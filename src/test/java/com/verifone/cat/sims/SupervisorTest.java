package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The supervisor against fake processes only: the test JVM running {@link FakeSimulator}, which
 * this test launches, and a {@link FakeProcessOps} that kills nothing. No PATS simulator is started,
 * and no PID the test did not spawn is ever passed to a kill.
 */
class SupervisorTest {

    private static final Duration BUDGET = Duration.ofSeconds(30);

    @TempDir
    Path tools;

    /** Records what the supervisor reports. */
    private static final class Recorder implements Events {
        final List<SimStatus> statuses = new CopyOnWriteArrayList<>();
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch newLine = new CountDownLatch(1);

        @Override
        public void status(SimStatus status) {
            statuses.add(status);
        }

        @Override
        public void log(String name, String line) {
            lines.add(line);
            if (line.equals("new line")) {
                newLine.countDown();
            }
        }

        SimStatus last(String name) {
            return statuses.stream().filter(s -> s.name().equals(name)).reduce((a, b) -> b).orElseThrow();
        }
    }

    private SimContext ctx() {
        return SimContext.of(tools, "");
    }

    private static Supervisor supervisor(FakeProcessOps ops) {
        return new Supervisor(ops, Duration.ofMillis(300));
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String token() {
        return "cat-sims-test-" + UUID.randomUUID();
    }

    private static Path testJava() {
        return SimTestSupport.testJava().orElseThrow();
    }

    @Test
    void adoptsAListeningSimulatorAndNeverStopsIt() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            SimEntry entry = SimEntry.exeRow("Adopted").exe(tools.resolve("missing.exe").toString())
                    .tcp(port).build();
            Recorder rec = new Recorder();
            Supervisor sup = supervisor(new FakeProcessOps());
            List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), rec, BUDGET, null);

            SimStatus status = procs.get(0).status();
            assertEquals(SimStatus.State.ADOPTED, status.state());
            assertEquals(SimStatus.Owner.EXTERNAL, status.owner());
            assertFalse(procs.get(0).spawned());
            assertTrue(sup.stop(procs, Duration.ofSeconds(1)).isEmpty(), "D9: nothing adopted is stopped");
            assertTrue(accepts(port), "the adopted listener is still serving");
        }
    }

    @Test
    void adoptOnlyMatchRefusesAnotherBuildOnTheListenersPort() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            FakeProcessOps ops = new FakeProcessOps();
            ops.listenerLine = "java -jar C:/other/SomethingElse.jar";
            SimEntry entry = SimEntry.exeRow("Petro").exe(tools.resolve("missing.exe").toString())
                    .match("Petro_Simulator_Suite.jar").tcp(server.getLocalPort()).adoptOnlyMatch().build();
            SimProcess proc = supervisor(ops).startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null).get(0);
            assertEquals(SimStatus.State.FAILED, proc.status().state());
            assertTrue(proc.status().detail().contains("held by another build"), proc.status().detail());
        }
    }

    @Test
    void adoptOnlyMatchAdoptsTheMatchingBuild() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            FakeProcessOps ops = new FakeProcessOps();
            ops.listenerLine = "java -jar C:/x/Petro_Simulator_Suite.jar";
            SimEntry entry = SimEntry.exeRow("Petro").exe(tools.resolve("missing.exe").toString())
                    .match("Petro_Simulator_Suite.jar").tcp(server.getLocalPort()).adoptOnlyMatch().build();
            SimProcess proc = supervisor(ops).startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null).get(0);
            assertEquals(SimStatus.State.ADOPTED, proc.status().state());
        }
    }

    @Test
    void aRunningButNotListeningProcessIsFailedNotAdoptedNotKilledNotSpawnedOver() {
        int port = SimTestSupport.freePort();
        String token = token();
        FakeProcessOps ops = new FakeProcessOps();
        ops.commandLines = "java -cp x " + token;
        SimEntry entry = SimEntry.exeRow("Stuck").exe(tools.resolve("missing.exe").toString())
                .match(token).tcp(port).build();
        SimProcess proc = supervisor(ops).startAll(List.of(entry), ctx(), Events.NONE,
                Duration.ofSeconds(1), null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertEquals(SimStatus.Owner.EXTERNAL, proc.status().owner());
        assertTrue(proc.status().detail().contains("process present but not listening"), proc.status().detail());
        assertTrue(ops.killRequests.isEmpty(), "nothing is killed without replace_stale");
        assertFalse(proc.spawned(), "and nothing is spawned over it");
    }

    @Test
    void replaceStaleKillsOnlyTheStaleProcessAndStartsItsOwn() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        int port = SimTestSupport.freePort();
        String token = token();
        FakeProcessOps ops = new FakeProcessOps();
        ops.commandLines = "java -cp x " + token;
        ops.listingText = "P\t4242\tjava -cp x " + token + "\n";
        SimEntry entry = SimEntry.exeRow("Stale Sim").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(port, token, "ready").toArray(String[]::new))
                .match(token).tcp(port).replaceStale().build();
        Recorder rec = new Recorder();
        Supervisor sup = supervisor(ops);
        List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), rec, BUDGET, null);
        try {
            assertEquals(List.of(List.of(4242L)), ops.killRequests, "only the listed stale PID was asked to die");
            assertEquals(SimStatus.State.READY, procs.get(0).status().state());
            assertEquals(SimStatus.Owner.CAT, procs.get(0).status().owner());
            assertTrue(rec.statuses.stream().anyMatch(s -> s.detail().contains("stale:")));
        } finally {
            sup.stop(procs, Duration.ofSeconds(5));
        }
    }

    @Test
    void spawnsAReadySimulatorAndStopsItsOwnProcess() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        int port = SimTestSupport.freePort();
        String token = token();
        SimEntry entry = SimEntry.exeRow("Ready Sim").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(port, token, "ready").toArray(String[]::new))
                .match(token).tcp(port).build();
        Supervisor sup = supervisor(new FakeProcessOps());
        List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null);
        SimProcess proc = procs.get(0);
        try {
            assertEquals(SimStatus.State.READY, proc.status().state());
            assertEquals(SimStatus.Owner.CAT, proc.status().owner());
            assertEquals("localhost:" + port, proc.status().bind());
            assertTrue(proc.spawned() && proc.process().isAlive());
        } finally {
            assertEquals(List.of("Ready Sim"), sup.stop(procs, Duration.ofSeconds(5)));
        }
        assertFalse(proc.process().isAlive(), "stop ended the child CAT spawned");
    }

    @Test
    void aSilentSimulatorFailsOnItsPortBudgetAndStopEndsIt() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        int port = SimTestSupport.freePort();
        String token = token();
        SimEntry entry = SimEntry.exeRow("Silent Sim").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(port, token, "silent").toArray(String[]::new))
                .match(token).tcp(port).build();
        Supervisor sup = supervisor(new FakeProcessOps());
        List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), Events.NONE, Duration.ofSeconds(2), null);
        SimProcess proc = procs.get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertTrue(proc.status().detail().contains("no listener on port " + port), proc.status().detail());
        assertTrue(proc.process().isAlive(), "a failed row's child is the caller's to stop");
        sup.stop(procs, Duration.ofSeconds(5));
        assertFalse(proc.process().isAlive());
    }

    @Test
    void anExitedSimulatorFailsWithItsExitCode() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        int port = SimTestSupport.freePort();
        String token = token();
        SimEntry entry = SimEntry.exeRow("Exits").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(port, token, "exit").toArray(String[]::new))
                .match(token).tcp(port).build();
        SimProcess proc = supervisor(new FakeProcessOps())
                .startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertTrue(proc.status().detail().contains("exited with 3"), proc.status().detail());
    }

    @Test
    void theFallbackRelaunchesOnTheTripLineThenServes() {
        assumeTrue(SimTestSupport.testJdkHome()
                .map(home -> Files.isRegularFile(home.resolve("bin").resolve("javaw.exe")))
                .orElse(false));
        Path jdkHome = SimTestSupport.testJdkHome().orElseThrow();
        int port = SimTestSupport.freePort();
        String token = token();
        SimEntry entry = SimEntry.javaw("Trip Sim")
                .argv(SimTestSupport.fakeArgs(port, token, "trip-once").toArray(String[]::new))
                .match(token).tcp(port)
                .fallback(jdkHome.toString(), "", "javax/xml/bind/JAXBException").build();
        Recorder rec = new Recorder();
        Supervisor sup = supervisor(new FakeProcessOps());
        List<SimProcess> procs = sup.startAll(List.of(entry), SimContext.of(tools, "").withJdk(jdkHome, null),
                rec, BUDGET, null);
        try {
            assertEquals(SimStatus.State.READY, procs.get(0).status().state(), procs.get(0).status().detail());
            assertTrue(rec.statuses.stream().anyMatch(s -> s.state() == SimStatus.State.STARTING
                    && s.detail().contains("retrying")), "the relaunch was announced");
        } finally {
            sup.stop(procs, Duration.ofSeconds(5));
        }
    }

    @Test
    void aSimulatorWithNoReadyProbeIsStartedWithReadinessUnknown() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        String token = token();
        SimEntry entry = SimEntry.exeRow("No Probe").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(0, token, "idle").toArray(String[]::new))
                .match(token).build();
        Supervisor sup = supervisor(new FakeProcessOps());
        List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null);
        try {
            assertEquals(SimStatus.State.STARTED, procs.get(0).status().state());
            assertTrue(procs.get(0).status().detail().contains("readiness unknown"));
        } finally {
            sup.stop(procs, Duration.ofSeconds(5));
        }
    }

    @Test
    void aClickRowIsNeedsAttentionAndNothingIsSpawned() {
        SimEntry loysim = SimEntry.javaw("PCATS01 Loyalty").cwd("PCATS01")
                .argv("-jar", "{cwd}/LoySim9.1.jar").click("Start Host").clicked()
                .log("LoySim_Debug.log").build();
        Recorder rec = new Recorder();
        SimProcess proc = supervisor(new FakeProcessOps())
                .startAll(List.of(loysim), ctx(), rec, BUDGET, null).get(0);
        assertEquals(SimStatus.State.NEEDS_ATTENTION, proc.status().state());
        assertTrue(proc.status().detail().contains("Access Bridge not ported"), proc.status().detail());
        assertTrue(proc.status().detail().contains("Start Host"), proc.status().detail());
        assertFalse(proc.spawned(), "a click row is never started half-way");
    }

    @Test
    void aMissingRuntimeIsAFailedRowNotAnException() {
        SimEntry entry = SimEntry.javaw("No Java").argv("-jar", "a.jar").build();
        SimProcess proc = supervisor(new FakeProcessOps())
                .startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertTrue(proc.status().detail().contains("could not launch"), proc.status().detail());
    }

    @Test
    void aCapkRowIsRefusedRatherThanLaunchedWithItsExpiredKeys() {
        SimEntry buypass = SimEntry.javaw("BUYPASS Host").cwd("BUYPASS")
                .argv("-jar", "ArbitraryHostSim_v5.00-cat.jar").capkFrom("ArbitraryHostSim_v5.00.jar")
                .tcp(SimTestSupport.freePort()).build();
        SimProcess proc = supervisor(new FakeProcessOps())
                .startAll(List.of(buypass), ctx(), Events.NONE, BUDGET, null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertTrue(proc.status().detail().contains("CAPK re-dating is not ported"), proc.status().detail());
        assertFalse(proc.spawned());
    }

    @Test
    void aProbeErrorIsAVisibleRowNotAnEmptyAnswer() {
        FakeProcessOps ops = new FakeProcessOps();
        ops.commandError = "Access is denied";
        SimEntry loysim = SimEntry.javaw("PCATS01 Loyalty").cwd("PCATS01")
                .argv("-jar", "{cwd}/LoySim9.1.jar").click("Start Host").clicked().build();
        Recorder rec = new Recorder();
        supervisor(ops).startAll(List.of(loysim), ctx(), rec, BUDGET, null);
        SimStatus probe = rec.last(Supervisor.PROBE_ROW);
        assertEquals(SimStatus.State.NEEDS_ATTENTION, probe.state());
        assertTrue(probe.detail().contains("could not list running processes: Access is denied"), probe.detail());
    }

    @Test
    void theLogFileIsFollowedFromItsEndNotReplayed() throws IOException, InterruptedException {
        Path log = tools.resolve("sim.log");
        Files.writeString(log, "old line\n");
        try (ServerSocket server = new ServerSocket(0)) {
            SimEntry entry = SimEntry.exeRow("Logged").exe(tools.resolve("missing.exe").toString())
                    .tcp(server.getLocalPort()).log("sim.log").build();
            Recorder rec = new Recorder();
            Supervisor sup = supervisor(new FakeProcessOps());
            List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), rec, BUDGET, null);
            try {
                // Append, as a log grows. A rewrite truncates first, and the tail treats a shrink
                // as a rotation (as the Python does) and reads the file again from its start.
                Files.writeString(log, "new line\n", StandardOpenOption.APPEND);
                assertTrue(rec.newLine.await(10, TimeUnit.SECONDS), "the appended line arrived");
                assertFalse(rec.lines.contains("old line"), "lines before the start are not replayed");
            } finally {
                sup.stop(procs, Duration.ofSeconds(1));
            }
        }
    }

    @Test
    void stopRunningKillsTheListenerAndTheMatchingProcessAndNothingElse() {
        FakeProcessOps ops = new FakeProcessOps();
        ops.listingText = "L\t9001\t555\nP\t600\tjava -jar Other.jar\nP\t601\tjava -jar Petro_Simulator_Suite.jar\n";
        SimEntry petro = SimEntry.exeRow("Petro").exe(tools.resolve("java.exe").toString())
                .match("Petro_Simulator_Suite.jar").tcp(9001).build();
        Map<String, Supervisor.StopRow> rows = supervisor(ops).stopRunning(List.of(petro), ctx(), BUDGET);
        assertEquals(List.of(List.of(555L, 601L)), ops.killRequests);
        assertEquals(Supervisor.StopOutcome.STOPPED, rows.get("Petro").outcome());
        assertEquals("pid 555, 601", rows.get("Petro").detail());
    }

    @Test
    void stopRunningWithNothingMatchingKillsNothing() {
        FakeProcessOps ops = new FakeProcessOps();
        ops.listingText = "P\t600\tjava -jar Other.jar\n";
        SimEntry petro = SimEntry.exeRow("Petro").exe(tools.resolve("java.exe").toString())
                .match("Petro_Simulator_Suite.jar").build();
        Map<String, Supervisor.StopRow> rows = supervisor(ops).stopRunning(List.of(petro), ctx(), BUDGET);
        assertEquals(Supervisor.StopOutcome.ABSENT, rows.get("Petro").outcome());
        assertTrue(ops.killRequests.isEmpty());
    }

    @Test
    void stopRunningOnAListingErrorKillsNothing() {
        FakeProcessOps ops = new FakeProcessOps();
        ops.listingError = "Access is denied";
        SimEntry petro = SimEntry.exeRow("Petro").exe(tools.resolve("java.exe").toString())
                .match("Petro_Simulator_Suite.jar").build();
        Map<String, Supervisor.StopRow> rows = supervisor(ops).stopRunning(List.of(petro), ctx(), BUDGET);
        assertEquals(Supervisor.StopOutcome.FAILED, rows.get("Petro").outcome());
        assertTrue(ops.killRequests.isEmpty());
    }

    /**
     * The real PowerShell query and the real taskkill, against one fake child this test starts
     * with a token of its own making. Nothing else on the machine carries that token.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void stopRunningReallyKillsOnlyTheInstanceItIsGivenByItsToken() throws IOException {
        assumeTrue(SimTestSupport.testJava().isPresent());
        String token = token();
        List<String> argv = new java.util.ArrayList<>();
        argv.add(testJava().toString());
        argv.addAll(SimTestSupport.fakeArgs(0, token, "idle"));
        Process child = new ProcessBuilder(argv).directory(tools.toFile()).redirectErrorStream(true).start();
        try {
            SimEntry row = SimEntry.exeRow("Fake Child").exe(testJava().toString()).match(token).build();
            Map<String, Supervisor.StopRow> rows = new Supervisor(new PowerShellProcessOps(), Duration.ofSeconds(1))
                    .stopRunning(List.of(row), ctx(), Duration.ofSeconds(30));
            assertEquals(Supervisor.StopOutcome.STOPPED, rows.get("Fake Child").outcome(),
                    rows.get("Fake Child").detail());
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "the child that carried the token is gone");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            child.destroyForcibly();
        }
    }
}
