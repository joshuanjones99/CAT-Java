package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
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

    /**
     * How long teardown waits for a fake simulator to exit. A child whose working directory is the
     * {@code @TempDir} keeps that folder from being deleted, so every child must be gone before
     * JUnit cleans up.
     */
    private static final Duration REAP_WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tools;

    /**
     * Destroys, and waits for, every fake simulator this JVM still has running, and their
     * descendants. Only children of the test JVM whose image is the test JDK's {@code java} are
     * touched: each one was started by a test here. A child still alive after the bound fails the
     * test rather than being left to race the folder cleanup.
     */
    @AfterEach
    void reapFakeSimulators() throws InterruptedException {
        Optional<Path> java = SimTestSupport.testJava();
        if (java.isEmpty()) {
            return;
        }
        String image = java.get().toString();
        List<ProcessHandle> leftovers = ProcessHandle.current().descendants()
                .filter(ProcessHandle::isAlive)
                .filter(handle -> handle.info().command().map(image::equalsIgnoreCase).orElse(false))
                .toList();
        for (ProcessHandle handle : leftovers) {
            handle.destroyForcibly();
        }
        for (ProcessHandle handle : leftovers) {
            try {
                handle.onExit().get(REAP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("fake simulator pid " + handle.pid() + " did not exit", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }
    }

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
    void aClickRowThatCannotBeClickedHereIsNeedsAttentionAndNothingIsSpawned() {
        SimEntry loysim = SimEntry.javaw("PCATS01 Loyalty").cwd("PCATS01")
                .argv("-jar", "{cwd}/LoySim9.1.jar").click("Start Host").clicked()
                .log("LoySim_Debug.log").build();
        Recorder rec = new Recorder();
        SimProcess proc = clicking(new FakeWindowOps(), new FakeAccessBridge(1, FakeAccessBridge.Node.panel()))
                .startAll(List.of(loysim), ctx(), rec, BUDGET, null).get(0);
        assertEquals(SimStatus.State.NEEDS_ATTENTION, proc.status().state());
        assertEquals("PCATS01 Loyalty needs attention: not started, because CAT cannot press \"Start Host\" in its "
                + "window: no javaw.exe under " + tools.resolve("Java17") + ".", proc.status().detail());
        assertFalse(proc.spawned(), "a row that cannot be clicked is never started half-way");
    }

    @Test
    void aClickRowIsSpawnedItsButtonsPressedItsWindowMinimisedAndItIsReady() throws IOException {
        assumeTrue(SimTestSupport.testJava().isPresent());
        stageAccessBridge();
        long hwnd = 0x7777;
        FakeWindowOps windows = new FakeWindowOps();
        windows.anyPid = List.of(new WindowOps.TopWindow(hwnd, false));
        FakeAccessBridge bridge = new FakeAccessBridge(hwnd, FakeAccessBridge.Node.panel(
                FakeAccessBridge.Node.button("Load Data"), FakeAccessBridge.Node.button("Load Servers")));
        Recorder rec = new Recorder();
        Supervisor sup = clicking(windows, bridge);

        List<SimProcess> procs = sup.startAll(List.of(clickRow("Load Data", "Load Servers")), ctx(), rec, BUDGET, null);
        try {
            SimProcess proc = procs.get(0);
            assertEquals(new SimStatus("Clicked Sim", SimStatus.State.READY, SimStatus.Owner.CAT, "",
                    "clicked Load Data, Load Servers"), proc.status());
            assertTrue(proc.spawned());
            assertEquals(List.of("Load Data", "Load Servers"), bridge.clicked);
            assertEquals(List.of(hwnd), windows.minimised);
            assertTrue(rec.statuses.stream().anyMatch(s -> s.detail().equals(
                    "pressing Load Data, then Load Servers through the Java Access Bridge")), rec.statuses.toString());
        } finally {
            assertEquals(List.of("Clicked Sim"), sup.stop(procs, Duration.ofSeconds(5)), "CAT's own is stopped");
        }
    }

    @Test
    void aClickThatFailsLeavesItsProcessRunningAndTheRowNeedsAttentionNeverReady() throws IOException {
        assumeTrue(SimTestSupport.testJava().isPresent());
        stageAccessBridge();
        long hwnd = 0x7778;
        FakeWindowOps windows = new FakeWindowOps();
        windows.anyPid = List.of(new WindowOps.TopWindow(hwnd, false));
        FakeAccessBridge bridge = new FakeAccessBridge(hwnd,
                FakeAccessBridge.Node.panel(FakeAccessBridge.Node.button("Stop Host")));
        Supervisor sup = clicking(windows, bridge);

        List<SimProcess> procs = sup.startAll(List.of(clickRow("Start Host")), ctx(), Events.NONE, BUDGET, null);
        try {
            SimProcess proc = procs.get(0);
            assertEquals(SimStatus.State.NEEDS_ATTENTION, proc.status().state());
            assertEquals("Clicked Sim needs attention: press \"Start Host\" in its window (clicked nothing; "
                    + "'Start Host' not found (available: Stop Host)).", proc.status().detail());
            assertTrue(proc.spawned() && proc.process().isAlive(), "left running for the operator, and owned");
            assertEquals(List.of(hwnd), windows.minimised);
        } finally {
            assertEquals(List.of("Clicked Sim"), sup.stop(procs, Duration.ofSeconds(5)));
        }
    }

    @Test
    void aClickerThatThrowsIsANeedsAttentionRowNotAnEndedPass() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        RecordingClicker clicker = new RecordingClicker();
        clicker.clickError = new IllegalStateException("boom");
        Supervisor sup = new Supervisor(new FakeProcessOps(), Duration.ofMillis(300), clicker);

        List<SimProcess> procs = sup.startAll(List.of(clickRow("Start Host")), ctx(), Events.NONE, BUDGET, null);
        try {
            assertEquals("Clicked Sim needs attention: press \"Start Host\" in its window (clicking raised "
                    + "IllegalStateException: boom).", procs.get(0).status().detail());
        } finally {
            sup.stop(procs, Duration.ofSeconds(5));
        }
    }

    @Test
    void aWindowedRowHasItsWindowsMinimisedPastItsStartBudgetAndIsNotClicked() {
        assumeTrue(SimTestSupport.testJava().isPresent());
        String token = token();
        SimEntry entry = SimEntry.exeRow("Windowed").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(0, token, "idle").toArray(String[]::new))
                .match(token).windowed().build();
        RecordingClicker clicker = new RecordingClicker();
        Supervisor sup = new Supervisor(new FakeProcessOps(), Duration.ofMillis(300), clicker);

        long before = System.nanoTime();
        List<SimProcess> procs = sup.startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null);
        try {
            assertEquals(SimStatus.State.STARTED, procs.get(0).status().state());
            assertEquals(List.of(procs.get(0).process()), clicker.minimised);
            assertTrue(clicker.minimiseDeadline - before >= BUDGET.plus(AccessBridgeClicker.WINDOW_BUDGET).toNanos(),
                    "watched for the window budget past the start budget");
            assertEquals(0, clicker.clicks);
        } finally {
            sup.stop(procs, Duration.ofSeconds(5));
        }
    }

    /** A {@link Clicker} that records, and can be made to throw. */
    private static final class RecordingClicker implements Clicker {
        RuntimeException clickError;
        final List<Process> minimised = new CopyOnWriteArrayList<>();
        volatile long minimiseDeadline;
        volatile int clicks;

        @Override
        public Optional<String> unavailable(SimContext ctx) {
            return Optional.empty();
        }

        @Override
        public Clicked click(SimEntry entry, Process process, SimContext ctx, long deadline) {
            clicks++;
            if (clickError != null) {
                throw clickError;
            }
            return new Clicked(true, "clicked");
        }

        @Override
        public void minimiseWhenShown(Process process, long deadline) {
            minimised.add(process);
            minimiseDeadline = deadline;
        }
    }

    /** A supervisor whose clicker runs over fake windows and a fake bridge, reading a staged properties file. */
    private Supervisor clicking(FakeWindowOps windows, FakeAccessBridge bridge) {
        AccessBridgeClicker clicker = new AccessBridgeClicker(windows, dll -> bridge,
                tools.resolve(".accessibility.properties"), true, Duration.ofMillis(10),
                d -> Park.park(Duration.ofMillis(1)));
        return new Supervisor(new FakeProcessOps(), Duration.ofMillis(300), clicker);
    }

    /** A JDK with the bridge DLL, and the switch on, all under the temp tools root. */
    private void stageAccessBridge() throws IOException {
        Path bin = Files.createDirectories(tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin"));
        Files.createFile(bin.resolve("javaw.exe"));
        Files.createFile(bin.resolve("windowsaccessbridge-64.dll"));
        Files.writeString(tools.resolve(".accessibility.properties"),
                "assistive_technologies=com.sun.java.accessibility.AccessBridge\n");
    }

    /** The test JVM running FakeSimulator silently, as a row ready once {@code buttons} are pressed. */
    private static SimEntry clickRow(String... buttons) {
        String token = token();
        return SimEntry.exeRow("Clicked Sim").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(0, token, "silent").toArray(String[]::new))
                .match(token).click(buttons).clicked().build();
    }

    @Test
    void aMissingRuntimeIsAFailedRowNotAnException() {
        SimEntry entry = SimEntry.javaw("No Java").argv("-jar", "a.jar").build();
        SimProcess proc = supervisor(new FakeProcessOps())
                .startAll(List.of(entry), ctx(), Events.NONE, BUDGET, null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertTrue(proc.status().detail().contains("could not launch"), proc.status().detail());
    }

    /** A jar holding a {@code PktTemplates.class} in the real key file's shape (CapkTest's data). */
    private static byte[] capkSourceJar() {
        String klass = "Êþº¾<FBData>CA_KEYS,011712312024,01,01,A000000025,C9,B362,03,8E8D"
                + "</FBData>\u0000CA_KEYS,011712312024,01,01,A000000025,C9,B362,03,8E8D"
                + "12312020,01,01,A000000277,02,E0FF,010001,BAF3\u0000\t<TTCutTime>1330</TTCutTime>\r";
        return Zips.zip("META-INF/MANIFEST.MF", "Main-Class: ArbitraryHostSim\n",
                Capk.CLASS, klass.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                "Other.class", "untouched");
    }

    /** The Python's {@code _python_row}: a real child (FakeSimulator) whose argv names a {@code -jar}. */
    private static SimEntry capkRow(int port, String token, String capkFrom) {
        List<String> argv = new java.util.ArrayList<>(SimTestSupport.fakeArgs(port, token, "ready"));
        argv.add("-jar");
        argv.add("cat.jar");
        return SimEntry.exeRow("Child").exe(testJava().toString()).argv(argv.toArray(String[]::new))
                .match(token).tcp(port).capkFrom(capkFrom).build();
    }

    @Test
    void theCapkJarIsBuiltBeforeTheChildStarts() throws IOException {
        Files.write(tools.resolve("src.jar"), capkSourceJar());
        int port = SimTestSupport.freePort();
        Supervisor sup = supervisor(new FakeProcessOps());
        List<SimProcess> procs = sup.startAll(List.of(capkRow(port, token(), "src.jar")), ctx(),
                Events.NONE, BUDGET, null);
        try {
            assertEquals(SimStatus.State.READY, procs.get(0).status().state(), procs.get(0).status().detail());
            assertTrue(procs.get(0).spawned());
            assertArrayEquals(Capk.patched(tools.resolve("src.jar")), Files.readAllBytes(tools.resolve("cat.jar")));
        } finally {
            sup.stop(procs, Duration.ofSeconds(5));
        }
    }

    @Test
    void aMissingCapkSourceIsAFailedRowAndNothingStarts() {
        SimProcess proc = supervisor(new FakeProcessOps()).startAll(
                List.of(capkRow(SimTestSupport.freePort(), token(), "absent.jar")), ctx(),
                Events.NONE, BUDGET, null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertEquals(SimStatus.Owner.CAT, proc.status().owner());
        assertTrue(proc.status().detail().startsWith("cannot build its jar: "), proc.status().detail());
        assertTrue(proc.status().detail().contains("absent.jar"), proc.status().detail());
        assertFalse(proc.spawned());
        assertFalse(Files.exists(tools.resolve("cat.jar")));
    }

    @Test
    void aCapkRowWithoutDashJarIsAFailedRow() throws IOException {
        Files.write(tools.resolve("src.jar"), capkSourceJar());
        SimEntry row = SimEntry.exeRow("Child").exe(testJava().toString())
                .argv(SimTestSupport.fakeArgs(SimTestSupport.freePort(), token(), "ready").toArray(String[]::new))
                .capkFrom("src.jar").build();
        SimProcess proc = supervisor(new FakeProcessOps())
                .startAll(List.of(row), ctx(), Events.NONE, BUDGET, null).get(0);
        assertEquals(SimStatus.State.FAILED, proc.status().state());
        assertEquals("cannot build its jar: capk_from: needs a -jar in argv", proc.status().detail());
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
        assertEquals("could not list running processes: Access is denied; a simulator that is already up cannot be "
                + "recognised and may be started a second time. Fix the process listing, then start again.",
                probe.detail());
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
            try {
                // Destroy is asynchronous: wait, bounded, so the folder is free before cleanup.
                child.waitFor(REAP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
