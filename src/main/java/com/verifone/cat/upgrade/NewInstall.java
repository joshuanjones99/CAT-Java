package com.verifone.cat.upgrade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.recovery.Timeouts;
import com.verifone.cat.rig.Rig;

/**
 * The PSI (fresh install) path: {@code CICDScript.bat}'s {@code upgrade=PSI}, without its fixed waits.
 *
 * <p>A port of {@code petrotest.upgrade.new_install.upgrade_new_install}, in the same order:
 * <ol>
 *   <li>{@code bypass.mem.size.check} on the Commander;</li>
 *   <li>reboot each device, then start its installer at once ({@code Petro_Suite_Installer.jar -q -addr=<host>});</li>
 *   <li>as each device's breadcrumb reads {@code Installation Complete}, end its installer and push its root
 *       access (so a failed install pushes nothing);</li>
 *   <li>wait for every device to answer SSH from a boot after its push;</li>
 *   <li>the tail: {@code setenforce 0}, the bypass again, reboot the Commander, wait for SSH, then for CGILink.</li>
 * </ol>
 *
 * <p><b>No fixed waits.</b> Every wait polls for the thing the next step needs, bounded by a {@link Settings}
 * ceiling. The installer's dialogs are answered from {@link LabAnswers#psiAnswers}. The breadcrumb is the
 * installer's last act, so CAT closes each installer once it has landed.
 *
 * <p>Not ported here: the PSI password reset and pairing (the Python {@code psi_init}), which need the CGILink
 * client and the POS driver.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). This reformats the Commander's disk and reinstalls both devices.
 */
public final class NewInstall {

    /** {@code CICDScript.bat}'s breadcrumb text on success. */
    static final String BREADCRUMB_OK = "Installation Complete";
    static final String STEP_CGILINK = "cgilink_commander";
    static final String STEP_INSTALL = "install";

    /** How long a device has to answer {@code cat /proc/uptime} when SSH is being checked. */
    static final Duration ROOT_PROBE_TIMEOUT = Duration.ofSeconds(20);
    /** How long an installer gets to exit after it is asked to, before it is killed. */
    static final Duration INSTALLER_GRACE = Duration.ofSeconds(10);
    static final String CGILINK_PROBE_PATH = "/cgi-bin/CGILink?cmd=validate";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * The waits, each a ceiling rather than a delay. The defaults are the Python CAT's; a test passes short ones.
     *
     * @param breadcrumbTimeout the whole wait for every installer's breadcrumb
     * @param pushRetryFor how long a root-access push is retried
     * @param sshBackTimeout how long a device has to answer SSH after its push or reboot
     * @param cgilinkTimeout how long the Commander's application tier has to answer after its last reboot
     */
    public record Settings(Duration breadcrumbTimeout, Duration breadcrumbInterval, Duration pushRetryFor,
            Duration pushRetryInterval, Duration sshInterval, Duration sshBackTimeout, Duration cgilinkTimeout) {

        public static Settings defaults() {
            return new Settings(Duration.ofMinutes(45), Duration.ofSeconds(5), Duration.ofMinutes(25),
                    Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofMinutes(20), Duration.ofMinutes(15));
        }
    }

    private final NewInstallTools tools;
    private final Function<SshTarget, Transport> transports;
    private final Jars jars;
    private final Installer.Launcher installers;
    private final Settings settings;
    private final Predicate<String> cgilinkAnswers;
    private final Predicate<Target> rootSshAnswers;
    private final Consumer<String> log;

    /**
     * @param rootSshAnswers whether a device already takes the no-pw key as root; null for the real probe
     */
    public NewInstall(NewInstallTools tools, Function<SshTarget, Transport> transports, Jars jars,
            Installer.Launcher installers, Settings settings, Predicate<String> cgilinkAnswers,
            Predicate<Target> rootSshAnswers, Consumer<String> log) {
        this.tools = tools;
        this.transports = transports;
        this.jars = jars;
        this.installers = installers;
        this.settings = settings;
        this.cgilinkAnswers = cgilinkAnswers;
        this.rootSshAnswers = rootSshAnswers != null ? rootSshAnswers : this::sshRootAnswers;
        this.log = log;
    }

