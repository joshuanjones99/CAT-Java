package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * The device half of coverage against in-memory devices that reboot in a map. Evidence about this repository,
 * not about a rig: no host is reached, and the Python port's rig runs are not repeated here.
 */
class CoverageDeviceTest {

    private static final String COMMANDER_HOST = "cmdr.test";
    private static final String REGISTER_HOST = "reg101.test";
    private static final TargetSpec COMMANDER = new TargetSpec(TargetRole.COMMANDER, "commander", COMMANDER_HOST,
            "root", 22, 8801, null);
    private static final TargetSpec REGISTER = new TargetSpec(TargetRole.REGISTER, "topaz101", REGISTER_HOST,
            "root", 22, 8801, null);
    private static final List<TargetSpec> TARGETS = List.of(COMMANDER, REGISTER);
    private static final JacocoTools TOOLS = new JacocoTools(Path.of("java.exe"), Path.of("jacococli.jar"), null);
    private static final String EXEC_DIR = Components.COMMANDER_EXEC_DIR;

    @TempDir
    Path tmp;

    private Map<String, FakeDevice> devices;
    private List<List<String>> dumps;
    private List<String> log;

    @BeforeEach
    void setUp() {
        devices = new HashMap<>();
        dumps = new ArrayList<>();
        log = new ArrayList<>();
    }

    /** A device that reboots in a map. Its JVMs start with the agent when the jar is there and the agent loads. */
    static final class FakeDevice implements Transport {

        private final String host;
        private final boolean commander;
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> ran = new ArrayList<>();
        boolean agentLoaded;
        /** Whether a boot loads the agent at all; false models a build whose start scripts do not look for it. */
        boolean neverLoads;
        /** {@code ps} is missing: the process list cannot be read. */
        boolean psBroken;
        /** Mount checks that fail before the data drive appears. */
        int mountChecksBeforeMounted;
        /** Exec files that are listed on the device but cannot be read. */
        Set<String> unreadable = Set.of();
        int reboots;

        FakeDevice(String host, boolean commander, boolean jar, boolean loaded) {
            this.host = host;
            this.commander = commander;
            if (jar) {
                files.put(Components.AGENT_JAR, "agent".getBytes(StandardCharsets.UTF_8));
            }
            this.agentLoaded = loaded;
        }

        @Override
        public String host() {
            return host;
        }

        @Override
        public CommandResult run(String command, Duration timeout) {
            ran.add(command);
            if (command.startsWith("test -s")) {
                return new CommandResult(files.containsKey(Components.AGENT_JAR) ? 0 : 1, "", "");
            }
            if (command.equals(CoverageDevice.PROCESS_ARGS)) {
                if (psBroken) {
                    return new CommandResult(127, "", "bash: ps: command not found");
                }
                String agent = "-javaagent:/home/common/lib/jacocoagent.jar=";
                String tomcat = agentLoaded ? agent + "output=tcpserver,address=*,port=6300" : "";
                List<String> lines = new ArrayList<>(List.of("/sbin/init",
                        "/usr/java/j2se/bin/java -Dnop " + tomcat + " -Xms128M org.apache.catalina.Bootstrap"));
                if (commander) {
                    for (String vm : List.of("fcc", "pmc")) {
                        String option = agentLoaded ? agent + "destfile=" + EXEC_DIR + "/" + vm + ".exec" : "";
                        lines.add("/usr/java/j2se/bin/java -server " + option + " -Xmx512M Main" + vm);
                    }
                }
                return new CommandResult(0, String.join("\n", lines), "");
            }
            if (command.equals("reboot")) {
                reboots++;
                if (commander && agentLoaded && !neverLoads) {
                    for (String vm : List.of("fcc", "pmc")) {
                        files.put(EXEC_DIR + "/" + vm + ".exec", (vm + "-data").getBytes(StandardCharsets.UTF_8));
                    }
                }
                agentLoaded = !neverLoads && files.containsKey(Components.AGENT_JAR);
                return new CommandResult(0, "", "");
            }
            if (command.contains("/proc/mounts")) {
                if (mountChecksBeforeMounted > 0) {
                    mountChecksBeforeMounted--;
                    return new CommandResult(1, "", "");
                }
                return new CommandResult(0, "", "");
            }
            if (command.startsWith("ls -1")) {
                if (mountChecksBeforeMounted > 0) {
                    return new CommandResult(2, "", "No such file or directory");
                }
                List<String> listing = execs();
                return new CommandResult(listing.isEmpty() ? 2 : 0, String.join("\n", listing), "");
            }
            if (command.startsWith("rm -f")) {
                files.keySet().removeIf(k -> k.startsWith(EXEC_DIR) && k.endsWith(".exec"));
                return new CommandResult(0, "", "");
            }
            return new CommandResult(0, "", "");
        }

