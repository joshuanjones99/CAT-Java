package com.verifone.cat.validate;

import static com.verifone.cat.validate.ValidateFixtures.CARWASH_RECEIPT;
import static com.verifone.cat.validate.ValidateFixtures.LOYALTY_RECEIPT;
import static com.verifone.cat.validate.ValidateFixtures.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The receipt normalisers, against genuine corpus receipts (DESIGN.md §5.4, §3.3).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_normalisers.py}. The load-bearing
 * pair is the volatile-fields-disappear test and the real-change-is-still-caught tests: a
 * normaliser that hides a genuine regression is worse than the line diffing it replaces.
 */
class NormalisersTest {

    // ------------------------------------------------------------------
    // The declared list itself
    // ------------------------------------------------------------------

    @Test
    void everyNormaliserMatchesItsOwnRecordedEvidence() {
        List<Normaliser> all = new java.util.ArrayList<>(Normalisers.RECEIPT_NORMALISERS);
        all.addAll(Normalisers.CARD_IDENTITY_NORMALISERS);
        for (Normaliser normaliser : all) {
            assertFalse(normaliser.evidence().isEmpty(), normaliser.name() + " ships no evidence");
            for (String sample : normaliser.evidence()) {
                assertTrue(normaliser.matches(sample),
                        normaliser.name() + " no longer matches its evidence " + sample);
            }
        }
    }

