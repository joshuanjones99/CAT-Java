package com.verifone.cat.validate;

import static com.verifone.cat.validate.ValidateFixtures.charity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code RECEIPT-RULE.txt}: the seed, its drift check, and what the rule drops (DESIGN.md §5.4).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_receipt_rule.py}. The receipt
 * fixtures are the committed charity baselines, so "an item line survives" is corpus text, not
 * invented text. The Python test's {@code write_seed} and {@code pytest} cases are not ported:
 * the seed is a literal here, and there is no pytest runner.
 */
class ReceiptRuleTest {

    private static final String BASELINE = charity("PETROCORE-61337-CATReceipt.txt");

    /** The real file's shape: one line, comma-separated, trailing separator. */
    private static final String RULE_TEXT = "TRAN#,Stan:,Auth #:,ST#CL120,CSH:,APP CRYPTOGRAM: ,ST#,\n";

    private static final String SUMMARY = charity("PETROCORE-61395-CATReceipt.txt");
    private static final String DEPARTMENT = charity("PETROCORE-61394-CATReceipt.txt");
    private static final Set<String> REPORTS = Set.of("PETROCORE-61394-CATReceipt.txt", "PETROCORE-61395-CATReceipt.txt");

    /** Lines Reg101 printed in 61395's Summary report (run 4556dba42943, 2026-09-18). */
    private static final Map<Integer, String> REAL_COUNTERS = new TreeMap<>(Map.ofEntries(
            Map.entry(7, "  REG NO#101 Receipt #1010167 to #1010191"),
            Map.entry(85, "TOTAL               62            0.00"),
            Map.entry(86, "NETWORK             61            0.00"),
            Map.entry(100, "ENDING           2294.76         3013.96"),
            Map.entry(101, "BEGINNING        1894.75         2571.53"),
            Map.entry(102, "DIFFERENCE        400.01          442.43"),
            Map.entry(176, "TOTAL               62            0.00"),
            Map.entry(177, "NETWORK             61            0.00"),
            Map.entry(191, "ENDING           2294.76         3013.96"),
            Map.entry(192, "BEGINNING        1894.75         2571.53"),
            Map.entry(193, "DIFFERENCE        400.01          442.43")));

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    @Test
    void tokensKeepFileOrderAndTheirOwnSpacing() {
        assertEquals(List.of("TRAN#", "Stan:", "Auth #:", "ST#CL120", "CSH:", "APP CRYPTOGRAM: ", "ST#"),
                ReceiptRule.parseReceiptRuleText(RULE_TEXT));
    }

    @Test
    void numericEntriesAreLineNumbersNotTokens() {
        // A per-case rule file's 26,27 must never become a substring match.
        assertEquals(List.of("TRAN#"), ReceiptRule.parseReceiptRuleText("26,27,TRAN#,"));
    }

    @Test
    void duplicatesCollapse() {
        // PATS's own file lists Stan: and CSH: twice.
        assertEquals(List.of("Stan:", "CSH:"), ReceiptRule.parseReceiptRuleText("Stan:,CSH:,Stan:,"));
    }

    // ------------------------------------------------------------------
    // Seed
    // ------------------------------------------------------------------

    @Test
    void theSeedIsWhatTheModuleApplies() {
        ReceiptRule seed = ReceiptRule.SEEDED;
        assertEquals(seed.tokens(), ReceiptRule.RECEIPT_RULE_TOKENS);
        assertFalse(seed.sha256().isEmpty(), "the seed must record the bytes it was parsed from");
        assertFalse(seed.commit().isEmpty(), "the seed must record which petroapps commit it came from");
        assertTrue(seed.source().endsWith("RECEIPT-RULE.txt"));
    }

    @Test
    void theTwoDeviationsAreDroppedAndRecorded() {
        ReceiptRule seed = ReceiptRule.SEEDED;
        assertEquals(ReceiptRule.DEVIATIONS.keySet(), new HashSet<>(seed.dropped()));
        assertFalse(ReceiptRule.RECEIPT_RULE_TOKENS.contains("ST#CL120"), "a rig literal in the validation layer");
        assertFalse(ReceiptRule.RECEIPT_RULE_TOKENS.contains("AuthNet:"), "card identity stays compared");
        // What still covers the trailer without the rig literal.
        assertTrue(ReceiptRule.RECEIPT_RULE_TOKENS.contains("ST#"));
        assertTrue(ReceiptRule.RECEIPT_RULE_TOKENS.contains("TRAN#"));
        // The rig owner kept this one, unlike AuthNet.
        assertTrue(ReceiptRule.RECEIPT_RULE_TOKENS.contains("Store #"));
    }

