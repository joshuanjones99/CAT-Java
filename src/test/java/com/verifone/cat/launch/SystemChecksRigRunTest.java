package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.ArtifactoryAuthError;
import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.FakeArtifactory;
import com.verifone.cat.connections.Readiness;
import com.verifone.cat.devices.printer.SerialPorts;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.Supervisor;
import com.verifone.cat.sims.Zips;
import com.verifone.cat.sims.base.SuiteBase;

/**
 * Step 1 on a rig run, with every outside question a fake ({@link SystemCheckStages}): the rig's network lines, the
 * serial listing, the Commander's {@code suiteinfo.dat}, Artifactory ({@code FakeArtifactory}), the process listing
 * and the stop. The Python CAT's {@code TestCommanderBaseAtSystemChecks} and {@code TestSimulatorBuildAtSystemChecks}
 * ({@code tests/unit/test_cat_launch.py}). Evidence about this code's wiring, not about a rig.
 */
class SystemChecksRigRunTest {

    private static final String RIG = "printer";
    private static final String MASTER = "RMS_Nightly_CLW/Petro/master";

    @TempDir
    Path dir;

    private LaunchPaths paths;
    private Path tools;
    private RunContext ctx;
    private FakeStages stages;
    private FakeArtifactory server;

    /** The stages a rig run asks, answered from the test. */
    private final class FakeStages implements SystemCheckStages {
        List<Readiness> network = List.of(new Readiness(true, "controller rig adapter", "held", true),
                new Readiness(true, "commander reachable", "connected on port 80", true));
        List<String> listed = List.of("COM3");
        Map<String, String> suiteinfo = Map.of("Base_Level", "057.02.00");
        boolean login = true;
        Map<String, String> running = Map.of();
        final List<String> suiteinfoReads = new CopyOnWriteArrayList<>();
        final List<String> stopped = new CopyOnWriteArrayList<>();
        final List<String> opened = new CopyOnWriteArrayList<>();
        final List<String> closed = new CopyOnWriteArrayList<>();
        PrinterHold.Opener opener = port -> {
            opened.add(port);
            return () -> closed.add(port);
        };

        @Override
        public PrinterHold.Opener printerOpener() {
            return opener;
        }

        @Override
        public List<Readiness> rigNetwork(Rig rig) {
            return network;
        }

        @Override
        public SerialPorts serialPorts() {
            return new SerialPorts() {
                @Override
                public List<String> listed() {
                    return listed;
                }

                @Override
                public Optional<String> openProblem(String port) {
                    return Optional.empty();
                }
            };
        }

        @Override
        public Map<String, String> suiteinfo(Rig rig) {
            suiteinfoReads.add(rig.name());
            if (suiteinfo == null) {
                throw new IllegalStateException("no route to the Commander");
            }
            return suiteinfo;
        }

        @Override
        public ArtifactoryClient artifactory() {
            if (!login) {
                throw new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD");
            }
            return server.client();
        }

        @Override
        public Map<String, String> running(List<SimEntry> entries, SimContext sims) {
            Map<String, String> found = new LinkedHashMap<>();
            for (SimEntry entry : entries) {
                if (running.containsKey(entry.name())) {
                    found.put(entry.name(), running.get(entry.name()));
                }
            }
            return found;
        }

        @Override
        public Map<String, Supervisor.StopRow> stopRunning(RunContext run, List<SimEntry> entries, SimContext sims) {
            Map<String, Supervisor.StopRow> rows = new LinkedHashMap<>();
            for (SimEntry entry : entries) {
                stopped.add(entry.name() + " " + entry.argv());
                rows.put(entry.name(), new Supervisor.StopRow(Supervisor.StopOutcome.ABSENT, ""));
            }
            return rows;
        }
    }

    private static Map<String, Object> d(Object... keysAndValues) {
        return FakeArtifactory.dir("", keysAndValues);
    }

