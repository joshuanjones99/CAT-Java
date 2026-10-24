package com.verifone.cat.report.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code run.log}, checked against the shapes the Python CAT's module docstring and its launch
 * test ({@code test_the_run_writes_a_human_readable_run_log}) state. Times are built in the
 * machine's zone, because the log prints local clock time.
 */
class RunLogWriterTest {

    private static final String CASE = "tests/suites/charity/PETROCORE-61334-TestCase.feature::charity";

    @TempDir
    Path dir;

    /** 16:40:01 on 24 October 2026, local time, as an instant. */
    private static Instant local(int hour, int minute, int second) {
        return ZonedDateTime.of(2026, 10, 24, hour, minute, second, 0, ZoneId.systemDefault())
                .toInstant();
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void theHeaderNamesTheDayThenTheRunIsOneLinePerThing() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(16, 40, 1), new CatEvent.RunStarted("a1b2c3d4e5f6", "lab", "tests/suites/charity",
                    "pytest", 1, local(16, 40, 1), "1", null, List.of("PETROCORE-70960"),
                    List.of("Charity Donations Indoor"), false, "", false));
        }

        String text = read(file);
        assertTrue(text.startsWith("CAT run log — Saturday 24 October 2026\n\n"), text);
        assertTrue(text.contains(
                "16:40:01  RUN       a1b2c3d4e5f6 on lab: PETROCORE-70960 (Charity Donations Indoor)\n"),
                text);
    }

    @Test
    void aRestatedRunStartedAddsNothing() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        CatEvent.RunStarted first = new CatEvent.RunStarted("r1", "lab", "suite", "pytest", 1,
                local(9, 0, 0), "1", null, null, null, false, null, false);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(9, 0, 0), first);
            log.emit(local(9, 0, 1), new CatEvent.RunStarted("r1", "lab", "suite-restated", "pytest -x",
                    1, local(9, 0, 1), "1", null, null, null, false, null, true));
        }
        String text = read(file);
        assertTrue(text.contains("r1 on lab: suite"), text);
        assertFalse(text.contains("restated"), text);
    }

    @Test
    void aFakePosRunSaysSo() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(9, 0, 0), new CatEvent.RunStarted("r", "lab", "suite", "pytest", 1,
                    local(9, 0, 0), "1", null, null, null, true, null, false));
        }
        assertTrue(read(file).contains(
                "[FAKE POS: evidence about CAT, not about a rig]"));
    }

    @Test
    void stepsAndTheirChecksAreTaggedAndWordedAsSteppedInPython() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(16, 40, 1), new CatEvent.StepEntered("System Checks"));
            log.emit(local(16, 40, 1), new CatEvent.StepCheck("System Checks", "petroapps current",
                    SubstepOutcome.RUNNING, "", 0));
            log.emit(local(16, 40, 2), new CatEvent.StepCheck("System Checks", "petroapps current",
                    SubstepOutcome.PASSED, "up to date", 0));
            log.emit(local(16, 40, 2), new CatEvent.StepCheck("System Checks", "printer COM1",
                    SubstepOutcome.FAILED, "not listed\nsecond line", 0));
            log.emit(local(16, 40, 10), new CatEvent.StepFinished("System Checks", "failed", 9.0,
                    "rig hardware not ready: x"));
            log.emit(local(16, 45, 1), new CatEvent.StepEntered("Tests Run"));
            log.emit(local(16, 45, 2), new CatEvent.StepFinished("Connections", "skipped", 0, ""));
        }

        String text = read(file);
        assertTrue(text.contains("16:40:01  STEP 1    System Checks: started\n"), text);
        assertTrue(text.contains("16:40:01              ....  petroapps current — started\n"), text);
        assertTrue(text.contains("16:40:02              PASS  petroapps current — up to date\n"), text);
        assertTrue(text.contains("16:40:02              FAIL  printer COM1 — not listed\n"), text);
        assertTrue(text.contains(
                "16:40:10  STEP 1    System Checks: FAILED in 9.0s — rig hardware not ready: x\n"), text);
        assertTrue(text.contains("16:45:01  STEP 5    Tests Run: started\n"), text);
        // A skipped step has no duration.
        assertTrue(text.contains("16:45:02  STEP 2    Connections: SKIPPED\n"), text);
    }

    @Test
    void aCaseIsNamedWithItsSummaryAndItsVerdictPointsAtItsFolder() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(16, 40, 1), new CatEvent.PlanResolved(List.of(
                    new CatEvent.PlanCaseDescriptor("PETROCORE-61334", "Charity donation, cash sale", null))));
            log.emit(local(16, 45, 1), new CatEvent.TestStarted(CASE));
            log.emit(local(16, 45, 2), new CatEvent.TestStep(CASE, 1, "Given",
                    "the cashier is signed on", SubstepOutcome.PASSED, 0.8, ""));
            log.emit(local(16, 45, 3), new CatEvent.TestStep(CASE, 2, "When",
                    "a cash sale", SubstepOutcome.SKIPPED, 0, ""));
            log.emit(local(16, 45, 33), new CatEvent.TestFinished(CASE, Outcome.PASSED, 32.1, 0, "", ""));
        }

        String text = read(file);
        assertTrue(text.contains("16:45:01  CASE      PETROCORE-61334: Charity donation, cash sale\n"), text);
        assertTrue(text.contains("16:45:02              PASS  1. Given the cashier is signed on  (0.8s)\n"), text);
        assertTrue(text.contains("16:45:03              SKIP  2. When a cash sale\n"), text);
        assertTrue(text.contains(
                "16:45:33  CASE      PETROCORE-61334: PASSED in 32s  (see PETROCORE-61334\\steps.log)\n"),
                text);
    }

    @Test
    void aFailedCaseCarriesItsExceptionAndItsFirstLineOnly() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(16, 45, 1), new CatEvent.TestFinished(CASE, Outcome.FAILED, 4.2, 0,
                    "AssertionError", "expected 1\nthe rest of the traceback"));
        }
        assertTrue(read(file).contains(
                "CASE      PETROCORE-61334: FAILED in 4.2s — AssertionError: expected 1  (see "), read(file));
    }

    @Test
    void aRepairingCaseIsPassedWithWarningsAndANonCaseNodeIdIsItsOwnName() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        String plain = "tests/suites/misc/test_plain.py::test_plain";
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(10, 0, 0), new CatEvent.TestFinished(CASE, Outcome.REPAIRING, 130, 0, "", ""));
            log.emit(local(10, 0, 0), new CatEvent.TestFinished(plain, Outcome.TIMEOUT, 90, 0, "", ""));
        }
        String text = read(file);
        assertTrue(text.contains("PETROCORE-61334: PASSED WITH WARNINGS in 2m 10s"), text);
        assertTrue(text.contains(plain + ": TIMED OUT in 1m 30s"), text);
        assertFalse(text.contains(plain + "\\steps.log"), text);
    }

    @Test
    void aValidationRowNamesItsBaseline() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(10, 0, 0), new CatEvent.TestValidation(CASE, "PETROCORE-61334", "receipt",
                    "", true, "", "", ""));
            log.emit(local(10, 0, 1), new CatEvent.TestValidation(CASE, "PETROCORE-61334", "tlog",
                    "tlog-baseline.xml", false, "- old\n+ new", "", ""));
        }
        String text = read(file);
        assertTrue(text.contains("PASS  validation: receipt against its baseline\n"), text);
        assertTrue(text.contains("FAIL  validation: tlog against tlog-baseline.xml — - old\n"), text);
    }

    @Test
    void logLinesKeepOnlyErrorsAndPauseNotTheLaunchersOwnLines() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(10, 0, 0), new CatEvent.LogLine("INFO", "petrotest.x", "chatter", ""));
            log.emit(local(10, 0, 0), new CatEvent.LogLine("ERROR", "petrotest.devices.viperpay",
                    "the card reader did not answer\nmore", ""));
            log.emit(local(10, 0, 0), new CatEvent.LogLine("ERROR", "cat", "a check failed", ""));
            log.emit(local(10, 0, 0), new CatEvent.LogLine("INFO", "cat.pause", "paused by operator", ""));
        }
        String text = read(file);
        assertFalse(text.contains("chatter"), text);
        assertFalse(text.contains("a check failed"), text);
        assertTrue(text.contains("ERROR     [petrotest.devices.viperpay] the card reader did not answer\n"), text);
        assertTrue(text.contains("INFO      [cat.pause] paused by operator\n"), text);
    }

    @Test
    void theRunEndsWithItsCountsAndSaysWhenItWasStopped() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(11, 0, 0), new CatEvent.RunFinished("r", Outcome.FAILED, 1, 1, 0, List.of(),
                    false, 3725, 0));
            log.emit(local(11, 0, 0), new CatEvent.RunFinished("r", Outcome.SKIPPED, 0, 0, 0, List.of(),
                    true, 5, 0));
        }
        String text = read(file);
        assertTrue(text.contains("RUN       tests finished: 1 passed, 1 failed, 0 passed with warnings, in 1h 02m\n"),
                text);
        assertTrue(text.contains("tests finished (stopped by the operator): 0 passed, 0 failed"), text);
    }

    @Test
    void theElapsedTimeIsWrittenTheWayPythonWritesIt() {
        assertEquals("0.8s", RunLogWriter.took(0.8));
        assertEquals("9.9s", RunLogWriter.took(9.94));
        assertEquals("32s", RunLogWriter.took(32.1));
        assertEquals("2m 05s", RunLogWriter.took(125));
        assertEquals("1h 02m", RunLogWriter.took(3725));
    }

    @Test
    void aLongFirstLineIsCutWithAnEllipsisAtTwoHundredForty() {
        String cut = RunLogWriter.firstLine("x".repeat(300));
        assertEquals(240, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("", RunLogWriter.firstLine("   \n  "));
        assertEquals("first", RunLogWriter.firstLine("  first\nsecond"));
    }

    @Test
    void aLogThatCannotBeWrittenCostsTheLogNotTheRun() throws IOException {
        // The "parent" is a file, so the log cannot be created beneath it.
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a folder");
        Path file = blocker.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            assertDoesNotThrow(() -> log.emit(local(10, 0, 0), new CatEvent.StepEntered("Cleanup")));
            assertDoesNotThrow(() -> log.emit(local(10, 0, 1), new CatEvent.StepEntered("Cleanup")));
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void theFileIsAppendedToNotTruncated() throws IOException {
        Path file = dir.resolve(RunLogWriter.RUN_LOG);
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(10, 0, 0), new CatEvent.StepEntered("Cleanup"));
        }
        try (RunLogWriter log = new RunLogWriter(file)) {
            log.emit(local(10, 0, 1), new CatEvent.StepFinished("Cleanup", "complete", 1, ""));
        }
        String text = read(file);
        // Each writer dates its own first line, as the Python one does: two writers, two headers.
        assertEquals(2, text.split("CAT run log", -1).length - 1);
        assertTrue(text.contains("STEP 7    Cleanup: started"), text);
        assertTrue(text.contains("STEP 7    Cleanup: COMPLETE in 1.0s"), text);
    }
}