    /** The real thing: JSch, child processes, and the CGILink probe over HTTP. */
    public static NewInstall system(NewInstallTools tools, Consumer<String> log) {
        return new NewInstall(tools, JschTransport::new, Jars.SYSTEM, Installer.SYSTEM, Settings.defaults(),
                NewInstall::cgilinkAnswers, null, log);
    }

    /**
     * Runs the PSI path against {@code rig}, stopping at the first failed step.
     *
     * @return one result per step, in order; the last is the failure, if there was one
     */
    public List<StepResult> run(Rig rig, Map<String, String> environ) {
        Duration sshTimeout = Timeouts.of(rig.timeouts()).ssh();
        List<Target> targets = Target.fromRig(rig, environ);
        Target commander = targets.get(0);
        Ledger ledger = new Ledger(log);
        prepare(targets);
        if (!bypass(commander, sshTimeout, ledger)) {
            return ledger.results();
        }
        Map<String, Long> pushedAt = new HashMap<>();
        if (!installAll(targets, sshTimeout, ledger, pushedAt)) {
            return ledger.results();
        }
        for (Target target : targets) {
            if (!ledger.add(waitForSsh(target, pushedAt.get(target.name())))) {
                return ledger.results();
            }
        }
        return tail(commander, sshTimeout, ledger);
    }

    /**
     * Carries an interrupted PSI install on from its root-access push: for a Commander (and registers) that came back
     * installed (CGILink answers with the PSI password) without root SSH. For each device in turn, the push until root
     * answers with the no-pw key ({@link RootAccess#pushUntilConfirmed}); a device whose root already answers is not
     * pushed and not waited for. Then each pushed device's SSH from a boot after its push, then the Commander's tail
     * ({@code setenforce 0}, the bypass, a reboot, SSH, CGILink), as {@link #run} ends.
     *
     * <p>No Python counterpart: CAT-Java's {@code install-resume} (HANDOFF section 6 item 1a). It writes no installer
     * config and deletes no breadcrumb; nothing is installed.
     *
     * @return one result per step, in order; the last is the failure, if there was one
     */
    public List<StepResult> resumeRootAccess(Rig rig, Map<String, String> environ) {
        Duration sshTimeout = Timeouts.of(rig.timeouts()).ssh();
        List<Target> targets = Target.fromRig(rig, environ);
        Target commander = targets.get(0);
        Ledger ledger = new Ledger(log);
        Map<String, Long> pushedAt = new LinkedHashMap<>();
        for (Target target : targets) {
            if (!rootSshAnswers.test(target)) {
                // The mark is taken before the push: the boot the package causes comes after it.
                pushedAt.put(target.name(), System.nanoTime());
            }
            StepResult pushed = RootAccess.pushUntilConfirmed(tools, jars, target, sshTimeout,
                    settings.pushRetryFor(), settings.pushRetryInterval(), rootSshAnswers, ledger::note);
            if (!ledger.add(pushed)) {
                return ledger.results();
            }
        }
        for (Target target : targets) {
            Long mark = pushedAt.get(target.name());
            if (mark != null && !ledger.add(waitForSsh(target, mark))) {
                return ledger.results();
            }
        }
        return tail(commander, sshTimeout, ledger);
    }

