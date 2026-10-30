package com.verifone.cat.launch;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.verifone.cat.build.Installed;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.provision.ShimStamp;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigBusyException;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.upgrade.NewInstall;
import com.verifone.cat.upgrade.NewInstallTools;
import com.verifone.cat.upgrade.StepResult;

/**
 * {@code cat-java install-resume --rig R [--build-dir DIR | --build-label L] [--dist D] [--continue]
 * [--no-forecourt]}: carries a PSI install on from its root-access push, for a Commander that came back after the
 * install (it answers CGILink with the PSI password) without root SSH (CAT-Java HANDOFF §6 item 1a). Neither CAT had
 * this; the order is step 4's after a new install (HANDOFF §6 item 1).
 *
 * <ol>
 *   <li>The build: {@code --build-dir}, else {@code --build-label} (a staged {@code <builds>/<label>/<dist>}), else the
 *       only staged build. With more than one staged and neither flag, the build the Commander's installed-build
 *       record names ({@code build.Installed}); when it names none the command refuses and lists them. The build used
 *       is printed.</li>
 *   <li>The rig's {@code <rig>.run} lease, the one a launch takes in System Checks: a resume never runs beside a run,
 *       and is released whatever happens.</li>
 *   <li>The root-access packages pushed to each device until root answers with the no-pw key, then the install's
 *       Commander tail (setenforce, the bypass switch, a reboot, SSH and CGILink back; no reboot when nothing was
 *       pushed): {@link RootAccessResume}, over {@code upgrade.RootAccess.pushUntilConfirmed}
 *       ({@link #PRODUCTION_RESUME}).</li>
 *   <li>Step 4's pos-shim build check ({@link CommanderConfigurationStep#posShimNeed}): the payload built when it is
 *       not the sources' build, a WARN at worst, before the first provision.</li>
 *   <li>{@code psi-init --no-pairing} (the Commander password PSI left), {@code provision}, {@code psi-init} with
 *       pairing, {@code provision} again (pairing reboots the register).</li>
 *   <li>By default it stops there and prints the launch that carries on ({@code launch --rig R --upgrade skip
 *       --capture-snapshot ...}): the rest of step 4 needs the run's simulators. Only with {@code --continue}, and only
 *       when Petro Simulator Suite (port {@value #PETRO_PORT}) and {@value CommanderConfigurationStep#PAYMENT_HOST}'s
 *       port are listening on this PC (checked before anything is sent): {@code commander-prep},
 *       {@code commander-init} ({@code --no-forecourt} on request), {@code secondary-fep none}, {@code payment-links},
 *       {@code emv-keys --tables always}, {@code register-app}, {@code close-day}, {@code payment-ready} (a WARN), the
 *       Commander snapshot capture ({@code --if-missing --twice}, with {@code --build-label}; a WARN) and the
 *       installed-build read.</li>
 * </ol>
 *
 * <p>{@code --stop-after-pairing} is still accepted: it is the default now (review, 2026-10-29), because after a failed
 * run nothing is listening on Petro's or the payment host's port and the chain stopped partway through step 4.
 *
 * <p>Not here, because they belong to a run: the pinpad link, the printer-port hold and the BUYPASS Host restart after
 * a FEP drift fix (a WARN line asks for it by hand).
 *
 * <p>Exit codes: 0 every stage passed, 1 a stage failed (the chain stops there) or the resume was refused (no build
 * could be told, the lease is held, the simulators {@code --continue} needs are not listening), 2 usage or a rig that
 * does not load.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): designed from HANDOFF §6 item 1 while the lab Commander was down; run
 * only against recording stages.
 */
public final class InstallResume {

    /** The install's root-access push and Commander tail, from where an interrupted install stopped. */
    @FunctionalInterface
    public interface RootAccessResume {
        /** @return one result per step, in order; the last is the failure, if there was one */
        List<StepResult> resume(Rig rig, Path buildDir, Map<String, String> environ, Consumer<String> log);
    }

    /** Whether something listens on a port of this PC. */
    @FunctionalInterface
    public interface PortProbe {
        boolean listening(int port);
    }

    /** Petro Simulator Suite's port, which {@code --continue} needs listening. */
    static final int PETRO_PORT = 9001;

    /** The step a resume reports when the build's tools are not on this PC: nothing was sent. */
    static final String STEP_TOOLS = "root_access_tools";

    /** A loopback connect, bounded: the controller's own listeners. */
    static final PortProbe LOOPBACK = port -> {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    };