    @Test
    void maskRejectsAPatternThatDoesNotMatchItsEvidence() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Normaliser.mask("cashier_bad", "Cashier:\\s*\\S+", "Cashier: <CASHIER>", 0)
                        .withEvidence("CSH: 1"));
        assertTrue(thrown.getMessage().contains("does not match its own recorded evidence"));
    }

    @Test
    void theShippedListIsTheRunAndRigClassesOnly() {
        for (Normaliser normaliser : Normalisers.RECEIPT_NORMALISERS) {
            assertTrue(normaliser.volatility() == Volatility.RUN || normaliser.volatility() == Volatility.RIG,
                    normaliser.name());
        }
        for (Normaliser normaliser : Normalisers.CARD_IDENTITY_NORMALISERS) {
            assertEquals(Volatility.DETERMINISTIC, normaliser.volatility(), normaliser.name());
            assertFalse(Normalisers.RECEIPT_NORMALISERS.contains(normaliser), normaliser.name());
        }
    }

    @Test
    void normaliserNamesAreUnique() {
        Set<String> names = new HashSet<>();
        Stream.concat(Normalisers.RECEIPT_NORMALISERS.stream(), Normalisers.CARD_IDENTITY_NORMALISERS.stream())
                .forEach(normaliser -> assertTrue(names.add(normaliser.name()), normaliser.name()));
    }

    @Test
    void compositeDatetimeRunsBeforeTheStandaloneDateAndTime() {
        List<String> order = names(Normalisers.RECEIPT_NORMALISERS);
        assertTrue(order.indexOf("datetime_stamp") < order.indexOf("date"));
        assertTrue(order.indexOf("datetime_stamp") < order.indexOf("time"));
        assertEquals("padding_after_mask", order.get(order.size() - 1), "padding collapse must see masked text");
    }

    @Test
    void design54SketchesDoNotMatchTheRealCorpus() {
        String text = text(LOYALTY_RECEIPT);
        assertFalse(Pattern.compile("Trans #\\s*\\d+").matcher(text).find());
        assertFalse(Pattern.compile("Cashier:\\s*\\S+").matcher(text).find());
        assertFalse(Pattern.compile("\\d{2}/\\d{2}/\\d{4}").matcher(text).find(), "dates are M/D/YY");
        assertTrue(text.contains("TRAN#1010897"));
        assertTrue(text.contains("CSH: 1"));
        assertTrue(text.contains("DR#1"));
    }

    // ------------------------------------------------------------------
    // Layout normalisation
    // ------------------------------------------------------------------

    @Test
    void layoutStripsTrailingPaddingButKeepsColumnAlignment() {
        String text = text(CARWASH_RECEIPT);
        assertTrue(text.contains("     WELCOME TO     \n"), "fixture is column-padded");
        String out = Normalisers.normaliseLayout(text);
        assertTrue(out.contains("     WELCOME TO\n"));
        assertTrue(out.contains("GALLONS:       4.548"), "interior alignment is real content");
        for (String line : out.split("\n", -1)) {
            assertEquals(line, line.stripTrailing());
        }
    }

    @Test
    void layoutDropsTrailingBlankLinesButKeepsInteriorOnes() {
        assertEquals("a\n\nb", Normalisers.normaliseLayout("a\n\nb\n   \n\n"));
    }

    @Test
    void layoutDropsThePrinterTailPatsReferencesEndWith() {
        assertEquals("AGAIN AND AGAIN\nTRAN#3010220",
                Normalisers.normaliseLayout("AGAIN AND AGAIN\r\nTRAN#3010220\r\n(H00"));
        assertEquals("a\n(H00\nb", Normalisers.normaliseLayout("a\n(H00\nb"), "only as the last line");
        assertEquals("a\n(H001", Normalisers.normaliseLayout("a\n(H001"));
    }

    @Test
    void maskingAVariableWidthFieldDoesNotLeaveVariablePadding() {
        String shortLine = "ST#10016               DR#1 TRAN#1010897";
        String longLine = "ST#CL120000000         DR#1 TRAN#7";
        assertEquals(Normalisers.normaliseReceipt(shortLine), Normalisers.normaliseReceipt(longLine));
        assertEquals("ST#<STORE> DR#<REGISTER> TRAN#<TXN>", Normalisers.normaliseReceipt(shortLine));
    }

    @Test
    void aCentredBatchLineNormalisesTheSameWhateverTheBatchWidth() {
        String head = "EPS Network Report\n";
        String tail = "\nTerminal Batch Clse: OPEN";
        String two = head + "            Terminal Batch 99           " + tail;
        String three = head + "           Terminal Batch 105           " + tail;
        String baseline = head + "            Terminal Batch #: <BATCH>" + tail;
        assertEquals(Normalisers.normaliseReceipt(two), Normalisers.normaliseReceipt(three));
        assertEquals(Normalisers.normaliseReceipt(two), Normalisers.normaliseReceipt(baseline));
        assertTrue(Normalisers.normaliseReceipt(three).contains("\nTerminal Batch #: <BATCH>\n"));
        // The flush-left form is left alone, and other centred lines keep their padding.
        assertEquals("Terminal Batch #: <BATCH>", Normalisers.normaliseReceipt("Terminal Batch #: 5"));
        assertTrue(Normalisers.normaliseReceipt(two).startsWith("EPS Network Report\n"));
        assertEquals("     WELCOME TO", Normalisers.normaliseReceipt("     WELCOME TO"));
    }

    // ------------------------------------------------------------------
    // Volatile fields disappear
    // ------------------------------------------------------------------

    /** The same test, run again an hour later on the same rig. */
    static String secondRun(String text) {
        return text.replace("3/1/21 13:21", "11/4/26 9:07")
                .replace("TRAN# 9010317", "TRAN# 9014412")
                .replace("Auth #: 123628", "Auth #: 998001")
                .replace("Stan: 001015724", "Stan: 002884109")
                .replace("Invoice #: 9781", "Invoice #: 10240")
                .replace("Trace #: 17", "Trace #: 6")
                .replace("     1 8 8 3 2      ", "     4 0 1 9 7      ");
    }

    @Test
    void twoRunsOfTheSameTestNormaliseToTheSameText() {
        String original = text(CARWASH_RECEIPT);
        String rerun = secondRun(original);
        assertNotEquals(original, rerun, "the fixture changed; update secondRun()");
        assertEquals(Normalisers.normaliseReceipt(original), Normalisers.normaliseReceipt(rerun));
    }

    @Test
    void theLoyaltyReceiptFooterNormalisesAway() {
        String original = text(LOYALTY_RECEIPT);
        String rerun = original.replace("TRAN#1010897", "TRAN#1010901")
                .replace("8/14/25 5:28:27 PM", "9/2/25 11:06:04 AM")
                .replace("Stan: 000412893", "Stan: 000412999")
                .replace("Trace #: 403", "Trace #: 511")
                .replace("Trace #: 409", "Trace #: 517");
        assertEquals(Normalisers.normaliseReceipt(original), Normalisers.normaliseReceipt(rerun));
    }

    @Test
    void noDateOrTimeSurvivesNormalisation() {
        for (String fixture : new String[] {CARWASH_RECEIPT, LOYALTY_RECEIPT}) {
            String out = Normalisers.normaliseReceipt(text(fixture));
            assertFalse(Pattern.compile("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b").matcher(out).find(), fixture);
            assertFalse(Pattern.compile("\\b\\d{1,2}:\\d{2}\\b").matcher(out).find(), fixture);
        }
    }

    @Test
    void normalisationIsIdempotent() {
        for (String fixture : new String[] {CARWASH_RECEIPT, LOYALTY_RECEIPT}) {
            String once = Normalisers.normaliseReceipt(text(fixture));
            assertEquals(once, Normalisers.normaliseReceipt(once), fixture);
        }
    }

    // ------------------------------------------------------------------
    // ... and real changes do NOT disappear. This is the important half.
    // ------------------------------------------------------------------

    static Stream<Arguments> realChanges() {
        return Stream.of(
                Arguments.of("FUEL SALE     $10.00", "FUEL SALE     $12.00", "fuel amount"),
                Arguments.of("TOTAL SALE    $20.00", "TOTAL SALE    $22.00", "sale total"),
                Arguments.of("GALLONS:       4.548", "GALLONS:       5.001", "dispensed volume"),
                Arguments.of("PRICE/G:      $2.199", "PRICE/G:      $2.499", "unit price"),
                Arguments.of("   CREDIT     $20.00", "   DEBIT      $20.00", "method of payment"),
                Arguments.of("PUMP# 01", "PUMP# 02", "which pump"),
                Arguments.of("SERVICE LEVEL: SELF", "SERVICE LEVEL: FULL", "service level"),
                Arguments.of("PRODUCT: UNLD", "PRODUCT: PREM", "fuel grade"),
                Arguments.of("Odometer: 37465", "Odometer: 91002", "fleet prompt the test entered"),
                Arguments.of("Resp Code: 000", "Resp Code: 051", "host response: a declined sale"),
                Arguments.of("  VALID FOR 14 DAYS", "  VALID FOR 30 DAYS", "car wash validity"),
                Arguments.of("************0005", "************9999", "which card was used"),
                Arguments.of("BP SITE ID: 99999", "BP SITE ID: 10001", "loyalty site id"),
                Arguments.of("Entry:Swiped", "Entry:Chip", "card entry method"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("realChanges")
    void aRealContentChangeIsStillCaught(String before, String after, String why) {
        String original = text(CARWASH_RECEIPT);
        assertTrue(original.contains(before), "fixture no longer contains " + before);
        String changed = original.replace(before, after);
        assertNotEquals(Normalisers.normaliseReceipt(original), Normalisers.normaliseReceipt(changed),
                "normalisation swallowed a real change to " + why);
    }

    @Test
    void aDroppedReceiptLineIsCaught() {
        String original = text(CARWASH_RECEIPT);
        String changed = original.replace("CARWASH       $10.00\n", "");
        assertNotEquals(Normalisers.normaliseReceipt(original), Normalisers.normaliseReceipt(changed));
    }

    @Test
    void anInsertedLineShiftsNothingButIsStillCaught() {
        String original = text(LOYALTY_RECEIPT);
        String changed = original.replace("                   Subtotal        19.60",
                "                   SURCHARGE        0.35\n                   Subtotal        19.60");
        List<String> before = List.of(Normalisers.normaliseReceipt(original).split("\n", -1));
        List<String> after = List.of(Normalisers.normaliseReceipt(changed).split("\n", -1));
        assertEquals(before.size() + 1, after.size());
        // Exactly one line differs in content, not everything from there down.
        Set<String> added = new HashSet<>(after);
        added.removeAll(before);
        assertEquals(Set.of("                   SURCHARGE        0.35"), added);
    }

    @Test
    void cardIdentityIsPreservedByDefault() {
        String out = Normalisers.normaliseReceipt(text(CARWASH_RECEIPT));
        assertTrue(out.contains("************0005"));
        assertFalse(out.contains("<PAN>"));
    }

    @Test
    void cardIdentityNormalisersAreAvailableAsAnOptIn() {
        List<Normaliser> withCard = new java.util.ArrayList<>(Normalisers.RECEIPT_NORMALISERS);
        withCard.addAll(Normalisers.CARD_IDENTITY_NORMALISERS);
        String out = Normalisers.normaliseReceipt(text(CARWASH_RECEIPT), withCard);
        assertTrue(out.contains("<PAN>"));
        assertFalse(out.contains("************0005"));
    }

    @Test
    void rigClassFieldsCanBeSwitchedOffInOnePlace() {
        String out = Normalisers.normaliseReceipt(text(LOYALTY_RECEIPT), Normalisers.RUN_VOLATILE_NORMALISERS);
        assertTrue(out.contains("ST#10016"));
        assertTrue(out.contains("DR#1"));
        assertTrue(out.contains("TRAN#<TXN>"), "run-volatile still masked");
        assertFalse(Normalisers.RIG_VOLATILE_NORMALISERS.isEmpty());
    }

    // ------------------------------------------------------------------
    // explain()
    // ------------------------------------------------------------------

    @Test
    void explainReportsWhatEachNormaliserMasked() {
        Map<String, List<String>> firings = Normalisers.explain(text(CARWASH_RECEIPT)).stream()
                .collect(Collectors.toMap(f -> f.normaliser().name(), Normalisers.Firing::hits));
        assertEquals(List.of("TRAN# 9010317"), firings.get("tran_no"));
        assertEquals(List.of("Auth #: 123628"), firings.get("auth_code"));
        assertEquals(List.of("Stan: 001015724", "Stan: 001015724"), firings.get("stan"));
        assertEquals(List.of("     1 8 8 3 2"), firings.get("carwash_code_spaced"));
        assertFalse(firings.containsKey("pan_masked"), "not in the default list");
    }

    @Test
    void explainDoesNotClaimANormaliserFiredWhenItDidNot() {
        assertTrue(Normalisers.explain("PLAIN RECEIPT\nTOTAL  1.00\n").isEmpty());
    }

    @Test
    void everyCarWashCodeOfABlockIsMaskedWhateverNamePrecedesIt() {
        String captured = "Car Wash code(s)\n   NAXML-COMBO-PLU-  89575\n   NAXML-COMBO-PLU-  44266\n";
        assertEquals("Car Wash code(s)\n   NAXML-COMBO-PLU-  <CARWASH_CODE>\n   NAXML-COMBO-PLU-  <CARWASH_CODE>",
                Normalisers.normaliseReceipt(captured));
        // A reference that prints the bare codes: both masked, so only the name differs.
        assertEquals("Car Wash code(s)\n <CARWASH_CODE>\n <CARWASH_CODE>",
                Normalisers.normaliseReceipt("Car Wash code(s)\n 12345\n 65078\n"));
    }

    @Test
    void theHeaderSiteNumberIsRigState() {
        String loaded = "AUTOMATION TODAY\n99999\nVFI-QA-LAB";
        String before = "AUTOMATION TODAY\n9999999\nVFI-QA-LAB";
        assertEquals(Normalisers.normaliseReceipt(loaded), Normalisers.normaliseReceipt(before));
        assertTrue(Normalisers.normaliseReceipt("PLU 99999 1.00").contains("PLU 99999 1.00"),
                "only a line of its own");
    }

    private static List<String> names(List<Normaliser> normalisers) {
        return normalisers.stream().map(Normaliser::name).toList();
    }
}