    /** The installer's config file, and no breadcrumb left by an earlier run to count for this one. */
    private void prepare(List<Target> targets) {
        try {
            Files.writeString(tools.buildDir().resolve(LabAnswers.PSI_CONFIG_FILE),
                    LabAnswers.psiAnswers(tools.dist()), StandardCharsets.UTF_8);
            for (Target target : targets) {
                Files.deleteIfExists(breadcrumbPath(tools, target.host()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not prepare the PSI install in " + tools.buildDir(), e);
        }
    }

    private boolean bypass(Target commander, Duration timeout, Ledger ledger) {
        try (Transport box = transports.apply(commander.ssh())) {
            return ledger.add(RootAccess.setBypassMemSizeCheck(box, timeout));
        }
    }

    /**
     * Reboots and installs each device in turn, then pushes each one's root access as its breadcrumb lands.
     * Every installer still running is ended on the way out, so an early return leaves none beeping.
     */
    private boolean installAll(List<Target> targets, Duration sshTimeout, Ledger ledger, Map<String, Long> pushedAt) {
        Map<Path, Target> crumbs = new LinkedHashMap<>();
        Map<String, Installer> procs = new LinkedHashMap<>();
        try {
            for (Target target : targets) {
                try (Transport box = transports.apply(target.ssh())) {
                    ledger.add(reboot(box, "reboot_" + target.name(), sshTimeout));
                }
                try {
                    procs.put(target.name(), launch(target));
                } catch (IOException e) {
                    ledger.add(StepResult.failed("launch_" + target.name(), String.valueOf(e.getMessage())));
                    return false;
                }
                crumbs.put(breadcrumbPath(tools, target.host()), target);
                ledger.note("installer started against " + target.name() + " (" + target.host() + ")");
            }
            long deadline = System.nanoTime() + settings.breadcrumbTimeout().toNanos();
            while (!crumbs.isEmpty()) {
                Path crumb;
                try {
                    crumb = Bounded.pollUntil(
                            () -> crumbs.keySet().stream().filter(Files::isRegularFile).findFirst(),
                            remaining(deadline), settings.breadcrumbInterval(), "a PSI breadcrumb");
                } catch (Bounded.TimedOut e) {
                    String missing = crumbs.keySet().stream().map(p -> p.getFileName().toString())
                            .collect(Collectors.joining(", "));
                    ledger.add(StepResult.failed(STEP_INSTALL, e.getMessage() + "; missing: " + missing));
                    return false;
                }
                Target target = crumbs.remove(crumb);
                String text = readText(crumb).strip();
                boolean ok = text.contains(BREADCRUMB_OK);
                if (!ledger.add(new StepResult("install_" + target.name(),
                        ok ? StepResult.Status.DONE : StepResult.Status.FAILED, "'" + text + "'"))) {
                    return false;
                }
                // The breadcrumb is the installer's last act; from here it only beeps.
                ledger.note(target.name() + ": " + endInstaller(procs.remove(target.name())));
                // The mark is taken before the push: the boot the package causes comes after it.
                pushedAt.put(target.name(), System.nanoTime());
                StepResult pushed = RootAccess.pushUntilConfirmed(tools, jars, target, sshTimeout,
                        settings.pushRetryFor(), settings.pushRetryInterval(), rootSshAnswers, ledger::note);
                if (!ledger.add(pushed)) {
                    return false;
                }
            }
            return true;
        } finally {
            for (Map.Entry<String, Installer> entry : procs.entrySet()) {
                ledger.note(entry.getKey() + ": " + endInstaller(entry.getValue()));
            }
        }
    }

    /** The Commander's tail: {@code setenforce 0}, the bypass, a reboot, then SSH and CGILink. */
    private List<StepResult> tail(Target commander, Duration sshTimeout, Ledger ledger) {
        long rebootedAt;
        try (Transport box = transports.apply(commander.ssh())) {
            try {
                box.run("setenforce 0", sshTimeout);
            } catch (DeviceError e) {
                ledger.add(StepResult.failed("setenforce_0", String.valueOf(e.getMessage())));
                return ledger.results();
            }
            if (!ledger.add(RootAccess.setBypassMemSizeCheck(box, sshTimeout))) {
                return ledger.results();
            }
            rebootedAt = System.nanoTime();
            ledger.add(reboot(box, "reboot_commander_after_bypass", sshTimeout));
        }
        if (ledger.add(waitForSsh(commander, rebootedAt))) {
            ledger.add(waitForCgilink(commander.host()));
        }
        return ledger.results();
    }

    private Installer launch(Target target) throws IOException {
        List<String> argv = List.of(tools.javaBin(), "-jar", tools.installerJar().toString(), "-q",
                "-addr=" + target.host());
        return installers.start(argv, tools.buildDir(),
                tools.buildDir().resolve("psi_" + target.host() + ".log"));
    }

    /** Ends an installer whose breadcrumb has landed; says what happened. */
    private String endInstaller(Installer proc) {
        OptionalInt exit = proc.exitCode();
        if (exit.isPresent()) {
            return "installer exited by itself (" + exit.getAsInt() + ")";
        }
        proc.terminate();
        try {
            if (proc.awaitExit(INSTALLER_GRACE)) {
                return "installer closed";
            }
            proc.kill();
            proc.awaitExit(INSTALLER_GRACE);
            return "installer killed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.kill();
            return "installer killed";
        }
    }

    /** Polls until {@code target} accepts SSH from a boot later than {@code bootedAfter} (a System.nanoTime reading). */
    private StepResult waitForSsh(Target target, long bootedAfter) {
        String step = "ssh_" + target.name();
        try {
            Bounded.pollUntil(() -> sshBootedAfter(target, bootedAfter) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                    settings.sshBackTimeout(), settings.sshInterval(), "SSH on " + target.name());
            return StepResult.done(step);
        } catch (Bounded.TimedOut e) {
            return StepResult.failed(step, e.getMessage());
        }
    }

    private boolean sshBootedAfter(Target target, long bootedAfter) {
        try (Transport box = transports.apply(target.ssh())) {
            Optional<Double> uptime = uptime(box, ROOT_PROBE_TIMEOUT);
            return uptime.isPresent() && uptime.get() < seconds(System.nanoTime() - bootedAfter);
        } catch (DeviceError e) {
            return false;
        }
    }

    /** Polls until the Commander's application tier answers; every later step needs it. */
    private StepResult waitForCgilink(String host) {
        try {
            Bounded.pollUntil(() -> cgilinkAnswers.test(host) ? Optional.of(Boolean.TRUE) : Optional.empty(),
                    settings.cgilinkTimeout(), settings.sshInterval(), "CGILink");
            return StepResult.done(STEP_CGILINK);
        } catch (Bounded.TimedOut e) {
            return StepResult.failed(STEP_CGILINK, e.getMessage());
        }
    }

    /** Whether {@code target} already takes the no-pw key as root: the package's own effect. */
    private boolean sshRootAnswers(Target target) {
        try (Transport box = transports.apply(target.ssh())) {
            return uptime(box, ROOT_PROBE_TIMEOUT).isPresent();
        } catch (DeviceError e) {
            return false;
        }
    }

    /**
     * The CGILink probe: a deliberately malformed {@code validate}, which reaches AAA before any handler. A 503 is
     * Apache up with Tomcat still starting; a fault with {@code AAA service unavailable} is the tier down. Sends
     * no credential, so it can never count as a failed login.
     */
    static boolean cgilinkAnswers(String host) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + CGILINK_PROBE_PATH))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && !response.body().contains("AAA service unavailable");
        } catch (IOException | IllegalArgumentException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code CICDScript.bat}'s breadcrumb for a device: last octet {@code 11} is the Commander, else {@code _Reg<octet>}. */
    static Path breadcrumbPath(NewInstallTools tools, String host) {
        String octet = host.substring(host.lastIndexOf('.') + 1);
        String suffix = "11".equals(octet) ? "_CMDR" : "_Reg" + octet;
        return tools.buildDir().resolve("psi_complete" + suffix);
    }