    /**
     * The production resume: {@link NewInstall#resumeRootAccess} with the build's PSI tools
     * ({@link NewInstallTools#forBuild}, overridable by the {@code PETROTEST_*} variables). Tools that are missing are
     * one failed step naming them, before anything is sent.
     */
    public static final RootAccessResume PRODUCTION_RESUME = (rig, buildDir, environ, log) -> {
        NewInstallTools tools;
        try {
            tools = NewInstallTools.forBuild(buildDir, environ);
        } catch (IllegalArgumentException e) {
            return List.of(StepResult.failed(STEP_TOOLS, e.getMessage()));
        }
        return NewInstall.system(tools, log).resumeRootAccess(rig, environ);
    };

    static final RigCommand.Spec SPEC = new RigCommand.Spec("install-resume",
            "usage: cat-java install-resume --rig <name|file.yaml> [--build-dir DIR | --build-label L] [--dist D] "
                    + "[--continue] [--no-forecourt]",
            Map.of("--build-dir", "", "--dist", LaunchConfig.DEFAULT_DIST, "--build-label", ""), Set.of(),
            Set.of("--no-forecourt", "--stop-after-pairing", "--continue"), Set.of(), List.of());

    private final RigCommand.Loader loader;
    private final Function<Map<String, String>, CommanderStages> stages;
    private final RootAccessResume resume;
    private final PortProbe ports;

    /** The production command: the real stages. */
    public InstallResume() {
        this(RigCommand.Loader.RIGS, CommanderStages::real, PRODUCTION_RESUME);
    }

    InstallResume(RigCommand.Loader loader, Function<Map<String, String>, CommanderStages> stages,
            RootAccessResume resume) {
        this(loader, stages, resume, LOOPBACK);
    }

    InstallResume(RigCommand.Loader loader, Function<Map<String, String>, CommanderStages> stages,
            RootAccessResume resume, PortProbe ports) {
        this.loader = loader;
        this.stages = stages;
        this.resume = resume;
        this.ports = ports;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        return RigCommand.run(SPEC, argv, env, out, err, loader, (rig, args, environ, say) ->
                RigCommand.exit(leased(args.rig(), rig, args, stages.apply(environ), environ, say)));
    }

    /** The resume under the rig's {@code <rig>.run} lease, the one a launch's System Checks takes. */
    boolean leased(String rigName, Rig rig, RigCommand.Args args, CommanderStages stages, Map<String, String> environ,
            Say say) {
        String name = rig.name();
        RigLease lease = new RigLease(name + ".run", RigLease.resolveLeaseDir(null, environ),
                "cat-java install-resume (pid " + ProcessHandle.current().pid() + ")");
        try {
            lease.acquire(SystemChecksStep.RUN_LOCK_WAIT);
        } catch (RigBusyException e) {
            say.err("install-resume: rig '" + name + "' is in use by another CAT run: " + lease.describeHolder());
            return false;
        } catch (RuntimeException e) {
            say.err("install-resume: the run lock could not be taken: " + e.getMessage());
            return false;
        }
        try {
            return resume(rigName, rig, args, stages, environ, say);
        } finally {
            lease.release();
        }
    }

