package com.verifone.cat.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseRecordTest {

    private static final Instant START = Instant.parse("2026-10-24T10:00:00Z");

    @TempDir
    Path run;

    private CaseRecord newCase() {
        return new CaseRecord("tests/suites/charity/PETROCORE-61334-TestCase.feature::test_x",
                "PETROCORE-61334", run.resolve("PETROCORE-61334"), START);
    }

    @Test
    void theSecondReceiptOfACaseIsNumberedNotOverwritten() throws Exception {
        CaseRecord record = newCase();

        assertEquals("receipt.txt", record.recordReceipt("first", "").orElseThrow());
        assertEquals("receipt-2.txt", record.recordReceipt("second", "").orElseThrow());
        assertEquals("first", Files.readString(run.resolve("PETROCORE-61334/receipt.txt")));
        assertEquals("second", Files.readString(run.resolve("PETROCORE-61334/receipt-2.txt")));
    }

    @Test
    void aTlogIsKeptAsItWasReadAndDescribedWithItsBaseline() throws Exception {
        CaseRecord record = newCase();

        record.recordTlog("<tlog/>", "baseline.xml");

        assertEquals("<tlog/>", Files.readString(run.resolve("PETROCORE-61334/tlog.xml"), StandardCharsets.UTF_8));
        assertEquals("tlog.xml", record.captures().get(0).fileName());
        assertEquals("the transaction log the case captured, compared with baseline.xml",
                record.captures().get(0).what());
    }

    @Test
    void theNewestReceiptIsMarkedWithItsVerdict() throws Exception {
        CaseRecord record = newCase();
        record.recordReceipt("one", "");
        record.recordReceipt("two", "");

        record.markNewestReceipt(false);

        assertEquals("the receipt the case captured: DIFFERS (the failing step has the diff)",
                record.captures().get(1).what());
        assertEquals("the receipt the case captured", record.captures().get(0).what());
    }

    @Test
    void aValidationIsKeptPassedOrFailedWithItsCaseKey() throws Exception {
        CaseRecord record = newCase();

        CaseRecord.ValidationRecord check = record.recordValidation(
                "receipt", "baseline.txt", false, "\n-a\n+b\n", "a", "b", "");

        assertEquals("-a\n+b", check.diff());
        assertEquals("PETROCORE-61334", check.caseKey());
        assertEquals(1, record.validations().size());
    }

    @Test
    void aValidationDiffOverTheBoundIsCutWithANote() throws Exception {
        CaseRecord record = newCase();
        String diff = "x".repeat(CaseRecord.VALIDATION_DIFF_CHARS + 500);

        CaseRecord.ValidationRecord check = record.recordValidation("report", "r", false, diff, "", "", "");

        assertTrue(check.diff().startsWith("x".repeat(100)));
        assertTrue(check.diff().endsWith("… truncated (500 more characters)"), check.diff().substring(check.diff().length() - 60));
    }

    @Test
    void aFailureDetailOverTheBoundIsCutToItsLimit() throws Exception {
        CaseRecord record = newCase();
        record.beginStep(1, "When", "something");

        record.failStep("y".repeat(CaseRecord.DETAIL_CHARS + 100));

        String detail = record.steps().get(0).detail();
        assertEquals(CaseRecord.DETAIL_CHARS, detail.length());
        assertTrue(detail.endsWith("..."));
    }

    @Test
    void aStepThatReturnsWithAPendingValidationFailureReadsFail() throws Exception {
        CaseRecord record = newCase();
        record.beginStep(1, "Then", "the receipt matches");

        record.noteStepFailure("receipt differs");
        record.endStep();

        CaseRecord.StepRecord step = record.steps().get(0);
        assertEquals("failed", step.outcome());
        assertTrue(step.detail().contains("the case carried on, as PATS does"));
        assertTrue(step.detail().contains("receipt differs"));
    }

    @Test
    void aPendingFailureFromBeforeAStepIsClearedWhenTheNextStepBegins() throws Exception {
        CaseRecord record = newCase();
        record.noteStepFailure("stale");
        record.beginStep(1, "Given", "a thing");

        record.endStep();

        assertEquals("passed", record.steps().get(0).outcome());
    }

    @Test
    void aStepStillRunningWhenTheCaseFinishesIsFailed() throws Exception {
        CaseRecord record = newCase();
        record.beginStep(1, "Given", "a thing");

        record.finish("error", "setup failed", "Traceback...", Instant.parse("2026-10-24T10:00:05Z"));

        CaseRecord.StepRecord step = record.steps().get(0);
        assertEquals("failed", step.outcome());
        assertEquals("the test ended during this step", step.detail());
        assertEquals("error", record.outcome());
    }

    @Test
    void stepLinesReadPassFailAndNotRunInColumns() throws Exception {
        CaseRecord record = newCase();
        record.beginStep(1, "Given", "the POS is idle");
        record.endStep();
        record.beginStep(2, "When", "I pay");
        record.failStep("boom");
        record.addUnreachedStep(3, "Then", "the receipt prints");

        List<CaseRecord.StepRecord> steps = record.steps();
        String pass = CaseLog.stepLine(steps.get(0), ZoneOffset.UTC);
        String fail = CaseLog.stepLine(steps.get(1), ZoneOffset.UTC);
        String notRun = CaseLog.stepLine(steps.get(2), ZoneOffset.UTC);

        assertTrue(pass.startsWith("    1. PASS   "), pass);
        assertTrue(fail.startsWith("    2. FAIL   "), fail);
        assertTrue(notRun.startsWith("    3. NOT RUN"), notRun);
        assertTrue(notRun.endsWith("Then the receipt prints"), notRun);
    }

    @Test
    void secondsReadAsTenthsUnderAMinuteAndMinutesAfter() throws Exception {
        assertEquals("0.5s", CaseLog.seconds(0.5));
        assertEquals("59.9s", CaseLog.seconds(59.9));
        assertEquals("3m 07s", CaseLog.seconds(187.4));
    }

    @Test
    void stepsLogSaysWhatPassedFailedAndWhereItsFilesAre() throws Exception {
        CaseRecord record = newCase();
        record.setTitle("Charity fuel claim");
        record.recordReceipt("RECEIPT", "baseline.txt");
        Files.writeString(run.resolve("PETROCORE-61334/PETROCORE-61334-step-2-screenshot.png"), "png");
        record.beginStep(1, "Given", "the POS is idle");
        record.endStep();
        record.beginStep(2, "When", "I pay");
        record.failStep("payment declined");
        record.addUnreachedStep(3, "Then", "the receipt prints");
        record.recordValidation("receipt", "baseline.txt", false, "", "", "", "");
        record.finish("failed", "payment declined", "Traceback (most recent call last):",
                Instant.parse("2026-10-24T10:02:05Z"));

        String log = CaseLog.render(record, ZoneOffset.UTC);

        assertTrue(log.startsWith("PETROCORE-61334 — Charity fuel claim\nResult:   FAILED\n"), log);
        assertTrue(log.contains("Steps (3):"));
        assertTrue(log.contains("PASS"));
        assertTrue(log.contains("    2. FAIL"));
        assertTrue(log.contains("NOT RUN"));
        assertTrue(log.contains("Validations (1):\n  FAIL  receipt   baseline.txt"), log);
        assertTrue(log.contains("Why:\n  payment declined"));
        assertTrue(log.contains("PETROCORE-61334-step-2-screenshot.png  screenshot of the POS when it failed"));
        assertTrue(log.contains("receipt.txt"));
        assertTrue(log.contains("Traceback (for whoever debugs it):"));
        assertFalse(log.contains("steps.log  "), "the log does not list itself");
    }

    @Test
    void aPassedCaseHasNoWhyOrTraceback() throws Exception {
        CaseRecord record = newCase();
        record.beginStep(1, "Given", "a thing");
        record.endStep();
        record.finish("passed", "", "", Instant.parse("2026-10-24T10:00:02Z"));

        String log = CaseLog.render(record, ZoneOffset.UTC);

        assertTrue(log.startsWith("PETROCORE-61334\nResult:   PASSED\n"), log);
        assertFalse(log.contains("Why:"));
        assertFalse(log.contains("Traceback"));
    }

    @Test
    void aCaseWithNoStepsSaysItIsNotAScenario() throws Exception {
        CaseRecord record = newCase();
        record.finish("passed", "", "", Instant.parse("2026-10-24T10:00:02Z"));

        assertTrue(CaseLog.render(record, ZoneOffset.UTC).contains("(no .feature steps: this test is not a scenario)"));
    }

    @Test
    void describesTheFilesAnotherWriterLeftByTheirNames() throws Exception {
        assertEquals("screenshot of the POS when it failed", CaseLog.describe("x-screenshot.png"));
        assertEquals("the POS page's DOM when it failed", CaseLog.describe("x-dom.html"));
        assertEquals("what recovery captured before it recovered", CaseLog.describe("capture.json"));
        assertEquals("", CaseLog.describe("notes.txt"));
    }
}
