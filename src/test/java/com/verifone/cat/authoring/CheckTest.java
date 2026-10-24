package com.verifone.cat.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The checker: every reason a draft is not a case CAT will run, by line, with the Python's messages. */
class CheckTest {

    private static final String NAME = "PETROCORE-61334-TestCase.feature";

    /** A feature with one scenario, its steps indented as a case writes them. */
    static String feature(String... lines) {
        StringBuilder out = new StringBuilder("@PETROCORE-61334\nFeature: Charity Donations\n\n  Scenario: Cash sale\n");
        for (String line : lines) {
            out.append("    ").append(line).append('\n');
        }
        return out.toString();
    }

    static final String GOOD = feature(
            "Given the register is idle",
            "When the cashier rings up PLU 9999",
            "And the cashier totals the sale",
            "And the customer pays 11.00 in cash",
            "Then the pinpad shows \"donate\"",
            "When the customer presses \"$0.51\" on the pinpad",
            "Then the receipt matches its baseline");

    /** The diagnostics as {@code line code} pairs, in the checker's order. */
    static List<String> codes(String text, String name) {
        List<String> out = new ArrayList<>();
        for (Check.Diagnostic d : Check.analyse(text, name).diagnostics()) {
            out.add(d.line() + " " + d.code());
        }
        return out;
    }

    static List<String> codes(String text) {
        return codes(text, NAME);
    }

    @Test
    void aWellFormedCaseIsCleanAndNamesWhatItTouches() {
        Check.Analysis analysis = Check.analyse(GOOD, NAME);
        assertTrue(analysis.ok());
        assertTrue(analysis.diagnostics().isEmpty(), analysis.diagnostics().toString());
        assertEquals("PETROCORE-61334", analysis.key());
        assertEquals(List.of("pos", "pinpad", "snapshot"), analysis.fixtures());
        assertEquals("Cash sale", analysis.scenarios().get(0));
    }

    @Test
    void anUnboundLineIsAnErrorWithTheClosestPhrases() {
        String text = GOOD.replace("rings up PLU 9999", "rings up the PLU 9999");
        List<Check.Diagnostic> errors = Check.analyse(text, NAME).errors();
        assertEquals(1, errors.size());
        assertEquals(6, errors.get(0).line());
        assertEquals("unbound", errors.get(0).code());
        assertTrue(errors.get(0).message().contains("the cashier rings up PLU {plu}"), errors.get(0).message());
    }

    @Test
    void patsImperativeStepsAreUnbound() {
        String text = feature(
                "When TOUCH_EVENT element \"EXAMPLE_ID\" of type \"ID\" on register with deviceid \"topaz101\"",
                "And SLEEP for 5000 milliseconds");
        assertEquals(List.of("5 unbound", "6 unbound"), codes(text));
    }

    @Test
    void aLineTwoPhrasesSayIsAmbiguous() {
        StepDef first = StepDef.of("the cashier waves", "POS screen", "action", "domain", "When the cashier waves",
                "Waves.", List.of());
        StepDef second = StepDef.of("the cashier {verb}", "POS screen", "action", "domain", "When the cashier waves",
                "Any verb.", List.of());
        Check.Analysis analysis = Check.analyse(feature("When the cashier waves"), NAME, List.of(first, second));
        assertEquals(List.of("ambiguous"), analysis.errors().stream().map(Check.Diagnostic::code).toList());
    }

    @Test
    void tagsNameTheCaseAndNothingElse() {
        assertEquals(List.of("1 tag", "1 tag"), codes(GOOD.replace("@PETROCORE-61334", "@smoke")));
        assertEquals(List.of("1 tag"), codes(GOOD, "PETROCORE-1-TestCase.feature"));
        assertEquals(List.of(), codes(GOOD.replace("@PETROCORE-61334", "@PETROCORE-61334 @plan_end")));
        // A group tag names cases for --tags: plan PETROCORE-143371's FEPs.
        assertEquals(List.of(), codes(GOOD.replace("@PETROCORE-61334", "@PETROCORE-61334 @fepsim @wex_otr")));
    }

    @Test
    void theFileNameIsTheCatCaseName() {
        assertTrue(codes(GOOD, "PETROCORE-61334-TestCase.scenario").contains("1 name"));
        assertTrue(codes(GOOD, "test_charity.feature").contains("1 name"));
    }

    @Test
    void oneCasePerFile() {
        String two = GOOD + "\n  Scenario: Another\n    Given the register is idle\n";
        assertTrue(codes(two).contains("1 one-case"));
    }

    @Test
    void anOutlineChecksEveryRow() {
        String text = feature(
                "Given the register is idle",
                "When the customer pays <amount> in cash",
                "Then the pinpad shows \"<prompt>\"",
                "",
                "  Examples:",
                "    | amount | prompt |",
                "    | 11.00  | donate |",
                "    | 2.0    | donate |").replace("Scenario:", "Scenario Outline:");
        assertEquals(List.of("6 unbound"), codes(text));
        String fixed = text.replace("2.0 ", "2.00");
        assertEquals(List.of(), codes(fixed));
        assertEquals(List.of("7 placeholder"), codes(fixed.replace("<prompt>", "<missing>")));
    }

    @Test
    void parseErrorsCarryTheLine() {
        assertEquals("1 parse", codes("Scenario: x\n  Given the register is idle\n").get(0));
    }

    @Test
    void uiLevelAndLongCasesWarnButPass() {
        Check.Analysis ui = Check.analyse(feature("When the cashier taps control \"EXPRESS_PANEL_VIEW_KEYPAD_ICON\""), NAME);
        assertTrue(ui.ok());
        assertEquals(List.of("ui-level"), ui.warnings().stream().map(Check.Diagnostic::code).toList());
        String[] many = new String[Check.MAX_STEPS + 1];
        Arrays.fill(many, "And the cashier totals the sale");
        Check.Analysis tooLong = Check.analyse(feature(many), null);
        assertTrue(tooLong.ok());
        assertEquals(List.of("long"), tooLong.warnings().stream().map(Check.Diagnostic::code).toList());
    }

    @Test
    void aDataTableIsRefused() {
        String text = feature("Given the register is idle", "  | a | b |");
        assertTrue(codes(text).contains("5 argument"), codes(text).toString());
    }

    @Test
    void aFeatureWithoutAFeatureLineIsAParseError() {
        Check.Analysis analysis = Check.analyse("# nothing here\n", NAME);
        assertEquals("no Feature: line", analysis.errors().get(0).message());
    }

    @Test
    void aSceneWithNoStepsIsEmpty() {
        String text = feature();
        assertTrue(codes(text).contains("4 empty"), codes(text).toString());
    }

    @Test
    void checkingAFileReadsItsText(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("PETROCORE-61334-TestCase.feature");
        Files.writeString(file, GOOD);
        assertFalse(Check.checkFiles(List.of(file)).get(file).errors().size() > 0);
    }
}
