package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.printer.TopazPrinter;

/**
 * The pieces the Run and Results pages draw with: the side-by-side pairing ({@code difflib} port), the receipt roll,
 * the validations' groups and words, and the Log's sources and line format. Each as the Python's tests check it.
 */
class RunPiecesTest {

    private static final Instant AT = Instant.parse("2026-10-24T10:00:02Z");

    @Test
    void sideBySidePairsEveryLineAndMarksWhatDiffers() {
        List<SideBySide.Row> rows = SideBySide.rows("A\nB\nC\nD", "A\nX\nC\nD\nE");
        assertEquals(List.of(
                new SideBySide.Row("A", "A", "equal"),
                new SideBySide.Row("B", "X", "changed"),
                new SideBySide.Row("C", "C", "equal"),
                new SideBySide.Row("D", "D", "equal"),
                new SideBySide.Row(null, "E", "added")), rows);
        List<SideBySide.Row> removed = SideBySide.rows("A\nB", "A");
        assertEquals(new SideBySide.Row("B", null, "removed"), removed.get(removed.size() - 1));
        assertEquals(Set.of("added"), Set.copyOf(SideBySide.rows("", "A\nB").stream().map(SideBySide.Row::kind).toList()));
        assertTrue(SideBySide.rows("", "").isEmpty());
    }

    @Test
    void sideBySideMatchesDifflibOnARepeatedLine() {
        // difflib's longest match is the earliest longest run: "x x" pairs with the first two x's.
        List<SideBySide.Row> rows = SideBySide.rows("a\nx\nx\nb", "x\nx\nc\nx\nb");
        assertEquals(List.of(
                new SideBySide.Row("a", null, "removed"),
                new SideBySide.Row("x", "x", "equal"),
                new SideBySide.Row("x", "x", "equal"),
                new SideBySide.Row(null, "c", "added"),
                new SideBySide.Row(null, "x", "added"),
                new SideBySide.Row("b", "b", "equal")), rows);
    }

    private static RunState.LogLine printer(String message) {
        return new RunState.LogLine(AT, "INFO", TopazPrinter.RECEIPT_LOGGER, message, "");
    }

    @Test
    void aVerdictLandsOnTheReceiptItComparedAndItsDiffBesideIt() {
        List<ReceiptRoll.Item> roll = ReceiptRoll.parse(List.of(
                printer("port COM1 opened"),
                printer("┌ receipt 1 on COM1, 2 lines [3f2a] during PETROCORE-61334"),
                printer("│ WELCOME TO"),
                printer("│   CHARITY            1    1.00"),
                printer("└ end of receipt 1"),
                printer("╞ 3f2a PETROCORE-61334 failed PETROCORE-61334-CATReceipt.txt"),
                printer("┊ -   CHARITY            1    0.51"),
                printer("┊ +   CHARITY            1    1.00"),
                printer("╞ beef - passed -")));
        assertInstanceOf(ReceiptRoll.Note.class, roll.get(0));
        ReceiptRoll.Receipt receipt = assertInstanceOf(ReceiptRoll.Receipt.class, roll.get(1));
        assertEquals("PETROCORE-61334", receipt.caseKey());
        assertEquals(List.of("WELCOME TO", "  CHARITY            1    1.00"), receipt.lines());
        assertFalse(receipt.verdict().passed());
        assertEquals("PETROCORE-61334-CATReceipt.txt", receipt.verdict().baseline());
        assertEquals(2, receipt.verdict().diff().size());
        ReceiptRoll.Verdict alone = assertInstanceOf(ReceiptRoll.Verdict.class, roll.get(2));
        assertTrue(alone.passed());
        assertEquals("", alone.caseKey());
        assertEquals("", alone.baseline());
        assertEquals(1, ReceiptRoll.receipts(roll));
        assertEquals("1 receipt printed on COM1 this run, oldest first.", ReceiptRoll.head(roll, "COM1"));
        assertEquals("✖ receipt differs from PETROCORE-61334-CATReceipt.txt (the diff is on the right)",
                ReceiptRoll.verdictLine(receipt.verdict(), false));
        assertEquals("receipt not compared yet", ReceiptRoll.verdictLine(null, true));
    }

    @Test
    void aReceiptWhoseStartFellOffTheLogIsStillShown() {
        List<ReceiptRoll.Item> roll = ReceiptRoll.parse(List.of(printer("│ ORPHAN LINE")));
        ReceiptRoll.Receipt receipt = assertInstanceOf(ReceiptRoll.Receipt.class, roll.get(0));
        assertEquals(List.of("ORPHAN LINE"), receipt.lines());
        assertNull(receipt.verdict());
        assertTrue(ReceiptRoll.head(List.of(), "").startsWith("No receipts yet."));
    }

    private static RunState.Validation validation(String nodeid, String key, String kind, boolean passed) {
        return new RunState.Validation(nodeid, key, kind, "", passed, "", "", "", AT);
    }

    @Test
    void validationsGroupByCaseInTheOrderTheirFirstVerdictCame() {
        List<RunState.Validation> all = List.of(
                validation("t/PETROCORE-2-TestCase.xml::a", "", "tlog", true),
                validation("x", "PETROCORE-1", "receipt", false),
                validation("t/PETROCORE-2-TestCase.xml::a", "", "receipt", true),
                validation("other::t", "", "report", true));
        Map<String, List<RunState.Validation>> groups = Validations.groupByCase(all);
        assertEquals(List.of("PETROCORE-2", "PETROCORE-1", "other::t"), new ArrayList<>(groups.keySet()));
        assertEquals(2, groups.get("PETROCORE-2").size());
        assertEquals("4 validations, 1 failed", Validations.counts(all));
        assertEquals("1 validation, all passed", Validations.counts(all.subList(0, 1)));
        assertEquals("receipt", Validations.firstTab(all));
        assertEquals("other", Validations.tabOf("report"));
        assertEquals("TLog", Validations.kindLabel("tlog"));
        assertEquals("Custom", Validations.kindLabel("custom"));
        assertEquals("removed", Validations.diffClass("-a"));
        assertEquals("added", Validations.diffClass("+a"));
        assertEquals("head", Validations.diffClass("--- a"));
        assertEquals("hunk", Validations.diffClass("@@ -1 +1 @@"));
    }

    @Test
    void theLogSourcesAreAllTheStepsThenTheSimulatorsAndAnUnknownOneIsAll() {
        List<LogSources.Option> options = LogSources.options(List.of("BUYPASS Host"));
        assertEquals("All", options.get(0).label());
        assertEquals(1 + RunState.STEP_NAMES.size() + 1, options.size());
        assertEquals("sim:BUYPASS Host", options.get(options.size() - 1).value());

        RunStateReducer reducer = new RunStateReducer();
        RunState state = reducer.state();
        assertEquals(state.logLines(), LogSources.bySource(state, "nonsense"));
        RunState.LogLine line = new RunState.LogLine(AT, "WARNING", "petrotest.devices.config_shim", "slow", "");
        assertEquals("10:00:02 W config_shim: slow", LogSources.format(line));
    }
}
