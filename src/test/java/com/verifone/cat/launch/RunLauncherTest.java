package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.events.RunEnded;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.runconfig.RunScope;

/**
 * The launch framework through {@link RunLauncher}: the step order and names, the bracket's terminal
 * states, Stop and Pause, the run lock, and what lands in the run's folder.
 *
 * <p>Every step that touches a device or a simulator is unavailable here, so the runs end where the
 * Python CAT's would stop on a failed System Checks. The framework is what is under test.
 * The bench rig's names are {@code .invalid} and its controller is loopback, so a run here probes no
 * device. UNVERIFIED AGAINST HARDWARE; nothing here starts a process.
 */
class RunLauncherTest {

    @TempDir
    Path dir;

    @Test
    void aRunGoesThroughAllSevenStepsInOrderAndEndsFailed() throws IOException {
        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir))
                .launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        assertEquals(LaunchStepNames.ALL, result.steps().stream().map(StepResult::name).toList());
        // The bench rig's Commander does not resolve, so its network line fails System Checks, which
        // stops the run before the unported audit is reached.
        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.FAILED, systemChecks.state());
        assertTrue(systemChecks.detail().startsWith("rig hardware not ready: commander not reachable"),
                systemChecks.detail());
        for (StepResult later : result.steps().subList(1, 5)) {
            assertEquals(StepResult.SKIPPED, later.state(), later.name());
            assertEquals("not reached: System Checks failed", later.detail(), later.name());
        }
        // Steps 6 and 7 run whatever happened, as the Python CAT's do. Report writes from the run's
        // events; Cleanup skips the day close (step 4 was never reached) and completes.
        assertEquals(StepResult.COMPLETE, result.steps().get(5).state());
        assertEquals(StepResult.COMPLETE, result.steps().get(6).state());
        assertTrue(result.steps().get(6).detail().startsWith("day not closed: run stopped before Commander "
                + "Configuration"), result.steps().get(6).detail());
        for (String report : List.of("junit.xml", "summary.html", "index.json")) {
            assertTrue(Files.isRegularFile(result.runFolder().resolve(report)), report);
        }
        assertFalse(result.complete());
        assertFalse(result.cancelled());
    }

    @Test
    void theRunIdIsTwelveHexCharactersAndNamesTheFolder() throws IOException {
        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir))
                .launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        assertTrue(result.runId().matches("[0-9a-f]{12}"), result.runId());
        assertEquals(result.runId(), result.runFolder().getFileName().toString());
        assertTrue(Files.isDirectory(result.runFolder()));
    }

    @Test
    void eachStepIsBracketedInRunOrderAndTheLauncherWritesAsTheController() throws IOException {
        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir))
                .launch(LaunchConfig.forRig(LaunchFixtures.RIG));
        List<JsonNode> events = LaunchFixtures.events(result.runFolder());

        assertEquals("run.started", events.get(0).get("type").asText());
        assertEquals("controller", events.get(0).get("worker").asText());
        // A skipped step has no StepEntered, as the Python CAT's _skip_step writes none. The
        // checklist rows (step.check) of System Checks are not part of the bracket.
        List<String> brackets = events.stream()
                .filter(e -> e.get("type").asText().startsWith("step.")
                        && !e.get("type").asText().equals("step.check"))
                .map(e -> e.get("type").asText() + ":" + e.get("step").asText())
                .toList();
        assertEquals(List.of(
                "step.entered:System Checks",
                "step.finished:System Checks",
                "step.finished:Connections",
                "step.finished:Start Simulators",
                "step.finished:Commander Configuration",
                "step.finished:Tests Run",
                "step.entered:Generate Report",
                "step.finished:Generate Report",
                "step.entered:Cleanup",
                "step.finished:Cleanup"), brackets);
        // CAT's own RunFinished stands in for pytest's, since no test session ran.
        JsonNode finished = events.stream()
                .filter(e -> e.get("type").asText().equals("run.finished"))
                .findFirst().orElseThrow();
        assertEquals("error", finished.get("outcome").asText());
        assertFalse(finished.get("cancelled").asBoolean());
    }

    @Test
    void theRunLogReadsAsAPersonReadsIt() throws IOException {
        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir))
                .launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        String log = Files.readString(result.runFolder().resolve("run.log"));
        assertTrue(log.contains("STEP 1"), log);
        assertTrue(log.contains("PASS  rig file"), log);
        assertTrue(log.contains("STEP 7"), log);
    }

    @Test
    void theEndMarkerIsWrittenAfterTheLastStepsEvent() throws IOException {
        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir))
                .launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        String marker = Files.readString(result.runFolder().resolve(RunEnded.RUN_ENDED_FILE));
        assertTrue(marker.matches("\\d{4}-\\d{2}-\\d{2}T.*\\+00:00\\n"), marker);
        List<JsonNode> events = LaunchFixtures.events(result.runFolder());
        JsonNode last = events.get(events.size() - 1);
        assertEquals("step.finished", last.get("type").asText());
        assertEquals("Cleanup", last.get("step").asText());
    }

    @Test
    void aRigFileThatWillNotLoadFailsSystemChecksWithTheReasonAndCleanupStillRuns() throws IOException {
        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir))
                .launch(LaunchConfig.forRig("no-such-rig"));

        StepResult systemChecks = result.steps().get(0);
        assertEquals(StepResult.FAILED, systemChecks.state());
        assertEquals("rig no-such-rig did not load", systemChecks.detail());
        assertEquals("not reached: System Checks failed", result.steps().get(1).detail());
        assertEquals(StepResult.COMPLETE, result.steps().get(6).state());
        assertTrue(result.steps().get(6).detail().endsWith("rig lease free"), result.steps().get(6).detail());
    }

    @Test
    void aRigAnotherRunHoldsStopsAtSystemChecksAndNamesIt() throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        RigLease other = new RigLease(LaunchFixtures.RIG + ".run", paths.leaseDir(), "another run");
        other.acquire(Duration.ofSeconds(1));
        try {
            LaunchResult result = new RunLauncher(paths).launch(LaunchConfig.forRig(LaunchFixtures.RIG));

            assertEquals(StepResult.FAILED, result.steps().get(0).state());
            assertTrue(result.steps().get(0).detail().startsWith("rig 'bench' is in use by another CAT run: "),
                    result.steps().get(0).detail());
            String log = Files.readString(result.runFolder().resolve("run.log"));
            assertTrue(log.contains("in use by another CAT run"), log);
        } finally {
            other.release();
        }
    }

    @Test
    void theRunLockIsFreedWhenTheRunEnds() throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        new RunLauncher(paths).launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        // A second run on the same rig reaches the rig readiness check, so the first run let the lock go.
        LaunchResult second = new RunLauncher(paths).launch(LaunchConfig.forRig(LaunchFixtures.RIG));
        assertTrue(second.steps().get(0).detail().startsWith("rig hardware not ready: "),
                second.steps().get(0).detail());
    }

    @Test
    void aFakePosRunNamesNoRigAndTakesNoRunLock() throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        LaunchConfig config = new LaunchConfig.Builder("").fakePos(true).build();

        LaunchResult result = new RunLauncher(paths).launch(config);

        // Every System Checks line a fake-POS run owns passes: the audit, the petroapps checkout (none here), the
        // staging lines (advisory) and the plan-less checks. No rig, so no lock is taken and none is left held.
        assertEquals(StepResult.COMPLETE, result.steps().get(0).state(), result.steps().get(0).detail());
        RigLease free = new RigLease(LaunchFixtures.RIG + ".run", paths.leaseDir(), "probe");
        free.acquire(Duration.ofMillis(200));
        free.release();
    }

    @Test
    void aPartialRunThatSelectsNothingIsRefusedBeforeStepOneAndTheRigFileIsNotRead() throws IOException {
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG)
                .tags("@smoke")
                .build();

        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir)).launch(config);

        for (StepResult refused : result.steps().subList(0, 5)) {
            assertEquals(StepResult.SKIPPED, refused.state(), refused.name());
            assertEquals(RunLauncher.PARTIAL_REFUSED + "--tags '@smoke': no case of the plan is selected",
                    refused.detail(), refused.name());
        }
        assertFalse(Files.readString(result.runFolder().resolve("run.log")).contains("PASS  rig file"));
    }

    /** Three resolved cases, the middle one Not Run, in plan order; files under {@code dir/cases}. */
    private CaseSource planOfThree() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("cases"));
        Path one = Files.writeString(folder.resolve("PETROCORE-1-TestCase.xml"), "<testscript/>");
        Path three = Files.writeString(folder.resolve("PETROCORE-3-TestCase.xml"), "<testscript/>");
        return config -> List.of(new CaseEntry("PETROCORE-1", "tests/c/PETROCORE-1-TestCase.xml", one),
                new CaseEntry("PETROCORE-2", null, null),
                new CaseEntry("PETROCORE-3", "tests/c/PETROCORE-3-TestCase.xml", three));
    }

    @Test
    void runStartResolvesThePlanWidensToTheWholePlanAndRestatesRunStarted() throws IOException {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.wireTests(null, planOfThree(), null);
        LaunchResult result = launcher.launch(new LaunchConfig.Builder("").fakePos(true).testPlan("PETROCORE-70960")
                .build());

        List<JsonNode> events = LaunchFixtures.events(result.runFolder());
        JsonNode plan = events.stream().filter(e -> "plan.resolved".equals(e.get("type").asText())).findFirst()
                .orElseThrow();
        assertEquals(3, plan.get("cases").size());
        assertTrue(plan.get("cases").get(1).get("node").isNull(), plan.toString());
        List<JsonNode> started = events.stream().filter(e -> "run.started".equals(e.get("type").asText())).toList();
        assertEquals(2, started.size());
        assertFalse(started.get(0).get("restated").asBoolean());
        assertTrue(started.get(1).get("restated").asBoolean());
        assertTrue(started.get(1).get("cmd").asText()
                .contains("tests/c/PETROCORE-1-TestCase.xml tests/c/PETROCORE-3-TestCase.xml"), started.get(1).toString());
        String log = Files.readString(result.runFolder().resolve("events.ndjson"));
        assertTrue(log.contains("pytest targets, in plan order: tests/c/PETROCORE-1-TestCase.xml "
                + "tests/c/PETROCORE-3-TestCase.xml"), log);
    }

    @Test
    void aPartialRunNarrowsTheWholePlanInPlanOrderAndWarnsWhatItLeavesOut() throws IOException {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.wireTests(null, planOfThree(), null);
        LaunchResult result = launcher.launch(new LaunchConfig.Builder("").fakePos(true).testPlan("PETROCORE-70960")
                .only(List.of("3")).build());

        String log = Files.readString(result.runFolder().resolve("events.ndjson"));
        assertTrue(log.contains("partial run, pytest targets in plan order: tests/c/PETROCORE-3-TestCase.xml"), log);
        assertTrue(log.contains("partial run: 1 case(s) selected"), log);
        assertFalse(result.steps().get(0).detail().startsWith(RunLauncher.PARTIAL_REFUSED), result.steps().get(0)
                .detail());
    }

    @Test
    void aPartialRunNamingACaseNotInThePlanIsRefused() throws IOException {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.wireTests(null, planOfThree(), null);
        LaunchResult result = launcher.launch(new LaunchConfig.Builder("").fakePos(true).testPlan("PETROCORE-70960")
                .fromCase("PETROCORE-2").build());

        assertEquals(RunLauncher.PARTIAL_REFUSED + "--from PETROCORE-2: not a migrated case of the plan",
                result.steps().get(0).detail());
    }

    /** The authoring replay gate: suites and no plan, each suite one authored case, with a cases scope beside them. */
    @Test
    void suitesWithNoPlanAreEachACaseAndACasesScopeBesideThemIsNotAPlanSelection() throws IOException {
        List<CaseEntry> cases = PlanCases.suiteCases(List.of("tests/authored/PETROCORE-61338-TestCase.feature",
                "x/Other.feature"));
        assertEquals(List.of(new CaseEntry("PETROCORE-61338", "tests/authored/PETROCORE-61338-TestCase.feature",
                Path.of("tests/authored/PETROCORE-61338-TestCase.feature")),
                new CaseEntry("Other", "x/Other.feature", Path.of("x/Other.feature"))), cases);
        assertEquals(cases, PlanCases.forRun(LaunchFixtures.paths(dir)).cases(new LaunchConfig.Builder("")
                .fakePos(true).suite("tests/authored/PETROCORE-61338-TestCase.feature").suite("x/Other.feature")
                .build()));

        LaunchResult result = new RunLauncher(LaunchFixtures.paths(dir)).launch(new LaunchConfig.Builder("")
                .fakePos(true).suite("tests/authored/PETROCORE-61338-TestCase.feature")
                .scope(new RunScope(RunScope.Mode.CASES, "", List.of("PETROCORE-61338"), null, "", true)).build());
        assertFalse(result.steps().get(0).detail().startsWith(RunLauncher.PARTIAL_REFUSED),
                result.steps().get(0).detail());
    }

    @Test
    void aPlanThatDoesNotResolveIsAWarningAndAPartialRunOfItIsRefused() throws IOException {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.wireTests(null, config -> {
            throw new IOException("no catalogue");
        }, null);
        LaunchResult result = launcher.launch(new LaunchConfig.Builder("").fakePos(true).testPlan("PETROCORE-70960")
                .tags("@smoke").build());

        String log = Files.readString(result.runFolder().resolve("events.ndjson"));
        assertTrue(log.contains("could not resolve PETROCORE-70960 to a case list (IOException: no catalogue); Not Run "
                + "cannot be counted for this run"), log);
        assertEquals(RunLauncher.PARTIAL_REFUSED + "a partial run needs its plan's cases, and the plan did not resolve",
                result.steps().get(0).detail());
    }

    @Test
    void aRunStoppedBeforeItStartsEndsCancelledWithEveryStepPending() throws IOException {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.stop();

        LaunchResult result = launcher.launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        assertTrue(result.cancelled());
        assertFalse(result.complete());
        assertTrue(result.steps().stream().allMatch(s -> s.state().equals(StepResult.PENDING)));
        List<JsonNode> events = LaunchFixtures.events(result.runFolder());
        JsonNode last = events.get(events.size() - 1);
        assertEquals("run.finished", last.get("type").asText());
        assertTrue(last.get("cancelled").asBoolean());
        assertEquals("skipped", last.get("outcome").asText());
        assertTrue(Files.isRegularFile(result.runFolder().resolve(RunEnded.RUN_ENDED_FILE)));
    }

    @Test
    void aRunPausedBeforeItStartsIsStoppedWhileItWaitsAndStillWritesItsEnd() throws Exception {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.pause();
        AtomicReference<LaunchResult> outcome = new AtomicReference<>();
        Thread run = new Thread(() -> outcome.set(launcher.launch(LaunchConfig.forRig(LaunchFixtures.RIG))));
        run.start();

        launcher.stop();
        run.join(Duration.ofSeconds(30).toMillis());

        assertFalse(run.isAlive(), "the run did not stop while paused");
        LaunchResult result = outcome.get();
        assertTrue(result.cancelled());
        assertTrue(Files.isRegularFile(result.runFolder().resolve(RunEnded.RUN_ENDED_FILE)));
    }

    @Test
    void aLauncherLaunchesOneRunOnly() throws IOException {
        RunLauncher launcher = new RunLauncher(LaunchFixtures.paths(dir));
        launcher.launch(LaunchConfig.forRig(LaunchFixtures.RIG));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> launcher.launch(LaunchConfig.forRig(LaunchFixtures.RIG)));
        assertTrue(refused.getMessage().contains("already launched"), refused.getMessage());
    }
}