    /** The chain; false at the first stage that failed, which is said last on the error stream. */
    boolean resume(String rigName, Rig rig, RigCommand.Args args, CommanderStages stages, Map<String, String> environ,
            Say say) {
        String dist = args.value("--dist");
        boolean carryOn = args.has("--continue");
        if (carryOn) {
            String missing = missingSimulators();
            if (!missing.isEmpty()) {
                say.err("install-resume: --continue needs the run's simulators on this PC, and " + missing
                        + ". Start them (Launch Simulators), or leave out --continue to stop after pairing.");
                return false;
            }
        }
        Path buildDir = buildDir(rig, args, stages, dist, say);
        if (buildDir == null) {
            return false;
        }
        heading(say, "root access (" + buildDir + ")");
        List<StepResult> pushed;
        try {
            pushed = resume.resume(rig, buildDir, environ, say::out);
        } catch (StageUnavailable e) {
            say.err("install-resume: " + e.getMessage());
            return false;
        }
        for (StepResult result : pushed) {
            if (result.failed()) {
                say.err("install-resume: " + result.step() + " failed: " + result.detail());
                return false;
            }
        }
        posShim(rig, stages, environ, say);
        if (!stage(say, "psi-init --no-pairing", () -> stages.psiInit(rig, false, say))
                || !stage(say, "provision", () -> stages.provision(rig, say))
                || !stage(say, "psi-init", () -> stages.psiInit(rig, true, say))
                || !stage(say, "provision", () -> stages.provision(rig, say))) {
            return false;
        }
        if (!carryOn) {
            say.out("install-resume: paired and provisioned; the rest of step 4 needs the run's simulators. Next:");
            say.out("  launch --rig " + rigName + " --upgrade skip --capture-snapshot --plan <key>");
            return true;
        }
        boolean forecourt = !args.has("--no-forecourt");
        boolean[] driftFixed = {false};
        Say heard = new Say() {
            @Override
            public void out(String line) {
                if (line.startsWith(CommanderLinks.FEP_DRIFT_FIXED)) {
                    driftFixed[0] = true;
                }
                say.out(line);
            }

            @Override
            public void err(String line) {
                say.err(line);
            }
        };
        if (!stage(say, "commander-prep", () -> stages.commanderPrep(rig, say))
                || !stage(say, "commander-init", () -> stages.commanderInit(rig, forecourt, say))
                || !stage(say, "secondary-fep none", () -> stages.secondaryFep(rig, List.of("none"), "", Map.of(), say))
                || !stage(say, "payment-links", () -> stages.paymentLinks(rig, heard))) {
            return false;
        }
        if (driftFixed[0]) {
            say.out("WARN  " + CommanderConfigurationStep.PAYMENT_HOST + " may answer nothing after the FEP drift fix "
                    + "until it is restarted by hand");
        }
        if (!stage(say, "emv-keys --tables always", () -> stages.emvKeys(rig, "always", say))
                || !stage(say, "register-app", () -> stages.registerApp(rig, say))) {
            return false;
        }
        heading(say, "close-day");
        CloseDay.Outcome closed = stages.closeDay(rig, say);
        if (closed == CloseDay.Outcome.FAILED) {
            say.err("install-resume: close-day failed");
            return false;
        }
        if (closed == CloseDay.Outcome.FORCED_AEOD) {
            say.out("WARN  the Commander forces automatic end of day and refused the close");
        }
        heading(say, "payment-ready");
        if (!stages.paymentReady(rig, say)) {
            say.out("WARN  the EPS was not ready for card sales; see payment-ready above");
        }
        heading(say, "commander-snapshot capture");
        if (!stages.snapshotCapture(rigName, args.value("--build-label"), say)) {
            say.out("WARN  commander-snapshot capture failed; see its lines above");
        }
        try {
            Installed.Identification found = stages.installedBuild(rig, dist);
            say.out("Installed build: " + (found.name().isEmpty() ? "unknown — " + found.why() : found.why()));
        } catch (RuntimeException e) {
            say.out("Installed build: unknown — " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        say.out("install-resume: step 4 done; run the plan with launch --upgrade skip");
        return true;
    }

    /**
     * Which of Petro Simulator Suite and the payment host is not listening, as one clause, or "" when both are. The
     * ports are the simulator registry's.
     */
    String missingSimulators() {
        List<String> missing = new ArrayList<>();
        for (String name : List.of(StartSimulatorsStep.PETRO_SIMULATOR, CommanderConfigurationStep.PAYMENT_HOST)) {
            int port = Registry.byName(name).map(SimEntry::readyTcp).filter(p -> p > 0)
                    .orElse(name.equals(StartSimulatorsStep.PETRO_SIMULATOR) ? PETRO_PORT : 0);
            if (port > 0 && !ports.listening(port)) {
                missing.add(name + " (port " + port + ")");
            }
        }
        return missing.isEmpty() ? "" : String.join(" and ", missing) + (missing.size() == 1 ? " is" : " are")
                + " not listening";
    }

    /**
     * The build to resume from, printed with why, or null (said on the error stream) when it cannot be told: a named
     * {@code --build-dir}; a {@code --build-label} that is staged; the only staged build; or, with several staged, the
     * one the Commander's installed-build record names.
     */
    static Path buildDir(Rig rig, RigCommand.Args args, CommanderStages stages, String dist, Say say) {
        String given = args.value("--build-dir");
        if (!given.isBlank()) {
            Path dir = Path.of(given);
            say.out("install-resume: build " + dir + " (from --build-dir)");
            return dir;
        }
        String label = args.value("--build-label").strip();
        if (!label.isEmpty()) {
            Path dir = stages.buildsRoot().resolve(label).resolve(dist);
            if (!Files.isDirectory(dir)) {
                say.err("install-resume: --build-label " + label + " is not staged: no " + dir
                        + "; name the folder with --build-dir");
                return null;
            }
            say.out("install-resume: build " + dir + " (from --build-label)");
            return dir;
        }
        List<Path> staged = stages.stagedBuilds(dist);
        if (staged.isEmpty()) {
            say.err("install-resume: no build under " + stages.buildsRoot() + " holds " + dist
                    + " with the PSI files; name one with --build-dir");
            return null;
        }
        if (staged.size() == 1) {
            say.out("install-resume: build " + staged.get(0) + " (the only one staged)");
            return staged.get(0);
        }
        String installed = "";
        String why;
        try {
            Installed.Identification found = stages.installedBuild(rig, dist);
            installed = found.name();
            why = found.why();
        } catch (RuntimeException e) {
            why = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (!installed.isEmpty()) {
            for (Path dir : staged) {
                if (buildLabel(dir).equalsIgnoreCase(installed)) {
                    say.out("install-resume: build " + dir + " (the Commander's installed build, " + installed + ")");
                    return dir;
                }
            }
        }
        List<String> names = staged.stream().map(InstallResume::buildLabel).toList();
        say.err("install-resume: " + staged.size() + " builds are staged (" + String.join(", ", names)
                + ") and the Commander's installed build is not one of them (" + why + "); name the one PSI installed "
                + "with --build-label or --build-dir");
        return null;
    }

    /** {@code <builds>/<label>/<dist>}'s label: the dist folder's parent's name, or "". */
    static String buildLabel(Path distDir) {
        Path parent = distDir.getParent();
        return parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
    }

    /** Step 4's pos-shim build check, before the first provision: a WARN at worst, never a failure. */
    static void posShim(Rig rig, CommanderStages stages, Map<String, String> environ, Say say) {
        Path payload = stages.posShimPayload();
        if (payload == null) {
            return;
        }
        heading(say, CommanderConfigurationStep.POS_SHIM_BUILD_ROW);
        Path repoRoot = stages.posShimRepoRoot();
        String root = environ.getOrDefault("PETROAPPS_ROOT", "").strip();
        Path petroapps = Path.of(root.isEmpty() ? Collect.DEFAULT_PETROAPPS_ROOT : root);
        CommanderConfigurationStep.PosShimNeed need = CommanderConfigurationStep.posShimNeed(payload, repoRoot,
                petroapps);
        if (!need.unknown().isEmpty()) {
            say.out("WARN  " + CommanderConfigurationStep.POS_SHIM_BUILD_ROW + " — " + need.unknown());
            return;
        }
        if (!need.current().isEmpty()) {
            say.out("OK    " + need.current());
            return;
        }
        Path script = ShimStamp.resolve(repoRoot, ShimStamp.BUILD_SCRIPT);
        if (!Files.isRegularFile(script)) {
            say.out("SKIP  " + CommanderConfigurationStep.POS_SHIM_BUILD_ROW + " — " + script
                    + " is not there; provision will report it");
            return;
        }
        say.out("BUILD pos-shim — " + need.why() + "; compiling bridge/pos-shim/");
        boolean ok;
        try {
            ok = stages.buildPosShim(rig, petroapps, say);
        } catch (RuntimeException e) {
            say.err(e.getClass().getSimpleName() + ": " + e.getMessage());
            ok = false;
        }
        if (!ok) {
            say.out("WARN  " + CommanderConfigurationStep.POS_SHIM_BUILD_ROW + " failed. It needs a JDK, petroapps, and "
                    + "the Topaz vendor jars, which it fetches over SSH from the rig's register. Leaving provision to "
                    + "report the missing payload.");
            return;
        }
        Optional<String> now = ShimStamp.payloadStamp(payload);
        say.out("OK    pos-shim built — " + (now.isPresent() ? "build " + now.get().substring(0,
                Math.min(12, now.get().length())) : "no " + ShimStamp.STAMP_NAME + " written"));
    }

    private static void heading(Say say, String stage) {
        say.out("install-resume: " + stage);
    }

    private static boolean stage(Say say, String name, java.util.function.BooleanSupplier body) {
        heading(say, name);
        boolean ok;
        try {
            ok = body.getAsBoolean();
        } catch (StageUnavailable e) {
            say.err("install-resume: " + name + ": " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            say.err("install-resume: " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
        if (!ok) {
            say.err("install-resume: " + name + " failed; the chain stops here");
        }
        return ok;
    }
}