    // ------------------------------------------------------------------
    // Drift
    // ------------------------------------------------------------------

    @Test
    void aNewUpstreamTokenIsReported(@TempDir Path dir) throws IOException {
        Path rule = write(dir.resolve("RECEIPT-RULE.txt"), RULE_TEXT + "NEW TOKEN:,");
        ReceiptRule.SeedDrift drift = ReceiptRule.verifySeed(rule, Map.of());
        assertTrue(drift.checked() && !drift.inSync());
        assertTrue(drift.added().contains("NEW TOKEN:"));
        assertTrue(drift.describe().contains("ReceiptRule.SEEDED"));
    }

    @Test
    void theDeliberateDeviationsAreNotDrift(@TempDir Path dir) throws IOException {
        // ST#CL120 and AuthNet: are in the live file and never in ours, by decision.
        List<String> entries = new ArrayList<>(ReceiptRule.RECEIPT_RULE_TOKENS);
        entries.addAll(ReceiptRule.DEVIATIONS.keySet());
        Path rule = write(dir.resolve("RECEIPT-RULE.txt"), String.join(",", entries) + ",");
        ReceiptRule.SeedDrift drift = ReceiptRule.verifySeed(rule, Map.of());
        assertTrue(drift.checked() && drift.inSync(), drift.describe());
    }

    @Test
    void noCheckoutIsNotAFailure() {
        ReceiptRule.SeedDrift drift = ReceiptRule.verifySeed(null, Map.of());
        assertFalse(drift.checked());
        assertTrue(drift.describe().contains("not checked"));
        assertTrue(ReceiptRule.resolveRulePath(null, Map.of()).isEmpty());
    }

    // ------------------------------------------------------------------
    // What the rule drops
    // ------------------------------------------------------------------

    @Test
    void theTrailerGoesAndTheSaleStays() {
        // 61337's failure on 2026-09-15: Reg101 stopped printing TILL XXXX.
        String withTill = "ST#<STORE> TILL XXXX DR#<REGISTER> TRAN#<TXN>";
        String without = "ST#<STORE> DR#<REGISTER> TRAN#<TXN>";
        assertTrue(ReceiptRule.isIgnored(withTill) && ReceiptRule.isIgnored(without));
        assertEquals(ReceiptRule.applyRule(BASELINE),
                ReceiptRule.applyRule(BASELINE.replace(withTill, without)));
    }

    @Test
    void anItemOrDonationLineIsNeverDropped() {
        String kept = ReceiptRule.applyRule(BASELINE);
        for (String line : List.of("Charity PLU", "Subtotal", "TOTAL", "CHECK  $")) {
            assertTrue(kept.contains(line), line);
        }
    }

    @Test
    void changingTheDonationAmountStillFails() {
        String mutated = BASELINE.replace("9.50", "8.50");
        assertNotEquals(BASELINE, mutated);
        assertNotEquals(ReceiptRule.applyRule(BASELINE), ReceiptRule.applyRule(mutated));
    }

    @Test
    void aDatedLineIsDroppedAsPatsDropsIt() {
        assertTrue(ReceiptRule.isIgnored("CSH: 1                8/5/20 12:28:52 PM"));
        assertTrue(ReceiptRule.isIgnored("printed 2026-09-15"));
        assertFalse(ReceiptRule.isIgnored("   CHARITY                 1        0.51"));
    }

    @Test
    void aMaskedDateIsStillADate() {
        // The rule runs after the normalisers: a time-clock row is dated, however it was masked.
        assertTrue(ReceiptRule.isIgnored("456 <DATE> <TIME><DATE> <TIME> <TIME>"));
        assertTrue(ReceiptRule.isIgnored("CSH: 1                <DATETIME>"));
        assertFalse(ReceiptRule.isIgnored("Time: <TIME>"));
    }

    @Test
    void explicitTokensOverrideTheSeed() {
        assertEquals("keep me", ReceiptRule.applyRule("keep me\ndrop me", List.of("drop")));
    }

    // ------------------------------------------------------------------
    // assertReceipt applies the rule
    // ------------------------------------------------------------------