        private List<String> execs() {
            return files.keySet().stream().filter(k -> k.startsWith(EXEC_DIR) && k.endsWith(".exec")).sorted()
                    .toList();
        }

        @Override
        public void put(String remotePath, byte[] content, int mode, Duration timeout) {
            files.put(remotePath, content);
        }

        @Override
        public Optional<byte[]> get(String remotePath, Duration timeout) {
            if (unreadable.contains(remotePath)) {
                return Optional.empty();
            }
            return Optional.ofNullable(files.get(remotePath));
        }

        @Override
        public void mkdir(String remotePath, Duration timeout) {
            // Nothing here is a directory tree; the device keeps files only.
        }

        @Override
        public boolean exists(String remotePath, Duration timeout) {
            return files.containsKey(remotePath);
        }

        @Override
        public void close() {
            // The fake has no session to drop.
        }
    }

    private DeviceOps ops(boolean sshBack, boolean cgilinkBack) {
        Jacoco.CliRunner cli = (tools, args, timeout) -> {
            dumps.add(args);
            if (args.get(0).equals("dump")) {
                writeBytes(Path.of(args.get(args.indexOf("--destfile") + 1)), "tomcat");
            }
            return new Jacoco.CliResult(true, "");
        };
        return new DeviceOps(spec -> devices.get(spec.host()), (spec, rebootedAt) -> sshBack,
                host -> cgilinkBack, TOOLS, cli, log::add, Duration.ofMillis(500), Duration.ofMillis(1));
    }