    @BeforeEach
    void setUp() throws IOException {
        paths = LaunchFixtures.paths(dir);
        tools = dir.resolve("tools");
        Files.writeString(paths.rigsDir().resolve(RIG + ".yaml"), """
                name: printer
                commander:
                  host: commander.invalid
                  ssh_user: root
                  eps_port: 41107
                registers:
                  - id: topaz101
                    host: register.invalid
                    printer_port: COM3
                controller:
                  host: 127.0.0.1
                pinpad: viperpay
                build:
                  expected_version: "057.02"
                """);
        // Both Java 17 sets staged and genuine: nothing to download for Java.
        Zips.stageJava(tools, "Java17", "jakarta");
        Zips.stageJava(tools, "Java17v1.1", "javax");
        Path v11 = Files.createDirectories(tools.resolve("Java17v1.1/jdk-17.0.5+8/bin"));
        Files.write(v11.resolve("javaw.exe"), new byte[0]);
        Files.write(v11.resolve("java.exe"), new byte[0]);
        stages = new FakeStages();
        byte[] v7 = Zips.zip("LoySim_V7/PCATS01/LoySim7.jar", "7", "LoySim_V7/PCATS01/LoyaltySim.prop", "k=v");
        byte[] v91 = Zips.zip("LoySim_V9.1/PCATS01/LoySim9.1.jar", "9.1", "LoySim_V9.1/PCATS01/LoyaltySim.prop", "k=v");
        server = new FakeArtifactory(d("RMS_Nightly_CLW", d("Petro", d(
                "master055.02", d("41", FakeArtifactory.dir("2026-09-28T18:23:48.785Z",
                        "simulators", d("LoySim_V7.zip", v7))),
                "master", d("60", FakeArtifactory.dir("2026-10-07T14:18:00.000Z",
                        "simulators", d("LoySim_V9.1.zip", v91),
                        "automation", d("terminalConnector-V4.zip", Zips.zip("app/main.py", "print()"))))))));
    }

    @AfterEach
    void tearDown() {
        if (ctx != null && ctx.runLock() != null) {
            ctx.runLock().release();
        }
        server.close();
    }

    private record Outcome(boolean keepGoing, StepEmitter step, String log) {
    }