    private static SnapshotSession sessionWithBaseline(Path dir) throws IOException {
        SnapshotSession session = SnapshotSession.forNodeId(dir.resolve("test_charity.py"),
                "test_charity.py::test_check_sale");
        Path baseline = session.path(ReceiptAssertions.RECEIPT_SUFFIX, null);
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, BASELINE, StandardCharsets.UTF_8);
        return session;
    }

    @Test
    void aMissingTillNoLongerFails(@TempDir Path dir) throws IOException {
        // Exactly 61337, 61359 and 61388 on 2026-09-15 (HANDOFF.md 1.43).
        SnapshotSession session = sessionWithBaseline(dir);
        String captured = BASELINE.replace("ST#<STORE> TILL XXXX DR#", "ST#<STORE> DR#");
        assertNotEquals(BASELINE, captured);
        ReceiptAssertions.assertReceipt(captured, session);
    }

    @Test
    void theSuiteCanStillDemandEveryLine(@TempDir Path dir) throws IOException {
        SnapshotSession session = sessionWithBaseline(dir);
        String captured = BASELINE.replace("ST#<STORE> TILL XXXX DR#", "ST#<STORE> DR#");
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class, () ->
                ReceiptAssertions.assertReceipt(captured, session, null, Normalisers.RECEIPT_NORMALISERS, false));
        assertTrue(thrown.getMessage().contains("TILL"));
    }

    @Test
    void aRealDifferenceStillFails(@TempDir Path dir) throws IOException {
        SnapshotSession session = sessionWithBaseline(dir);
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class, () ->
                ReceiptAssertions.assertReceipt(BASELINE.replace("Charity PLU", "Charity PLX"), session));
        assertTrue(thrown.getMessage().contains("Charity PLU"));
    }

    @Test
    void theFailureShowsOnlyLinesTheRuleCompares(@TempDir Path dir) throws IOException {
        // Run f727d41f7c2d, 61386: the diff must not list the ignored trailer beside the failing line.
        SnapshotSession session = sessionWithBaseline(dir);
        String captured = BASELINE.replace("ST#<STORE> TILL XXXX DR#", "ST#<STORE> DR#")
                .replace("Charity PLU", "Charity PLX");
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class, () ->
                ReceiptAssertions.assertReceipt(captured, session));
        assertTrue(thrown.getMessage().contains("Charity PLX"));
        assertFalse(thrown.getMessage().contains("TILL"));
    }

    // ------------------------------------------------------------------
    // Report lines
    // ------------------------------------------------------------------

    private static String swap(String text, Map<Integer, String> lines) {
        String[] rows = text.split("\n", -1);
        lines.forEach((number, value) -> rows[number - 1] = value);
        return String.join("\n", rows);
    }

    private static Set<Integer> ignoredNumbers(String text) {
        Set<Integer> numbers = new HashSet<>();
        List<Boolean> ignored = ReceiptRule.ignoredLines(text);
        for (int i = 0; i < ignored.size(); i++) {
            if (ignored.get(i)) {
                numbers.add(i + 1);
            }
        }
        return numbers;
    }

    @Test
    void theSummaryReportLosesOnlyItsCounters() {
        // PATS's own 61395 rule is 3,4,7-10,85-87,100-102,152-154,176-178,191-193. Line 3 is the
        // masked date stamp; 10 is blank once normalised; 152-154 duplicate VOID TICKETS, whose
        // first copy (61-63) PATS compares.
        Set<Integer> counters = Set.of(3, 4, 7, 8, 9, 85, 86, 87, 100, 101, 102, 176, 177, 178, 191, 192, 193);
        assertEquals(counters, ignoredNumbers(SUMMARY));
    }

    @Test
    void theDepartmentReportLosesOnlyItsHeaderStamps() {
        // PATS's 61394 rule is 3,4,7; line 8 is CLOSE daypart PENDING.
        assertEquals(Set.of(3, 4, 7, 8), ignoredNumbers(DEPARTMENT));
    }

    @Test
    void theSalesTotalsStayCompared() {
        String[] rows = SUMMARY.split("\n", -1);
        Set<Integer> ignored = ignoredNumbers(SUMMARY);
        Set<String> kept = new HashSet<>();
        for (int i = 0; i < rows.length; i++) {
            if (!ignored.contains(i + 1)) {
                kept.add(rows[i]);
            }
        }
        for (String line : List.of(
                "MOP SALES                       551.29",
                "  CREDIT            10          225.77",
                "#ITEMS              66",
                "VOID TICKETS         0            0.00",
                "TOTAL POP      11     23.46    128.331",
                "TOTAL FUEL SALES                172.30",
                "TOTAL TAXES                       6.45")) {
            assertTrue(kept.contains(line), line);
        }
    }

    @Test
    void noSaleBaselineHasAReportLine() throws IOException {
        // So no sale verdict can change: on every committed sale baseline the rule skips exactly
        // the lines PATS's tokens and date rule skip, and nothing more.
        List<Path> sales;
        try (var files = Files.list(ValidateFixtures.charityDir())) {
            sales = files.filter(p -> p.getFileName().toString().endsWith("-CATReceipt.txt"))
                    .filter(p -> !REPORTS.contains(p.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        }
        assertTrue(sales.size() >= 33, "found " + sales.size());
        for (Path path : sales) {
            String text = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String[] lines = text.split("\n", -1);
            List<Boolean> pats = new ArrayList<>();
            for (String line : lines) {
                pats.add(ReceiptRule.RECEIPT_RULE_TOKENS.stream().anyMatch(line::contains)
                        || ReceiptRule.DATE_TIME.matcher(line).find()
                        || ReceiptRule.MASKED_DATE.matcher(line).find());
            }
            assertEquals(pats, ReceiptRule.ignoredLines(text), path.getFileName().toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "BEGINNING CASH                   150.00",
        "ACQ  #     TOTAL     BAL    DIFFERENCE",
        "TOTAL               69            0.00",
        "Receipt #1010042",
    })
    void similarLinesOutsideAReportStayCompared(String line) {
        assertFalse(ReceiptRule.ignoredLines(line).contains(true), line);
    }

    @Test
    void theBatchCounterIsNotComparedButTheTotalsAre() {
        // 37025: the batch counter counts batches since install (4 against 34).
        assertTrue(ReceiptRule.isIgnored("BATCH/DAY SEQ# = 34"));
        assertTrue(ReceiptRule.isIgnored("DAY SEQ#:   13"));
        assertTrue(ReceiptRule.isIgnored("RCPTS: 1010003-1010169 TOT=167 ACT=170"));
        assertFalse(ReceiptRule.isIgnored("TOTAL          78   2440.15    1   39.16"));
    }

    @Test
    void theTimingsSectionEndsAtTheNextHeader() {
        String text = "----TRANSACTION TIMINGS----\nTOTAL  69  0.00\n\n----TOTALS----\nTOTAL  69  0.00";
        assertEquals(List.of(false, true, false, false, false), ReceiptRule.ignoredLines(text));
    }

    // ------------------------------------------------------------------
    // assertReceipt on a report
    // ------------------------------------------------------------------

    private static SnapshotSession reportSession(Path dir) throws IOException {
        SnapshotSession session = SnapshotSession.forNodeId(dir.resolve("test_petrocore_61395.py"),
                "test_petrocore_61395.py::test_summary_report");
        Path path = session.path(ReceiptAssertions.RECEIPT_SUFFIX, null);
        Files.createDirectories(path.getParent());
        Files.writeString(path, SUMMARY, StandardCharsets.UTF_8);
        return session;
    }

    @Test
    void theRigsRealCountersPass(@TempDir Path dir) throws IOException {
        ReceiptAssertions.assertReceipt(swap(SUMMARY, REAL_COUNTERS), reportSession(dir));
    }

    @Test
    void withoutTheRuleTheyFail(@TempDir Path dir) throws IOException {
        SnapshotSession session = reportSession(dir);
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class, () ->
                ReceiptAssertions.assertReceipt(swap(SUMMARY, REAL_COUNTERS), session,
                        null, Normalisers.RECEIPT_NORMALISERS, false));
        assertTrue(thrown.getMessage().contains("2294.76"));
    }

    @Test
    void aDifferentSalesTotalFails(@TempDir Path dir) throws IOException {
        Map<Integer, String> lines = new TreeMap<>(REAL_COUNTERS);
        lines.put(15, "MOP SALES                       444.40");
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class, () ->
                ReceiptAssertions.assertReceipt(swap(SUMMARY, lines), reportSession(dir)));
        assertTrue(thrown.getMessage().contains("444.40"));
    }

    @Test
    void aDifferentVoidCountFails(@TempDir Path dir) throws IOException {
        String voidLine = "VOID TICKETS         1            9.50";
        ValidationFailedException thrown = assertThrows(ValidationFailedException.class, () ->
                ReceiptAssertions.assertReceipt(swap(SUMMARY, Map.of(61, voidLine, 152, voidLine)),
                        reportSession(dir)));
        assertTrue(thrown.getMessage().contains("VOID TICKETS"));
    }

    private static Path write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}