    private static void writeBytes(Path file, String text) {
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void bothHealthy() {
        devices.put(COMMANDER_HOST, new FakeDevice(COMMANDER_HOST, true, true, true));
        devices.put(REGISTER_HOST, new FakeDevice(REGISTER_HOST, false, true, true));
    }

    @Test
    void aDeviceWithTheAgentRunningIsNotRebooted() {
        bothHealthy();

        assertTrue(CoverageDevice.prepare(TARGETS, ops(true, true), tmp));

        assertEquals(0, devices.get(COMMANDER_HOST).reboots);
        assertEquals(0, devices.get(REGISTER_HOST).reboots);
        assertTrue(devices.get(COMMANDER_HOST).ran.stream().anyMatch(c -> c.startsWith("rm -f")));
    }

    @Test
    void theOldDataIsThrownAwayAndEachTomcatsCountersReset() {
        bothHealthy();

        assertTrue(CoverageDevice.prepare(TARGETS, ops(true, true), tmp));

        List<String> resets = dumps.stream().filter(args -> args.contains("--reset"))
                .map(args -> args.get(args.indexOf("--address") + 1)).sorted().toList();
        assertEquals(List.of(COMMANDER_HOST, REGISTER_HOST), resets);
    }

    @Test
    void aMissingJarIsPushedAndTheDeviceRebootedToLoadIt() throws IOException {
        Path agent = tmp.resolve("jacocoagent.jar");
        Files.write(agent, "agent-0.8.8".getBytes(StandardCharsets.UTF_8));
        devices.put(COMMANDER_HOST, new FakeDevice(COMMANDER_HOST, true, false, false));
        devices.put(REGISTER_HOST, new FakeDevice(REGISTER_HOST, false, true, true));
        JacocoTools tools = new JacocoTools(Path.of("java.exe"), Path.of("cli.jar"), agent);

        assertTrue(CoverageDevice.prepare(TARGETS, withTools(tools, true, true), tmp));

        assertArrayEquals("agent-0.8.8".getBytes(StandardCharsets.UTF_8),
                devices.get(COMMANDER_HOST).files.get(Components.AGENT_JAR));
        assertEquals(1, devices.get(COMMANDER_HOST).reboots);
        assertEquals(0, devices.get(REGISTER_HOST).reboots);
    }

    @Test
    void aProcessListThatCannotBeReadNeverCausesAReboot() {
        bothHealthy();
        devices.get(COMMANDER_HOST).psBroken = true;

        assertFalse(CoverageDevice.prepare(TARGETS, ops(true, true), tmp));

        assertEquals(0, devices.get(COMMANDER_HOST).reboots);
        assertTrue(log.stream().anyMatch(line -> line.contains("ProcessListUnreadable")), log.toString());
    }

    @Test
    void aJarNoJvmLoadsEvenAfterARebootFailsPrepare() {
        devices.put(COMMANDER_HOST, new FakeDevice(COMMANDER_HOST, true, true, true));
        FakeDevice register = new FakeDevice(REGISTER_HOST, false, true, false);
        register.neverLoads = true;
        devices.put(REGISTER_HOST, register);

        assertFalse(CoverageDevice.prepare(TARGETS, ops(true, true), tmp));

        assertEquals(1, register.reboots);
        assertTrue(log.stream().anyMatch(line -> line.contains("no JVM loaded it after a reboot")), log.toString());
    }

    @Test
    void aDeviceThatDoesNotComeBackAfterAPrepareRebootFailsPrepare() {
        devices.put(COMMANDER_HOST, new FakeDevice(COMMANDER_HOST, true, false, false));
        devices.put(REGISTER_HOST, new FakeDevice(REGISTER_HOST, false, true, true));

        assertFalse(CoverageDevice.prepare(TARGETS, withTools(agentTools(), false, true), tmp));

        assertTrue(log.stream().anyMatch(line -> line.contains("SSH did not come back")), log.toString());
    }

    @Test
    void theWaitsAreNotWiredSoARebootIsReportedNotAssumed() {
        devices.put(COMMANDER_HOST, new FakeDevice(COMMANDER_HOST, true, false, false));
        devices.put(REGISTER_HOST, new FakeDevice(REGISTER_HOST, false, true, true));
        DeviceOps unwired = new DeviceOps(spec -> devices.get(spec.host()), null, null, agentTools(),
                (tools, args, timeout) -> new Jacoco.CliResult(true, ""), log::add, null, null);

        assertFalse(CoverageDevice.prepare(TARGETS, unwired, tmp));

        assertTrue(log.stream().anyMatch(line -> line.contains("not ported")), log.toString());
        assertEquals(0, devices.get(COMMANDER_HOST).reboots, "the reboot is not sent while its wait is unwired");
    }

    @Test
    void tomcatsAreDumpedThenTheCommanderRebootedForItsExecFiles() throws IOException {
        bothHealthy();
        Path out = tmp.resolve("exec");

        assertTrue(CoverageDevice.collect(TARGETS, out, true, ops(true, true)));

        assertTrue(Files.isRegularFile(out.resolve("commander-tomcat.exec")));
        assertTrue(Files.isRegularFile(out.resolve("pos-tomcat-topaz101.exec")));
        assertEquals("fcc-data", Files.readString(out.resolve("fcc.exec"), StandardCharsets.UTF_8));
        assertEquals("pmc-data", Files.readString(out.resolve("pmc.exec"), StandardCharsets.UTF_8));
        assertEquals(1, devices.get(COMMANDER_HOST).reboots);
        assertTrue(devices.get(COMMANDER_HOST).execs().isEmpty(), "read exec files are deleted on the device");
        assertTrue(Files.readString(out.resolve("collect.json"), StandardCharsets.UTF_8).contains("\"rebooted\""));
    }

    @Test
    void noRebootCollectsTheTomcatsOnly() throws IOException {
        bothHealthy();
        Path out = tmp.resolve("exec");

        assertTrue(CoverageDevice.collect(TARGETS, out, false, ops(true, true)));

        assertTrue(Files.isRegularFile(out.resolve("commander-tomcat.exec")));
        assertFalse(Files.exists(out.resolve("fcc.exec")));
        assertEquals(0, devices.get(COMMANDER_HOST).reboots);
    }

    @Test
    void aFileThatCannotBeReadLeavesEveryExecFileOnTheCommander() {
        bothHealthy();
        FakeDevice commander = devices.get(COMMANDER_HOST);
        commander.unreadable = Set.of(EXEC_DIR + "/fcc.exec");

        assertFalse(CoverageDevice.collect(TARGETS, tmp.resolve("exec"), true, ops(true, true)));

        assertTrue(log.stream().anyMatch(line -> line.contains("vanished before it was read")), log.toString());
        assertTrue(commander.files.containsKey(EXEC_DIR + "/fcc.exec"));
        assertTrue(commander.files.containsKey(EXEC_DIR + "/pmc.exec"));
    }

    @Test
    void theExecFilesAreReadOnceTheDataDriveIsMountedNotBefore() throws IOException {
        bothHealthy();
        FakeDevice commander = devices.get(COMMANDER_HOST);
        commander.mountChecksBeforeMounted = 2;
        Path out = tmp.resolve("exec");

        assertTrue(CoverageDevice.collect(TARGETS, out, true, ops(true, true)));

        assertEquals(0, commander.mountChecksBeforeMounted);
        assertTrue(Files.isRegularFile(out.resolve("fcc.exec")));
    }

    @Test
    void aDriveThatNeverMountsIsAProblemAndTheFilesStay() {
        bothHealthy();
        FakeDevice commander = devices.get(COMMANDER_HOST);
        commander.mountChecksBeforeMounted = Integer.MAX_VALUE;

        assertFalse(CoverageDevice.collect(TARGETS, tmp.resolve("exec"), true, ops(true, true)));

        assertTrue(log.stream().anyMatch(line -> line.contains("was not mounted")), log.toString());
        assertTrue(commander.files.containsKey(EXEC_DIR + "/fcc.exec"));
    }

    @Test
    void aCommanderThatDoesNotComeBackIsAProblemNotAHang() {
        bothHealthy();

        assertFalse(CoverageDevice.collect(TARGETS, tmp.resolve("exec"), true, ops(false, true)));

        assertTrue(log.stream().anyMatch(line -> line.contains("SSH did not come back")), log.toString());
        assertTrue(devices.get(COMMANDER_HOST).files.containsKey(EXEC_DIR + "/fcc.exec"));
    }

    @Test
    void theProcessListNamesEachJvmsAgentOptions() {
        String lab = String.join("\n",
                "/sbin/init",
                "/usr/java/j2se/bin/java -Dnop -javaagent:/home/common/lib/jacocoagent.jar="
                        + "output=tcpserver,address=*,port=6300 -Xms128M org.apache.catalina.Bootstrap",
                "/usr/java/j2se/bin/java -server -javaagent:/home/common/lib/jacocoagent.jar="
                        + "destfile=/media/v950sc-drive/v950sc/logs/fcc.exec -Xmx512M Mainfcc");

        CoverageDevice.DeviceAgent agent = CoverageDevice.parseAgent("commander", COMMANDER_HOST, true, lab);

        assertTrue(agent.tcp());
        assertEquals(List.of("fcc"), agent.files());
        assertTrue(agent.loaded());
        assertTrue(agent.describe().contains("tomcat (tcp)"), agent.describe());
    }

    @Test
    void aJvmWithoutTheAgentIsNotLoadedAndSaysSo() {
        CoverageDevice.DeviceAgent agent = CoverageDevice.parseAgent("topaz101", REGISTER_HOST, true, "/sbin/init");

        assertFalse(agent.loaded());
        assertEquals("topaz101: agent jar present, loaded by no JVM", agent.describe());
    }

    @Test
    void aPathSpecificDestfileIsNamedByItsStem() {
        CoverageDevice.DeviceAgent agent = CoverageDevice.parseAgent("commander", COMMANDER_HOST, true,
                "-javaagent:/home/common/lib/jacocoagent.jar=destfile=/x/y/pmc.exec");

        assertEquals(List.of("pmc"), agent.files());
    }

    @Test
    void aBoundedPollReturnsAtTheDeadlineWithoutSleeping() {
        assertFalse(CoverageDevice.boundedPoll(() -> false, Duration.ofMillis(20), Duration.ofMillis(5)));
        assertTrue(CoverageDevice.boundedPoll(() -> true, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void aMissingAgentCannotBePushedWithoutAJarToPushAndThatIsAFailureNotAnException() {
        devices.put(COMMANDER_HOST, new FakeDevice(COMMANDER_HOST, true, false, false));
        devices.put(REGISTER_HOST, new FakeDevice(REGISTER_HOST, false, true, true));

        assertFalse(CoverageDevice.prepare(TARGETS, withTools(TOOLS, true, true), tmp));

        assertTrue(log.stream().anyMatch(line -> line.contains("no jacocoagent.jar to push")), log.toString());
    }

    /** Tools whose agent is a real file under {@code @TempDir}, so a push has something to read. */
    private JacocoTools agentTools() {
        try {
            Path agent = tmp.resolve("jacocoagent.jar");
            Files.write(agent, "agent-0.8.8".getBytes(StandardCharsets.UTF_8));
            return new JacocoTools(Path.of("java.exe"), Path.of("cli.jar"), agent);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DeviceOps withTools(JacocoTools tools, boolean sshBack, boolean cgilinkBack) {
        return ops(sshBack, cgilinkBack).withTools(tools);
    }
}