    private Outcome run(LaunchConfig.Builder config) throws IOException {
        Path folder = dir.resolve("run");
        ctx = new RunContext(config.build(), paths, "dddddddddddd", folder, RunEvents.open(folder, Clock.systemUTC()),
                new PauseGate(), Clock.systemUTC());
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SYSTEM_CHECKS);
        boolean keepGoing = new SystemChecksStep(stages).run(ctx, step);
        ctx.closeEvents();
        // Every log line of the step, in order (run.log keeps only the rows and the errors).
        StringBuilder log = new StringBuilder();
        for (JsonNode event : LaunchFixtures.events(folder)) {
            if (event.has("message")) {
                log.append(event.get("message").asText()).append('\n');
            }
        }
        return new Outcome(keepGoing, step, log.toString());
    }

    private static LaunchConfig.Builder loysim() {
        return new LaunchConfig.Builder(RIG).simulator("PCATS01 Loyalty");
    }

    /** PCATS01 staged as master's LoySim 9.1, as CAT installed it. */
    private Path stageLoySim91() throws IOException {
        Path loysim = Files.createDirectories(tools.resolve("PCATS01"));
        Files.writeString(loysim.resolve("LoySim9.1.jar"), "9.1");
        SuiteBase.writeMarker(loysim, MASTER, "60");
        return loysim;
    }

    @Test
    void aRigWithAPrinterPassesStepOneInsteadOfEndingUnavailable() throws IOException {
        stageLoySim91();

        Outcome out = run(loysim());

        assertTrue(out.keepGoing(), out.step().detail() + "\n" + out.log());
        assertEquals(StepResult.COMPLETE, out.step().state(), out.step().detail());
        assertFalse(out.log().contains("unavailable"), out.log());
        assertTrue(out.log().contains("PASS  printer port for topaz101 — COM3 is listed and opens"), out.log());
        assertTrue(out.log().contains("PASS  Commander base — 057.02.00: the Commander is on Base_Level 057.02.00"),
                out.log());
        assertTrue(out.log().contains("PASS  PCATS01 Loyalty base — CAT installed master build 60"), out.log());
        assertTrue(out.log().contains("PASS  Java 17 v1.2 (Java17) — staged; "), out.log());
        assertEquals("057.02.00", ctx.simsBase());
        assertEquals(List.of(), server.requests); // nothing to fetch, so no client was made
    }

    @Test
    void aPrinterPortThatIsNotListedFailsTheRunAndNothingIsReadOrFetched() throws IOException {
        stages.listed = List.of();

        Outcome out = run(loysim());

        assertFalse(out.keepGoing());
        assertEquals(StepResult.FAILED, out.step().state());
        assertTrue(out.step().detail().startsWith("rig hardware not ready: printer port for topaz101 — COM3 is not "
                + "listed"), out.step().detail());
        assertEquals(List.of(), stages.suiteinfoReads);
        assertEquals(List.of(), stages.opened, "a printer line that failed holds nothing");
    }

    @Test
    void aRigWhosePrinterLinesPassHoldsThePrinterPortFromStepOne() throws IOException {
        stageLoySim91();

        Outcome out = run(loysim());

        assertTrue(out.keepGoing(), out.step().detail());
        assertEquals(List.of("COM3"), stages.opened);
        assertTrue(out.log().contains("holding COM3 as the register's printer until the tests start"), out.log());
        assertTrue(ctx.heldPrinter() != null);
        assertEquals(List.of("ready COM3 CAT holds it for the register until the tests start"), printerRows());
    }

    @Test
    void aPrinterPortThatWillNotOpenIsReportedAsNotHeldAndTheStepGoesOn() throws IOException {
        stageLoySim91();
        stages.opener = port -> {
            throw new com.verifone.cat.devices.DeviceUnreachable(port, "Access is denied (PATS holds it?)");
        };

        Outcome out = run(loysim());

        assertTrue(out.keepGoing(), out.step().detail());
        assertTrue(out.log().contains("printer port COM3 not held during setup ("), out.log());
        assertEquals(null, ctx.heldPrinter());
        List<String> rows = printerRows();
        assertEquals(1, rows.size(), rows.toString());
        assertTrue(rows.get(0).startsWith("failed COM3 "), rows.toString());
    }

    @Test
    void coverageOrAnOnboardingOnARigFailStepOneBeforeAnythingIsStarted() throws IOException {
        stageLoySim91();

        Outcome covered = run(loysim().coverage(true));
        assertFalse(covered.keepGoing());
        assertTrue(covered.log().contains("FAIL  JaCoCo coverage — unavailable in CAT-Java: needs JaCoCo coverage's "
                + "launch wiring"), covered.log());
        ctx.runLock().release();

        // The cloud tunnel's start is ported (launch.TunnelStart, wave 16): an onboarding run is no longer stopped here.
        Outcome onboarding = run(loysim().commanderConnect(true));
        assertFalse(onboarding.log().contains("FAIL  cloud-tunnel — unavailable"), onboarding.log());
    }

    /** The Receipt Printer's {@code simulator.state} rows of the last run: state, bind, detail. */
    private List<String> printerRows() throws IOException {
        List<String> rows = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(dir.resolve("run"))) {
            if (event.get("type").asText().equals("simulator.state")
                    && event.get("name").asText().equals("Receipt Printer")) {
                rows.add(event.get("state").asText() + " " + event.get("bind").asText() + " "
                        + event.get("detail").asText());
            }
        }
        return rows;
    }

    @Test
    void theBuildARunInstallsIsTheBaseWithoutAskingTheCommander() throws IOException {
        Outcome out = run(new LaunchConfig.Builder(RIG).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch("master055.02").commanderBuild("41"));

        assertEquals("master055.02", ctx.simsBase());
        assertEquals(List.of(), stages.suiteinfoReads);
        assertTrue(out.log().contains("PASS  Commander base — master055.02: the run installs master055.02"), out.log());
    }

    @Test
    void aSolutionsInstallReadsTheCommandersBaseLevel() throws IOException {
        run(new LaunchConfig.Builder(RIG).upgradeMode(UpgradeMode.NEW_INSTALL).commanderBranch("Solutions")
                .commanderBuild("134"));

        assertEquals(List.of(RIG), stages.suiteinfoReads);
        assertEquals("057.02.00", ctx.simsBase());
    }

    @Test
    void anUnreadableBaseIsAWarningAndNothingIsFetched() throws IOException {
        stages.suiteinfo = null;

        Outcome out = run(loysim());

        assertTrue(out.log().contains("WARN  Commander base — the Commander's base could not be read "
                + "(IllegalStateException: no route to the Commander); base 57's simulators"), out.log());
        assertTrue(out.log().contains("WARN  simulators for the Commander's base"), out.log());
        assertEquals("", ctx.simsBase());
        assertEquals(List.of(), server.requests);
    }

    @Test
    void aLoySimWithoutIts5502BuildIsStoppedUnderBothFormsAndReplaced() throws IOException {
        // 2026-10-10: LoySim 9.1 staged on a 55.02 Commander answered loyalty interface 1.3; 55.02 ships LoySim V7.
        Path loysim = stageLoySim91();
        stages.suiteinfo = Map.of("Base_Level", "055.02.09");

        Outcome out = run(loysim());

        assertTrue(out.keepGoing(), out.step().detail() + "\n" + out.log());
        assertTrue(out.log().contains("FETCH PCATS01 Loyalty — its build for 055.02.09 (LoySim7.jar) is not staged"),
                out.log());
        assertEquals(2, stages.stopped.size(), stages.stopped.toString()); // both registry forms
        assertTrue(stages.stopped.get(0).contains("LoySim7.jar") && stages.stopped.get(1).contains("LoySim9.1.jar"),
                stages.stopped.toString());
        assertTrue(out.log().contains("PASS  Java 17 v1.1 (Java17v1.1)"), out.log());
        assertTrue(out.log().contains("PASS  PCATS01 Loyalty for 055.02.09"), out.log());
        assertEquals("7", Files.readString(loysim.resolve("LoySim7.jar")));
        assertFalse(Files.exists(loysim.resolve("LoySim9.1.jar")));
        assertEquals("master055.02", SuiteBase.readMarker(loysim).orElseThrow().branch());
        assertEquals(1, server.downloads().size());
    }

    @Test
    void aMismatchOnAChosenSimulatorBuildIsAWarningNamingBothBasesAndStays() throws IOException {
        Path loysim = Files.createDirectories(tools.resolve("PCATS01"));
        Files.writeString(loysim.resolve("LoySim7.jar"), "7");
        SuiteBase.writeMarker(loysim, MASTER, "60"); // master: base 57 and later
        stages.suiteinfo = Map.of("Base_Level", "055.02.09");

        Outcome out = run(loysim().simBranch("master").simBuild("60"));

        assertTrue(out.log().contains("WARN  PCATS01 Loyalty base — built for base 57 and later, the Commander is "
                + "055.02 (CAT installed master build 60); the Simulator Build master 60 was chosen, so it stays"),
                out.log());
        assertEquals(List.of(), stages.stopped);
    }

    @Test
    void aChosenBuildAlreadyStagedIsAQuickPass() throws IOException {
        stageLoySim91();
        Path connector = Files.createDirectories(tools.resolve("terminalConnector-V4"));
        SuiteBase.writeMarker(connector, MASTER, "60");

        Outcome out = run(loysim().simBranch("master").simBuild("60"));

        assertTrue(out.keepGoing(), out.step().detail() + "\n" + out.log());
        assertTrue(out.log().contains("PASS  replace PCATS01 Loyalty — PCATS01 Loyalty: already master build 60"),
                out.log());
        assertTrue(out.log().contains("PASS  replace Terminal Connector"), out.log());
        assertEquals(List.of(), server.requests);
    }

    @Test
    void aChosenBuildReplacesTheStagedCopyInStepOne() throws IOException {
        Path loysim = Files.createDirectories(tools.resolve("PCATS01"));
        Files.writeString(loysim.resolve("lab-only.txt"), "the lab's own");

        Outcome out = run(loysim().simBranch("master").simBuild("60"));

        assertTrue(out.keepGoing(), out.step().detail() + "\n" + out.log());
        assertTrue(out.log().contains("PASS  replace PCATS01 Loyalty — PCATS01 Loyalty: replaced with master build "
                + "60's copy"), out.log());
        assertFalse(Files.exists(loysim.resolve("lab-only.txt")));
        assertTrue(out.log().contains("downloading RMS_Nightly_CLW/Petro/master/60/simulators/LoySim_V9.1.zip"),
                out.log());
    }

    @Test
    void aRunningSimulatorTheRunStartsIsRefusedAndEndsTheRun() throws IOException {
        Path loysim = Files.createDirectories(tools.resolve("PCATS01"));
        Files.writeString(loysim.resolve("lab-only.txt"), "the lab's own");
        stages.running = Map.of("PCATS01 Loyalty", "its process is running");

        Outcome out = run(loysim().simBranch("master").simBuild("60"));

        assertFalse(out.keepGoing());
        assertTrue(out.log().contains("FAIL  replace PCATS01 Loyalty — PCATS01 Loyalty: running (its process is "
                + "running); stop it before replacing its files"), out.log());
        assertTrue(out.step().detail().contains("replace PCATS01 Loyalty"), out.step().detail());
        assertTrue(Files.exists(loysim.resolve("lab-only.txt")));
    }

    @Test
    void aStagedOnlyRowNotReplacedIsAWarningNotAFailure() throws IOException {
        stageLoySim91();
        SuiteBase.writeMarker(tools.resolve("PCATS01"), MASTER, "60");
        stages.running = Map.of("Terminal Connector", "its process is running");

        Outcome out = run(loysim().simBranch("master").simBuild("60"));

        assertTrue(out.keepGoing(), out.step().detail() + "\n" + out.log());
        assertTrue(out.log().contains("FAIL  replace Terminal Connector"), out.log());
    }

    @Test
    void noLoginFailsStepOneAndTheStagedCopiesAreUnchanged() throws IOException {
        Path loysim = Files.createDirectories(tools.resolve("PCATS01"));
        Files.writeString(loysim.resolve("lab-only.txt"), "the lab's own");
        stages.login = false;

        Outcome out = run(loysim().simBranch("master").simBuild("60"));

        assertFalse(out.keepGoing());
        assertTrue(out.log().contains("FAIL  Simulator Build master 60 — no Artifactory login saved"), out.log());
        assertTrue(out.log().contains("the staged simulators are unchanged"), out.log());
        assertTrue(Files.exists(loysim.resolve("lab-only.txt")));
    }

    @Test
    void aMissingJavaSetWithNoLoginFailsWithTheSetNamed() throws IOException {
        stageLoySim91();
        deleteTree(tools.resolve("Java17").resolve("jaxb-ri"));
        stages.login = false;

        Outcome out = run(loysim());

        assertFalse(out.keepGoing());
        assertTrue(out.log().contains("FAIL  Java 17 v1.2 (Java17) — Java 17 v1.2: fetching from Artifactory failed: "
                + "no Artifactory login saved"), out.log());
    }

    @Test
    void aPlansRequiredBaseIsCheckedAgainstTheCommandersBase() throws IOException {
        stages.suiteinfo = Map.of("Base_Level", "057.02.00");
        Outcome out = run(new LaunchConfig.Builder(RIG).testPlan("PETROCORE-79667"));
        assertTrue(out.log().contains("FAIL  PETROCORE-79667 needs base 56.00 or earlier — this run's Commander is on "
                + "057.02.00"), out.log());
    }

    @Test
    void aRequiredBaseIsAWarningWhenTheRunInstallsABuildWhoseBaseIsNotKnown() throws IOException {
        Outcome out = run(new LaunchConfig.Builder(RIG).testPlan("PETROCORE-79667")
                .upgradeMode(UpgradeMode.NEW_INSTALL).commanderBranch("Solutions").commanderBuild("134"));
        assertTrue(out.log().contains("WARN  PETROCORE-79667 needs base 56.00 or earlier — the run installs a build "
                + "whose base is not known yet"), out.log());
    }

    private static void deleteTree(Path root) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.forEach(paths::add);
        }
        for (int i = paths.size() - 1; i >= 0; i--) {
            Files.delete(paths.get(i));
        }
    }
}
