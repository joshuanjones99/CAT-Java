package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The supervisor's pre-launch steps (Petro's tank level simulator, the RXTX agent, the {@code artifactory.after} step)
 * and its wrong-variant stop: the Python CAT's {@code TestSpawnConfiguresFirst}, {@code TestRxtxAgent} and
 * {@code stop_wrong_variants} cases. Every child is the test JDK's own {@code java}; no simulator is started and no
 * process is killed (the process layer is {@link FakeProcessOps}).
 */
class SupervisorPreLaunchTest {

    @TempDir
    Path tools;

    private final List<String> lines = new CopyOnWriteArrayList<>();

    private final Events events = new Events() {
        @Override
        public void log(String name, String line) {
            lines.add(line);
        }
    };

    private Supervisor supervisor(FakeProcessOps ops) {
        Supervisor sup = new Supervisor(ops, Duration.ofMillis(300));
        sup.agentCache = tools.resolve("agent-cache");
        sup.serialPorts = Set.of("COM2");
        sup.sources = entry -> Optional.empty();
        return sup;
    }

    private static Path testJdk() {
        return SimTestSupport.testJdkHome().orElseThrow();
    }

    private boolean await(Supplier<Boolean> seen) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (seen.get()) {
                return true;
            }
            Park.park(Duration.ofMillis(100));
        }
        return seen.get();
    }

    @Test
    void theTankLevelFilesAreWrittenBeforeTheChildStartsOnOrOff() throws IOException {
        for (String port : new String[] {"COM2", ""}) {
            Path suite = PetroTlsTest.suite(tools.resolve("Petro" + port));
            SimEntry entry = SimEntry.exeRow("Child").exe(SimTestSupport.testJava().orElseThrow().toString())
                    .cwd(suite.getFileName().toString()).argv("-version")
                    .tls(PetroTlsTest.SPEC.simulators(), PetroTlsTest.SPEC.port()).build();
            Supervisor sup = supervisor(new FakeProcessOps());
            SimContext ctx = new SimContext(tools, "", SimContext.JAVA_DIR, null, null, port);
            SimProcess proc = sup.start(entry, ctx, events, "", "", Duration.ofSeconds(10));
            try {
                assertTrue(proc.spawned());
                String common = new String(Files.readAllBytes(suite.resolve(PetroTlsTest.SPEC.simulators())),
                        StandardCharsets.ISO_8859_1);
                assertEquals(!port.isEmpty(), common.contains(", TLS\r\n"), common);
                assertTrue(lines.stream().anyMatch(line -> line.contains("tank level simulator")), lines.toString());
            } finally {
                sup.stop(List.of(proc), Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void aPortLaunchesTheJvmWithTheRxtxAgent() throws IOException {
        PetroTlsTest.javac();
        Path suite = PetroTlsTest.suite(tools.resolve("Petro"));
        Path java = testJdk().resolve("bin").resolve("java.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(java)
                && Files.isRegularFile(java.resolveSibling("javaw.exe")), "a Windows JDK");
        SimEntry entry = SimEntry.exeRow("Child").exe(java.toString()).cwd("Petro").argv("-version")
                .tls(PetroTlsTest.SPEC.simulators(), PetroTlsTest.SPEC.port()).build();
        Supervisor sup = supervisor(new FakeProcessOps());
        SimContext ctx = new SimContext(tools, "", SimContext.JAVA_DIR, testJdk(), null, "COM2");
        SimProcess proc = sup.start(entry, ctx, events, "", "", Duration.ofSeconds(30));
        try {
            assertTrue(proc.entry().argv().get(0).startsWith("-javaagent:"), proc.entry().argv().toString());
            // No RXTX on this class path: the agent ran, and says what it could not load.
            assertTrue(await(() -> lines.stream().anyMatch(
                    line -> line.contains("cat-rxtx-agent: RXTX serial drivers not loaded"))), lines.toString());
        } finally {
            sup.stop(List.of(proc), Duration.ofSeconds(5));
            if (proc.process() != null) {
                proc.process().onExit().join();
            }
        }
        assertTrue(Files.isDirectory(suite));
        // Windows lets go of the agent jar a moment after the JVM is gone; the temp folder's cleanup needs it.
        Path jar = proc.entry().argv().get(0).substring("-javaagent:".length()).isEmpty() ? null
                : Path.of(proc.entry().argv().get(0).substring("-javaagent:".length()));
        assertTrue(await(() -> {
            try {
                Files.deleteIfExists(jar);
                return true;
            } catch (IOException e) {
                return false;
            }
        }), "the agent jar is released");
    }

    @Test
    void theAfterStepRunsOnEveryLaunchWithTheJdksJava() throws IOException {
        Path java = testJdk().resolve("bin").resolve("java.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(java.resolveSibling("javaw.exe")),
                "a Windows JDK");
        Files.createDirectories(tools.resolve("Child"));
        SimEntry entry = SimEntry.exeRow("Child").exe(java.toString()).cwd("Child").argv("-version").build();
        Supervisor sup = supervisor(new FakeProcessOps());
        sup.sources = row -> Optional.of(new SimSources.ArtifactorySource("repo", "folder", "file.zip", "",
                List.of("-version")));
        SimContext ctx = new SimContext(tools, "", SimContext.JAVA_DIR, testJdk(), null, "");
        for (int launch = 1; launch <= 2; launch++) {
            SimProcess proc = sup.start(entry, ctx, events, "", "", Duration.ofSeconds(10));
            sup.stop(List.of(proc), Duration.ofSeconds(5));
        }
        assertEquals(2, lines.stream().filter(line -> line.equals("Child: setup done")).count(), lines.toString());
    }

    @Test
    void theOtherBasesLoySimIsClosedByItsCommandLineAndMppaIsNeverTold() {
        FakeProcessOps ops = new FakeProcessOps();
        SimContext ctx = SimContext.of(tools, "");
        SimEntry loysim = Registry.byName("PCATS01 Loyalty").orElseThrow();
        SimEntry seven = loysim.olderBaseForm();
        String token = ctx.matchToken(seven);
        ops.commandLines = "javaw.exe -jar " + token;
        ops.listingText = "P\t4321\tjavaw.exe -jar " + token + "\n";
        Supervisor sup = supervisor(ops);

        Map<String, Supervisor.StopRow> closed = sup.stopWrongVariants(
                List.of(loysim, Registry.byName("MPPA Server Sim").orElseThrow()), Registry.otherVariants(false),
                ctx, Duration.ofSeconds(1));

        assertEquals(Set.of("PCATS01 Loyalty"), closed.keySet());
        assertEquals(Supervisor.StopOutcome.STOPPED, closed.get("PCATS01 Loyalty").outcome());
        assertEquals(token + ": pid 4321", closed.get("PCATS01 Loyalty").detail());
        assertEquals(List.of(List.of(4321L)), ops.killRequests);
    }

    @Test
    void nothingOfTheOtherBaseRunningClosesNothing() {
        FakeProcessOps ops = new FakeProcessOps();
        SimContext ctx = SimContext.of(tools, "");
        Map<String, Supervisor.StopRow> closed = supervisor(ops).stopWrongVariants(
                List.of(Registry.byName("PCATS01 Loyalty").orElseThrow()), Registry.otherVariants(false), ctx,
                Duration.ofSeconds(1));
        assertTrue(closed.isEmpty());
        assertTrue(ops.killRequests.isEmpty());
    }

    @Test
    void bothFormsOfOneRowAreStoppedTogether() {
        FakeProcessOps ops = new FakeProcessOps();
        SimContext ctx = SimContext.of(tools, "");
        List<SimEntry> forms = Registry.bothForms(List.of("PCATS01 Loyalty"));
        assertEquals(2, forms.size());
        ops.listingText = "P\t11\tjavaw.exe -jar " + ctx.matchToken(forms.get(0)) + "\n"
                + "P\t22\tjavaw.exe -jar " + ctx.matchToken(forms.get(1)) + "\n";
        Map<String, Supervisor.StopRow> rows = supervisor(ops).stopRunning(forms, ctx, Duration.ofSeconds(1));
        assertEquals(Set.of("PCATS01 Loyalty"), rows.keySet());
        assertEquals("pid 11, 22", rows.get("PCATS01 Loyalty").detail());
        assertEquals(List.of(List.of(11L, 22L)), ops.killRequests);
    }

    @Test
    void probeRunningSaysWhatIsUpBeforeAnythingStarts() {
        FakeProcessOps ops = new FakeProcessOps();
        SimContext ctx = SimContext.of(tools, "");
        SimEntry loysim = Registry.byName("PCATS01 Loyalty").orElseThrow();
        SimEntry buypass = Registry.byName("BUYPASS Host").orElseThrow();
        ops.commandLines = "javaw.exe -jar " + ctx.matchToken(loysim);
        Map<String, SimRunning.Running> rows = SimRunning.probeRunning(List.of(loysim, buypass), ctx, ops,
                (hosts, port) -> port == 41107 ? "localhost" : "");
        assertEquals(new SimRunning.Running("already running", "its process is running"), rows.get("PCATS01 Loyalty"));
        assertEquals(new SimRunning.Running("already running", "listening"), rows.get("BUYPASS Host"));

        ops.commandLines = "";
        ops.commandError = "CIM is down";
        rows = SimRunning.probeRunning(List.of(loysim), ctx, ops, (hosts, port) -> "");
        assertEquals(new SimRunning.Running("unknown", "could not list running processes: CIM is down"),
                rows.get("PCATS01 Loyalty"));
        ops.commandError = "";
        rows = SimRunning.probeRunning(List.of(loysim), ctx, ops, (hosts, port) -> "");
        assertEquals(new SimRunning.Running("not running", ""), rows.get("PCATS01 Loyalty"));
    }
}
