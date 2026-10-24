package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.verifone.cat.results.RunSummary;
import com.verifone.cat.xray.Summary.Verdict;

/** The summary's judgement calls: classify, the reason line, and the history, as the Python's tests. */
class SummaryTest {

    private static final String PLAN = Events.PLAN;

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "failed|AssertionError|X: 2 validation(s) failed ...#12 VALIDATE_RECEIPT(...): receipt differs|product",
            "failed|AssertionError|#12 VALIDATE_RECEIPT(...): receipt not received: the printed receipt exceeded its 117s budget|product",
            "failed|PatsStepError|X #1 WAIT_DCR_PROMPT(DISPLAY_KEY='WELCOME'): TimeoutExpired: DCR 1 prompt exceeded its 117s budget|equipment",
            "error|DeviceUnavailable|pinpad has no backend|equipment",
            "failed|KeyError|'foo'|harness",
            "skipped|XFailed|plan_end verdict untrusted: 3 earlier case(s) failed|cascade",
            "warned|| |warned"})
    void eachKindOfFailureLandsInItsCategory(String status, String excType, String detail, String expected) {
        // An empty CSV cell arrives as null: the port must read it as "no exception, no detail".
        assertEquals(expected, Summary.classify(status, excType, detail == null ? "" : detail.strip()));
    }

    @Test
    void reasonHeadKeepsTheFirstValidationAndDropsTheCasePrefix() {
        String detail = "PETROCORE-36879: 2 validation(s) failed (PATS carries on past these, so every one "
                + "is listed):\n\n#12 VALIDATE_RECEIPT(SELECT_FILE='x'): receipt differs:\n--- a\n+++ b"
                + "\n\n#13 VALIDATE_TRANSACTION";
        assertEquals("2 validation(s) failed — #12 VALIDATE_RECEIPT(SELECT_FILE='x'): receipt differs:",
                Summary.reasonHead(detail, ""));
    }

    @Test
    void reasonHeadIsBounded() {
        assertTrue(Summary.reasonHead("x".repeat(1000), "").length() <= 221);
    }

    @Test
    void theExecutionSummaryLineIsTheTitle() {
        assertEquals("Automation Test Execution - EMV_Debit_Contactless (PETROCORE-122723)",
                Summary.executionSummaryLine("PETROCORE-122723", "EMV_Debit_Contactless"));
        assertEquals("Automation Test Execution - " + PLAN, Summary.executionSummaryLine(PLAN, ""));
    }

    @Test
    void onlyFinishedRigRunsOfTheSamePlanCountNewestFirst() {
        String node = Events.node("PETROCORE-37043");
        List<RunSummary> rows = List.of(
                row("this", "2026-10-07T10:00:00+00:00", "failed", Map.of(node, "failed")),
                row("prev", "2026-10-06T10:00:00+00:00", "passed", Map.of(node, "passed")),
                row("older", "2026-10-05T10:00:00+00:00", "failed", Map.of(node, "failed")),
                row("double", "2026-10-06T12:00:00+00:00", "failed", Map.of(node, "failed"), true, PLAN, "lab"),
                row("other-plan", "2026-10-06T13:00:00+00:00", "failed", Map.of(node, "failed"), false, "PETROCORE-1", "lab"),
                row("unfinished", "2026-10-06T14:00:00+00:00", "incomplete", Map.of(node, "failed")),
                row("other-rig", "2026-10-06T15:00:00+00:00", "failed", Map.of(node, "failed"), false, PLAN, "bench2"));
        Map<String, List<Verdict>> history = Summary.historyFromRows(rows, "this", List.of(PLAN), "lab");
        assertEquals(Map.of(node, List.of(
                new Verdict("2026-10-06T10:00:00+00:00", "passed"),
                new Verdict("2026-10-05T10:00:00+00:00", "failed"))), history);
    }

    @Test
    void theSummarySaysNewOrRepeated(@TempDir Path dir) {
        String node = Events.node("PETROCORE-37043");
        List<RunSummary> rows = List.of(row("prev", "2026-10-06T10:00:00+00:00", "passed", Map.of(node, "passed")));
        XrayReporter reporter = Events.reporter(dir.resolve("run"), null, new double[] {0},
                state -> Summary.historyFromRows(rows, state.runId(), state.testPlans(), state.rig()));
        Events.stream().forEach(reporter::feed);
        reporter.finish();
        String summary = Events.read(dir.resolve("run").resolve(XrayReporter.SUMMARY_FILE));
        assertTrue(summary.contains("New: passed in the previous run"), summary);
        assertTrue(summary.contains("1 is new: it passed in the previous run on this rig"), summary);
    }

    private static RunSummary row(String runId, String started, String status, Map<String, String> tests) {
        return row(runId, started, status, tests, false, PLAN, "lab");
    }

    private static RunSummary row(String runId, String started, String status, Map<String, String> tests,
            boolean fakePos, String plan, String rig) {
        List<RunSummary.TestRow> rows = tests.entrySet().stream()
                .map(e -> new RunSummary.TestRow(e.getKey(), e.getValue(), 0.0, ""))
                .toList();
        return new RunSummary(runId, started, rig, fakePos, List.of(plan), status, 0, 0, 0, 0, 1.0,
                "", "", List.of(), rows, List.of());
    }
}
