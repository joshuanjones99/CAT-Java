package com.verifone.cat.results;

import static com.verifone.cat.results.RunFixtures.event;
import static com.verifone.cat.results.RunFixtures.finished;
import static com.verifone.cat.results.RunFixtures.logLine;
import static com.verifone.cat.results.RunFixtures.planResolved;
import static com.verifone.cat.results.RunFixtures.started;
import static com.verifone.cat.results.RunFixtures.stepEntered;
import static com.verifone.cat.results.RunFixtures.stepFinished;
import static com.verifone.cat.results.RunFixtures.test;
import static com.verifone.cat.results.RunFixtures.testStarted;
import static com.verifone.cat.results.RunFixtures.writeRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.report.ReportPaths;

/** The fold of one run's events (RunReplay) and the index row built from it (RunStore). */
class ReplayAndStoreTest {

    @TempDir
    Path root;

    private static final List<String> NO_PLANS = List.of();

    @Test
    void foldsStepsTestsAndTotalsInFileOrder() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "abc123", List.of("PETROCORE-1"), List.of("Cadence")),
                stepEntered("System Checks"),
                stepFinished("System Checks", "complete", 2.0),
                stepEntered("Tests Run"),
                stepFinished("Tests Run", "complete", 10.0),
                testStarted("tests/a.py::one"),
                test("tests/a.py::one", "passed", 1.5, ""),
                test("tests/a.py::two", "failed", 0.5, "AssertionError"),
                testStarted("tests/a.py::three"),
                finished("failed", 1, 1, 0, false, 99.0, List.of())));

        RunReplay replay = RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), false);

        RunReplay.StepState checks = replay.steps().get(0);
        assertEquals("complete", checks.status());
        assertEquals(2.0, checks.elapsed());
        assertEquals("pending", replay.steps().get(6).status());
        assertEquals(12.0, replay.runElapsed(), "the steps' sum is preferred to run.finished's elapsed");

        Map<String, String> status = replay.tests().stream()
                .collect(java.util.stream.Collectors.toMap(RunReplay.TestState::nodeid, RunReplay.TestState::status));
        assertEquals("passed", status.get("tests/a.py::one"));
        assertEquals("failed", status.get("tests/a.py::two"));
        assertEquals("running", status.get("tests/a.py::three"));
        assertEquals("AssertionError", replay.tests().get(1).excType());
        assertEquals(1, replay.passed());
        assertEquals(1, replay.failed());
        assertEquals("failed", replay.outcome());
        assertEquals("abc123", replay.gitHead());
        assertEquals(List.of("Cadence"), replay.planNames());
    }

    @Test
    void warnedNodeidsMoveOffPassedWhateverTheOrder() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "", NO_PLANS, NO_PLANS),
                test("tests/a.py::one", "passed", 1.0, ""),
                finished("passed", 0, 0, 1, false, 5.0, List.of("tests/a.py::one"))));

        RunReplay replay = RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), false);

        assertEquals("warned", replay.tests().get(0).status());
        assertEquals(1, replay.warned());
    }

    @Test
    void aPluginsUnknownRigDoesNotEraseTheLaunchers() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "", NO_PLANS, NO_PLANS),
                started("unknown", false, "", NO_PLANS, NO_PLANS)));

        assertEquals("lab", RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), false).rig());
    }

    @Test
    void logLinesAreSkippedUnlessAsked() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "", NO_PLANS, NO_PLANS),
                logLine("hello")));

        assertTrue(RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), false).logLines().isEmpty());
        assertEquals(1, RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), true).logLines().size());
    }

    @Test
    void caseVerdictsAggregateByFileAndUnmigratedCasesAreNotRun() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "", NO_PLANS, NO_PLANS),
                planResolved(
                        "PETROCORE-1", "tests/a.py", "Mixed file",
                        "PETROCORE-2", "tests/b.py", "Skipped file",
                        "PETROCORE-3", null, "Not migrated"),
                test("tests/a.py::one", "passed", 1.0, ""),
                test("tests/a.py::two", "failed", 1.0, "X"),
                test("tests/b.py::one", "skipped", 1.0, ""),
                finished("failed", 1, 1, 0, false, 3.0, List.of())));

        RunReplay replay = RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), false);

        assertEquals("failed", replay.caseVerdicts().get("PETROCORE-1"));
        assertEquals("skipped", replay.caseVerdicts().get("PETROCORE-2"));
        assertEquals(List.of("PETROCORE-3"), replay.notRunCases().stream().map(RunReplay.PlanCase::key).toList());
    }

    @Test
    void recoveryAndPreflightComeFromTheSharedFold() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "", NO_PLANS, NO_PLANS),
                event("recovery.rung", "nodeid", "tests/a.py::one", "rung", "restart", "outcome", "passed"),
                event("preflight.check", "name", "System Checks", "phase", "verify", "outcome", "failed",
                        "detail", "shim down")));

        RunReplay replay = RunReplay.read(run.resolve(ReportPaths.EVENTS_FILE), false);

        assertEquals("restart", replay.facts().recoveryRungs().get("tests/a.py::one").get(0).rung());
        assertEquals("shim down", replay.facts().preflightChecks().get(0).detail());
    }

    @Test
    void aCancelledRunIsCancelledNotFailed() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("lab", false, "", NO_PLANS, NO_PLANS),
                finished("failed", 0, 0, 0, true, 1.0, List.of())));

        RunSummary row = RunStore.summarize(run, RunStore.replay(run.resolve(ReportPaths.EVENTS_FILE), false));

        assertEquals("cancelled", row.status());
    }

    @Test
    void aRunWithoutRunFinishedIsIncomplete() throws Exception {
        Path run = writeRun(root, "r1", List.of(started("lab", false, "", NO_PLANS, NO_PLANS)));

        RunSummary row = RunStore.summarize(run, RunStore.replay(run.resolve(ReportPaths.EVENTS_FILE), false));

        assertEquals("incomplete", row.status());
        assertEquals("rig", row.segment());
    }

    @Test
    void fakePosIsInferredFromTheRecordedCommandLine() throws Exception {
        Map<String, Object> launched = event("run.started", "run_id", "r", "rig", "unknown",
                "cmd", "pytest tests/suites/charity --fake-pos", "started_at", RunFixtures.T0,
                "fake_pos", false, "test_plans", NO_PLANS, "plan_names", NO_PLANS);
        Path run = writeRun(root, "r1", List.of(launched, test("tests/a.py::one", "passed", 1.0, "")));

        RunSummary row = RunStore.summarize(run, RunStore.replay(run.resolve(ReportPaths.EVENTS_FILE), false));

        assertEquals("fake_pos", row.segment());
        assertEquals(1, row.tests().size(), "a fake-POS run keeps its passing tests");
    }

    @Test
    void aNoRigRunKeepsOnlyItsNonPassingTests() throws Exception {
        Path run = writeRun(root, "r1", List.of(
                started("unknown", false, "", NO_PLANS, NO_PLANS),
                test("tests/a.py::one", "passed", 1.0, ""),
                test("tests/a.py::two", "failed", 1.0, "X"),
                finished("failed", 1, 1, 0, false, 2.0, List.of())));

        RunSummary row = RunStore.summarize(run, RunStore.replay(run.resolve(ReportPaths.EVENTS_FILE), false));

        assertEquals("no rig", row.segment());
        assertEquals(List.of("tests/a.py::two"), row.tests().stream().map(RunSummary.TestRow::nodeid).toList());
    }

    @Test
    void aRunWithoutAStartTimeUsesTheFilesModificationTime() throws Exception {
        Path run = writeRun(root, "r1", List.of(event("test.finished", "nodeid", "tests/a.py::one",
                "outcome", "passed", "duration", 1.0, "warnings", 0, "exc_type", "")));
        Files.setLastModifiedTime(run.resolve(ReportPaths.EVENTS_FILE),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-10-01T10:00:00Z")));

        RunSummary row = RunStore.summarize(run, RunStore.replay(run.resolve(ReportPaths.EVENTS_FILE), false));

        assertTrue(OffsetDateTime.parse(row.startedAt()).getYear() == 2026);
        assertEquals("r1", row.runId());
    }

    @Test
    void onlyFoldersWithAnEventsFileAreRuns() throws Exception {
        writeRun(root, "with-events", List.of(started("lab", false, "", NO_PLANS, NO_PLANS)));
        Files.createDirectories(root.resolve("screenshots-only"));
        Files.writeString(root.resolve("stray.txt"), "not a run");

        List<Path> runs = RunStore.iterRunDirs(root);

        assertEquals(List.of(root.resolve("with-events")), runs);
        assertFalse(runs.contains(root.resolve("screenshots-only")));
    }

    @Test
    void aMissingRootHasNoRuns() {
        assertEquals(List.of(), RunStore.iterRunDirs(root.resolve("absent")));
    }

    @Test
    void theRootComesFromTheEnvironmentMapNotTheRealOne() {
        assertEquals(root, RunStore.resultsRoot(Map.of("CAT_RESULTS_DIR", root.toString())));
    }
}
