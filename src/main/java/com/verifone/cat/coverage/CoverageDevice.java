package com.verifone.cat.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;

/**
 * The device half of coverage: get the agent running before the tests, and pull its data after them.
 *
 * <p><b>{@link #prepare}</b> (step 4, before {@code provision}): on the Commander and each register, read
 * whether {@link Components#AGENT_JAR} is there and whether any JVM loaded it (from {@code ps -eo args}).
 * A missing jar is pushed; a device whose JVMs did not load the agent is rebooted, and waited for. The
 * Commander's old {@code *.exec} files are deleted, and each Tomcat's counters are reset over TCP, so this
 * run's report is this run's.
 *
 * <p><b>{@link #collect}</b> (step 6): dump each Tomcat over TCP, before anything restarts it. Then reboot
 * the Commander so each file-output JVM writes its {@code <vm>.exec} on the way down; wait for SSH and
 * CGILink, wait for the data drive to mount, read each exec file back, and delete it on the Commander only
 * when every one arrived. {@code reboot = false} collects the Tomcats alone.
 *
 * <p>The reboot waits go through {@link DeviceOps#waitForSsh} and {@link DeviceOps#waitForCgilink}, which
 * have no default: while they are unwired a reboot is reported as a problem, not assumed to have worked.
 *
 * <p>The Python source is {@code petrotest.coverage.device}. Its process-list check is {@code ps -eo args}
 * because neither device has {@code tr} or {@code id} (run {@code b7652389ca5c}, 2026-10-13).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). What the Python one has not run either: pushing a missing agent
 * jar, and collect's wait for the mount after a live reboot.
 */
public final class CoverageDevice {

    static final String PROCESS_ARGS = "ps -eo args";

    /** Per-call budget for SSH commands. */
    static final Duration SSH_TIMEOUT = Duration.ofSeconds(30);

    /** A read of one exec file: four times the usual budget. */
    static final Duration GET_TIMEOUT = SSH_TIMEOUT.multipliedBy(4);

    /** Per-call budget for a {@code jacococli dump}. */
    static final Duration CLI_TIMEOUT = Duration.ofMinutes(5);

    private static final Pattern AGENT_OPTION = Pattern.compile("jacocoagent\\.jar=(\\S+)");
    private static final Pattern DESTFILE = Pattern.compile("destfile=[^, ]*/([^/, ]+)\\.exec");

    private static final ObjectMapper JSON = new ObjectMapper();

    private CoverageDevice() {}

    /** A process list that could not be read: whether the agent is loaded is then unknown, never "no". */
    public static final class ProcessListUnreadable extends RuntimeException {

        public ProcessListUnreadable(String message) {
            super(message);
        }
    }

    /**
     * What one device's JVMs run with: {@code tcp} for Tomcat, and {@code files} for each file-output JVM.
     *
     * @param jarPresent whether {@link Components#AGENT_JAR} is on the device
     * @param files      the file-output JVMs' exec stems, sorted
     */
    public record DeviceAgent(String name, String host, boolean jarPresent, boolean tcp, List<String> files) {

        public DeviceAgent {
            files = List.copyOf(files);
        }

        /** Whether any JVM on the device loaded the agent. */
        public boolean loaded() {
            return tcp || !files.isEmpty();
        }

        public String describe() {
            if (!jarPresent) {
                return name + ": no agent jar at " + Components.AGENT_JAR;
            }
            if (!loaded()) {
                return name + ": agent jar present, loaded by no JVM";
            }
            List<String> vms = new ArrayList<>();
            if (tcp) {
                vms.add("tomcat (tcp)");
            }
            vms.addAll(files);
            return name + ": agent loaded in " + String.join(", ", vms);
        }
    }

    /** A Tomcat's exec file name, and the box it is dumped from. */
    private record TomcatTarget(TargetSpec spec, String execName) {}

    /** {@link #prepare(List, DeviceOps, Path)} for a rig's own boxes, read with the given environment. */
    public static boolean prepare(Rig rig, Map<String, String> environ, DeviceOps ops, Path scratch) {
        return prepare(TargetSpec.fromRig(rig, environ), ops, scratch);
    }