    private static StepResult reboot(Transport box, String step, Duration timeout) {
        try {
            box.run("reboot", timeout);
        } catch (DeviceError e) {
            // The channel dropping is the expected outcome of a reboot.
        }
        return StepResult.done(step, "reboot sent");
    }

    /** Seconds since the device booted, or empty if it does not answer. */
    private static Optional<Double> uptime(Transport box, Duration timeout) {
        try {
            CommandResult result = box.run("cat /proc/uptime", timeout);
            if (!result.ok()) {
                return Optional.empty();
            }
            return Optional.of(Double.parseDouble(result.stdout().strip().split("\\s+")[0]));
        } catch (DeviceError | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0L, deadline - System.nanoTime()));
    }

    private static double seconds(long nanos) {
        return nanos / 1e9;
    }

    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** The run's results, each one logged as it is recorded. */
    private static final class Ledger {
        private final List<StepResult> results = new ArrayList<>();
        private final Consumer<String> sink;

        Ledger(Consumer<String> sink) {
            this.sink = sink;
        }

        /** Records a step and logs it; true when the install may go on. */
        boolean add(StepResult result) {
            results.add(result);
            sink.accept(result.step() + ": " + result.status().wire()
                    + (result.detail().isEmpty() ? "" : " - " + result.detail()));
            return !result.failed();
        }

        void note(String line) {
            sink.accept(line);
        }

        List<StepResult> results() {
            return List.copyOf(results);
        }
    }
}