    /**
     * Step 4's half: the agent running on every device, and the Tomcats' counters reset.
     *
     * @return true when every device has the agent running
     */
    public static boolean prepare(List<TargetSpec> targets, DeviceOps ops, Path scratch) {
        Consumer<String> log = ops.log();
        JacocoTools tools;
        try {
            tools = ops.tools() != null ? ops.tools()
                    : Jacoco.findTools(Jacoco.Locations.fromEnvironment(System.getenv()), true);
        } catch (JacocoUnavailable e) {
            log.accept("FAIL  coverage tools — " + e.getMessage());
            return false;
        }
        log.accept("OK    jacococli " + tools.cli() + "; agent " + tools.agent() + "; java " + tools.java());

        boolean ok = true;
        String commanderHost = "";
        for (TargetSpec spec : targets) {
            boolean isCommander = spec.role() == TargetRole.COMMANDER;
            if (isCommander) {
                commanderHost = spec.host();
            }
            DeviceAgent agent;
            try {
                agent = prepareDevice(spec, tools, ops, isCommander);
            } catch (RuntimeException e) {
                log.accept("FAIL  " + spec.name() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ok = false;
                continue;
            }
            if (agent == null || !agent.loaded()) {
                ok = false;
            }
        }
        if (!ok) {
            return false;
        }

        // This run's Tomcat coverage starts here: whatever the JVMs held is thrown away.
        Path dir = scratch != null ? scratch : Path.of(System.getProperty("java.io.tmpdir"));
        for (TomcatTarget target : tomcatTargets(targets)) {
            Path discard = dir.resolve("reset-" + target.execName());
            Jacoco.CliResult result = ops.cli().run(tools,
                    Jacoco.dumpArgs(target.spec().host(), Components.TOMCAT_PORT, discard, true), CLI_TIMEOUT);
            deleteQuietly(discard);
            if (result.ok()) {
                log.accept("OK    " + target.spec().name() + ": Tomcat coverage reset");
            } else {
                log.accept("WARN  " + target.spec().name() + ": could not reset Tomcat's coverage ("
                        + result.detail() + ")");
            }
        }
        log.accept("OK    coverage ready (Commander " + commanderHost + ")");
        return true;
    }

    /** Step 6's half: {@link #collect(List, Path, boolean, DeviceOps)} for a rig's own boxes. */
    public static boolean collect(Rig rig, Map<String, String> environ, Path outDir, boolean reboot, DeviceOps ops) {
        return collect(TargetSpec.fromRig(rig, environ), outDir, reboot, ops);
    }

    /**
     * Step 6's half: writes the exec files into {@code outDir} and {@code collect.json} beside them.
     *
     * @return true when every expected file arrived
     */
    public static boolean collect(List<TargetSpec> targets, Path outDir, boolean reboot, DeviceOps ops) {
        Consumer<String> log = ops.log();
        Map<String, Object> manifest = new LinkedHashMap<>();
        Map<String, String> tomcat = new LinkedHashMap<>();
        Map<String, Integer> files = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        manifest.put("tomcat", tomcat);
        manifest.put("files", files);
        manifest.put("rebooted", false);
        manifest.put("problems", problems);
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        JacocoTools tools;
        try {
            tools = ops.tools() != null ? ops.tools()
                    : Jacoco.findTools(Jacoco.Locations.fromEnvironment(System.getenv()), false);
        } catch (JacocoUnavailable e) {
            log.accept("FAIL  coverage tools — " + e.getMessage());
            return false;
        }

        for (TomcatTarget target : tomcatTargets(targets)) {
            Path destfile = outDir.resolve(target.execName());
            Jacoco.CliResult result = ops.cli().run(tools,
                    Jacoco.dumpArgs(target.spec().host(), Components.TOMCAT_PORT, destfile, false), CLI_TIMEOUT);
            if (result.ok() && Files.isRegularFile(destfile)) {
                tomcat.put(target.spec().name(), target.execName());
                log.accept("OK    " + target.spec().name() + ": Tomcat dumped to " + target.execName() + " ("
                        + size(destfile) + " bytes)");
            } else {
                problems.add(target.spec().name() + ": Tomcat dump failed (" + result.detail() + ")");
                log.accept("FAIL  " + target.spec().name() + ": Tomcat dump failed (" + result.detail() + ")");
            }
        }

        TargetSpec commander = targets.stream().filter(s -> s.role() == TargetRole.COMMANDER).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no Commander among the targets"));
        if (reboot) {
            manifest.put("rebooted", true);
            collectCommanderFiles(commander, outDir, ops, problems, files, log);
        } else {
            log.accept("SKIP  Commander file-output JVMs — --no-reboot: they write their data only on exit");
        }

        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("collect.json").toFile(), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return problems.isEmpty();
    }

    /**
     * Reads {@code /proc}-style process lines (one per line, as {@code ps -eo args} prints them) and says
     * which JVMs were started with the agent. Exposed for tests and the device half.
     */
    static DeviceAgent parseAgent(String name, String host, boolean jarPresent, String processList) {
        Matcher option = AGENT_OPTION.matcher(processList);
        TreeSet<String> options = new TreeSet<>();
        while (option.find()) {
            options.add(option.group(1));
        }
        boolean tcp = false;
        List<String> files = new ArrayList<>();
        for (String text : options) {
            if (text.contains("output=tcpserver")) {
                tcp = true;
            }
            Matcher destfile = DESTFILE.matcher(text);
            if (destfile.find()) {
                files.add(destfile.group(1));
            }
        }
        Collections.sort(files);
        return new DeviceAgent(name, host, jarPresent, tcp, files);
    }

    /**
     * Polls {@code condition} until it holds or {@code timeout} passes. Returns whether it held. Uses
     * {@link LockSupport#parkNanos}, never {@code Thread.sleep}, and never waits past the deadline.
     */
    static boolean boundedPoll(BooleanSupplier condition, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return true;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                return false;
            }
            LockSupport.parkNanos(Math.min(interval.toNanos(), remaining));
        }
    }

    private static DeviceAgent readAgent(Transport transport, TargetSpec spec) {
        boolean jarPresent = transport.run("test -s " + Components.AGENT_JAR, SSH_TIMEOUT).ok();
        CommandResult listing = transport.run(PROCESS_ARGS, SSH_TIMEOUT);
        if (!listing.ok() || listing.stdout().isBlank()) {
            // Never a reason to reboot: a check that could not run has not said "not loaded".
            String stderr = listing.stderr().strip();
            String detail = stderr.isEmpty() ? "" : ": " + stderr.substring(0, Math.min(200, stderr.length()));
            throw new ProcessListUnreadable("`" + PROCESS_ARGS + "` on " + spec.name() + " failed (exit "
                    + listing.exitStatus() + detail + ")");
        }
        return parseAgent(spec.name(), spec.host(), jarPresent, listing.stdout());
    }

    /** Reboots a device and returns the {@link System#nanoTime} the reboot was sent at. A dropped session is not an error. */
    private static long reboot(Transport transport) {
        long rebootedAt = System.nanoTime();
        try {
            transport.run("reboot", SSH_TIMEOUT);
        } catch (DeviceError e) {
            // A reboot cuts the SSH session, so the command may report a failure that is the reboot itself.
        }
        return rebootedAt;
    }

    private static DeviceAgent prepareDevice(TargetSpec spec, JacocoTools tools, DeviceOps ops, boolean isCommander) {
        Consumer<String> log = ops.log();
        DeviceAgent agent = null;
        OptionalLong rebootedAt = OptionalLong.empty();
        Transport transport = ops.transportFactory().apply(spec);
        try {
            agent = readAgent(transport, spec);
            log.accept("      " + agent.describe());
            boolean reboot = false;
            if (!agent.jarPresent()) {
                if (tools.agent() == null) {
                    throw new IllegalStateException("no jacocoagent.jar to push");
                }
                transport.put(Components.AGENT_JAR, readFile(tools.agent()), 0644, SSH_TIMEOUT);
                log.accept("PUSH  " + spec.name() + ": " + tools.agent() + " -> " + Components.AGENT_JAR);
                reboot = true;
            } else if (!agent.loaded()) {
                reboot = true;
            }
            if (reboot && (ops.waitForSsh() == null || (isCommander && ops.waitForCgilink() == null))) {
                // Refused before the reboot is sent: a device this port cannot wait for is not rebooted.
                log.accept("FAIL  " + spec.name() + ": cannot reboot it; the SSH and CGILink waits are not ported yet");
                return agent;
            }
            if (reboot) {
                rebootedAt = OptionalLong.of(reboot(transport));
                log.accept("BOOT  " + spec.name() + ": rebooting so its JVMs start with the agent");
            }
        } finally {
            close(transport);
        }

        if (rebootedAt.isPresent()) {
            if (!ops.waitForSsh().await(spec, rebootedAt.getAsLong())) {
                log.accept("FAIL  " + spec.name() + ": SSH did not come back after the reboot");
                return null;
            }
            if (isCommander && !ops.waitForCgilink().test(spec.host())) {
                log.accept("FAIL  " + spec.name() + ": CGILink did not come back after the reboot");
                return null;
            }
            Transport after = ops.transportFactory().apply(spec);
            try {
                agent = readAgent(after, spec);
            } finally {
                close(after);
            }
            log.accept("      after reboot — " + agent.describe());
            if (!agent.loaded()) {
                log.accept("FAIL  " + spec.name() + ": the agent jar is in place and no JVM loaded it after a reboot;"
                        + " this build's start scripts may not look for it");
                return agent;
            }
        }

        if (isCommander) {
            Transport removal = ops.transportFactory().apply(spec);
            try {
                removal.run("rm -f " + Components.COMMANDER_EXEC_DIR + "/*.exec", SSH_TIMEOUT);
            } finally {
                close(removal);
            }
            log.accept("OK    " + spec.name() + ": old " + Components.COMMANDER_EXEC_DIR + "/*.exec removed");
        }
        return agent;
    }

    private static void collectCommanderFiles(TargetSpec spec, Path outDir, DeviceOps ops, List<String> problems,
            Map<String, Integer> files, Consumer<String> log) {
        Consumer<String> problem = text -> {
            problems.add(spec.name() + ": " + text);
            log.accept("FAIL  " + spec.name() + ": " + text);
        };
        if (ops.waitForSsh() == null) {
            problem.accept("cannot wait for the reboot: the SSH wait is not ported yet");
            return;
        }
        long rebootedAt;
        try {
            Transport transport = ops.transportFactory().apply(spec);
            try {
                rebootedAt = reboot(transport);
            } finally {
                close(transport);
            }
        } catch (RuntimeException e) {
            problem.accept(e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        log.accept("BOOT  " + spec.name() + ": rebooting so each JVM writes its <vm>.exec on the way down");
        if (!ops.waitForSsh().await(spec, rebootedAt)) {
            problem.accept("SSH did not come back after the reboot");
            return;
        }

        // Up first, read second: SSH answers early in a boot, before the data drive is mounted (run
        // da4bfa334bd9 listed the directory 20 s into a boot, found it empty, and lost six files). CGILink
        // answering means the boot is done; the mount is then checked as well, never assumed.
        if (ops.waitForCgilink() == null) {
            problem.accept("the CGILink wait is not ported yet, so the boot is not confirmed done");
        } else if (!ops.waitForCgilink().test(spec.host())) {
            problem.accept("CGILink did not come back after the reboot");
        } else {
            log.accept("OK    " + spec.name() + ": back up (CGILink answers)");
        }
        try {
            readExecFiles(spec, outDir, ops, problems, files, log);
        } catch (RuntimeException e) {
            problem.accept("reading the exec files: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Copies every {@code <vm>.exec} off the Commander; deletes them there only when all arrived. */
    private static void readExecFiles(TargetSpec spec, Path outDir, DeviceOps ops, List<String> problems,
            Map<String, Integer> files, Consumer<String> log) {
        Consumer<String> problem = text -> {
            problems.add(spec.name() + ": " + text);
            log.accept("FAIL  " + spec.name() + ": " + text);
        };
        Transport transport = ops.transportFactory().apply(spec);
        try {
            String mounted = "grep -q ' " + Components.COMMANDER_EXEC_MOUNT + " ' /proc/mounts";
            boolean isMounted = boundedPoll(() -> transport.run(mounted, SSH_TIMEOUT).ok(), ops.mountTimeout(),
                    ops.mountPollInterval());
            if (!isMounted) {
                problem.accept(Components.COMMANDER_EXEC_MOUNT + " was not mounted within "
                        + ops.mountTimeout().toSeconds() + "s");
                return;
            }
            CommandResult listing = transport.run("ls -1 " + Components.COMMANDER_EXEC_DIR + "/*.exec", SSH_TIMEOUT);
            List<String> names = Arrays.stream(listing.stdout().strip().split("\\s+"))
                    .filter(token -> token.endsWith(".exec"))
                    .map(token -> token.substring(token.lastIndexOf('/') + 1))
                    .toList();
            if (names.isEmpty()) {
                problem.accept("no JVM wrote an exec file to " + Components.COMMANDER_EXEC_DIR);
                return;
            }
            boolean missed = false;
            for (String name : names) {
                Optional<byte[]> content = transport.get(Components.COMMANDER_EXEC_DIR + "/" + name, GET_TIMEOUT);
                if (content.isEmpty()) {
                    problem.accept(name + " vanished before it was read");
                    missed = true;
                    continue;
                }
                try {
                    Files.write(outDir.resolve(name), content.get());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                files.put(name, content.get().length);
                log.accept("OK    " + spec.name() + ": " + name + " (" + content.get().length + " bytes)");
            }
            // Deleted only when every file arrived: the next run's JVMs start from nothing, but a file that
            // was not read is left for a second collect to try again.
            if (!missed) {
                transport.run("rm -f " + Components.COMMANDER_EXEC_DIR + "/*.exec", SSH_TIMEOUT);
            }
        } finally {
            close(transport);
        }
    }

    private static List<TomcatTarget> tomcatTargets(List<TargetSpec> targets) {
        List<TomcatTarget> result = new ArrayList<>();
        for (TargetSpec spec : targets) {
            if (spec.role() == TargetRole.COMMANDER) {
                result.add(new TomcatTarget(spec, Components.COMMANDER_TOMCAT + ".exec"));
            } else {
                result.add(new TomcatTarget(spec, Components.posExecName(spec.name())));
            }
        }
        return List.copyOf(result);
    }

    private static byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static void close(Transport transport) {
        try {
            transport.close();
        } catch (RuntimeException e) {
            // Closing a dead session is not a result.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A scratch file left behind is not worth failing a step for.
        }
    }
}
