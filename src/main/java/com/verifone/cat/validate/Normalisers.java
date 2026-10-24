package com.verifone.cat.validate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receipt normalisers, declared once and globally (DESIGN.md §5.4).
 *
 * <p>Section 3.3 of the design diagnoses receipt validation as per-test line-number diffing,
 * and TLog as one global ignore-list of volatile fields. This class is the second model
 * applied to receipts: one list, in source, so changing it is a reviewable diff.
 *
 * <p>A normaliser erases a field the test does not control, so an unchanged receipt compares
 * equal across runs. It is not a way to make an awkward assertion pass. Three classes:
 * {@link Volatility#RUN} and {@link Volatility#RIG} are active by default;
 * {@link Volatility#DETERMINISTIC} ({@link #CARD_IDENTITY_NORMALISERS}) is opt-in, because
 * masking the card's own identity deletes the assertion that the right card was used.
 *
 * <p>The patterns were measured by the Python CAT against all 7,077 receipts in the petroapps
 * git index, and the evidence strings and corpus counts travel with each entry. Three of
 * §5.4's five sketches matched nothing in that corpus (the labels are {@code TRAN#} and
 * {@code CSH:}, and dates are M/D/YY), so the shipped list is not the sketch. Order is
 * load-bearing: the composite datetime mask precedes the standalone date and time masks, and
 * the padding collapse runs last.
 *
 * <p>Layout normalisation is applied unconditionally first. A masked variable-width field in a
 * fixed-width receipt otherwise leaves variable trailing padding, which is the §3.3 failure
 * again.
 *
 * <p>UNVERIFIED AGAINST HARDWARE as a Java port. The Python CAT's tests are its evidence, and
 * {@code ValidationNormalisersTest} ports them. Java's {@code \d}, {@code \s} and {@code \b} are
 * ASCII by default; the corpus is ASCII, so the two agree on it.
 */
public final class Normalisers {

    private Normalisers() {}

    /** One normaliser's firing: what it replaced, for diagnostics. */
    public record Firing(Normaliser normaliser, List<String> hits) {
        public Firing {
            hits = List.copyOf(hits);
        }
    }

    /** The car-wash code's trailing number on each line of a block. */
    private static final Pattern CARWASH_CODE_LINE = Pattern.compile("(?m)\\d{4,6}[ \\t]*$", Pattern.UNIX_LINES);

    /**
     * Masks every code of a car-wash block, whatever name precedes it. The block is masked per
     * line, so a NAXML-style name and two codes in one sale are both covered (run
     * e17342994dc5, 2026-10-14).
     */
    private static String maskCarwashCodes(MatchResult block) {
        return CARWASH_CODE_LINE.matcher(block.group()).replaceAll("<CARWASH_CODE> ");
    }

    // ------------------------------------------------------------------
    // RUN: changes on every execution (timestamps, STAN, auth code)
    // ------------------------------------------------------------------

    private static final List<Normaliser> RUN_VOLATILE = List.of(
            // Footer stamp: `CSH: 1                8/14/25 5:28:27 PM`. Composite first.
            Normaliser.mask("datetime_stamp",
                    "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}[ \\t]+\\d{1,2}:\\d{2}(?::\\d{2})?[ \\t]*(?:AM|PM)",
                    "<DATETIME>", 0)
                    .withEvidence("8/14/25 5:28:27 PM", "10/31/25 11:04:13 AM", "1/15/20 7:18:37 AM")
                    .withCorpusFiles(6339)
                    .withNote("Beyond DESIGN.md 5.4: the sketch had no composite, so its date and "
                            + "time regexes each had to match half of this line and neither did."),
            // Sketched as \d{2}/\d{2}/\d{4}: 9 of 7,077 files. Dates are M/D/YY.
            Normaliser.mask("date", "\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b", "<DATE>", 0)
                    .withEvidence("8/14/25", "10/31/25", "3/1/21", "01/10/20", "7/20/23")
                    .withCorpusFiles(6916)
                    .inDesign54(),
            // Sketched as \d{2}:\d{2}:\d{2}: 21.5%. The trailing lookahead stops it eating the
            // head of a longer time token.
            Normaliser.mask("time", "\\b\\d{1,2}:\\d{2}(?::\\d{2})?[ \\t]*(?:AM|PM)?(?![\\d:])", "<TIME>", 0)
                    .withEvidence("5:28:27 PM", "11:04:13 AM", "13:10:58", "13:21", "12:22")
                    .withCorpusFiles(6899)
                    .inDesign54(),
            // Sketched as `Trans #`, which does not occur in the corpus; the label is `TRAN#`.
            Normaliser.mask("tran_no", "\\bTRAN#[ \\t]*\\d+", "TRAN#<TXN>", 0)
                    .withEvidence("TRAN#1010897", "TRAN# 9010317", "TRAN#9010719")
                    .withCorpusFiles(6788)
                    .inDesign54()
                    .inReceiptRuleTxt()
                    .withNote("DESIGN.md 5.4 spelled this `Trans #`: 0 matches in 7,077 files."),
            // The mobile host prints `AUTH #: 1`, counting up per sale. A departure from PATS,
            // accepted by the rig owner 2026-09-15: PATS's case-sensitive `Auth #:` token would
            // otherwise compare this line.
            Normaliser.mask("auth_code", "\\bAuth[ \\t]*#[ \\t]*:?[ \\t]*[A-Z0-9]+", "Auth #: <AUTH>",
                    Pattern.CASE_INSENSITIVE)
                    .withEvidence("Auth #: 999696", "Auth# 028833", "Auth #: 123628", "AUTH #: 1")
                    .withCorpusFiles(5066)
                    .inReceiptRuleTxt(),
            Normaliser.mask("stan", "\\bStan:[ \\t]*\\d+", "Stan: <STAN>", 0)
                    .withEvidence("Stan: 000412893", "Stan: 001015724")
                    .withCorpusFiles(4608)
                    .inDesign54()
                    .inReceiptRuleTxt()
                    .withNote("The one DESIGN.md 5.4 sketch that was already correct."),
            Normaliser.mask("invoice", "\\bInv(?:oice)?[ \\t]*#[ \\t]*:?[ \\t]*\\d+", "Invoice #: <INVOICE>", 0)
                    .withEvidence("Invoice #: 72541", "Inv#  1000810", "Invoice #: 9781")
                    .withCorpusFiles(4498)
                    .inReceiptRuleTxt(),
            Normaliser.mask("trace", "\\bTrace[ \\t]*#[ \\t]*:?[ \\t]*\\d+", "Trace #: <TRACE>", 0)
                    .withEvidence("Trace #: 403", "Trace #: 17")
                    .withCorpusFiles(1233)
                    .inReceiptRuleTxt(),
            // The car-wash code is generated per transaction by the wash controller; the TLog
            // side already ignores it. Two printed forms occur. Only the code is masked, so a
            // build that prints the wash's PLU before the code still shows that layout change.
            Normaliser.masking("carwash_code",
                    "(?<=Car Wash code\\(s\\)\\n)(?:[ \\t]*(?:\\S[^\\n]*?[ \\t]+)?\\d{4,6}[ \\t]*(?:\\n|$))+",
                    Normalisers::maskCarwashCodes, 0)
                    .withEvidence("Car Wash code(s)\n 42480 ",
                            "Car Wash code(s)\n   CAR-WASH-PLU-2   50084",
                            "Car Wash code(s)\n   NAXML-COMBO-PLU-  89575\n   NAXML-COMBO-PLU-  44266\n")
                    .withCorpusFiles(306)
                    .withNote("Companion to TLOG-RULE.txt's trlCwCode. Every code line of the block "
                            + "is masked, whatever name the build prints before it."),
            Normaliser.mask("carwash_good_thru_day", "\\bGood thru (?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b",
                    "Good thru <DAY>", 0)
                    .withEvidence("Good thru Thu 11/26/20")
                    .withNote("The car-wash code's expiry weekday, which follows the date the "
                            + "date normaliser already masks."),
            // The space-separated print form, e.g. PETROCORE-10823. Anchored to a whole line of
            // single digits, so it cannot swallow an item line.
            Normaliser.masking("carwash_code_spaced", "(?m)^([ \\t]*)(?:\\d ){3,}\\d[ \\t]*$",
                    m -> m.group(1) + "<CARWASH_CODE>", 0)
                    .withEvidence("     1 8 8 3 2      \n")
                    .withNote("The space-separated print form. Anchored to a whole line of "
                            + "single digits, so it cannot swallow an item line."),
            Normaliser.mask("approve_no", "\\bApprove[ \\t]+\\d+", "Approve <APPROVAL>", 0)
                    .withEvidence("Approve 0001")
                    .withCorpusFiles(153),
            Normaliser.mask("card_bal", "\\bCARD BAL:[ \\t]*\\$?[\\d.,]+", "CARD BAL: <BAL>", 0)
                    .withEvidence("CARD BAL: $0.00", "CARD BAL: $14.92")
                    .withCorpusFiles(110)
                    .inReceiptRuleTxt()
                    .withNote("Prepaid or fleet balance carried by the card between runs, so it "
                            + "drifts as the suite spends it. Not a sale amount."),
            Normaliser.mask("ctl", "\\bCTL#[ \\t]*:?[ \\t]*\\d+", "CTL#<CTL>", 0)
                    .withEvidence("CTL#: 154666")
                    .withCorpusFiles(76)
                    .inReceiptRuleTxt(),
            Normaliser.mask("batch", "\\bBatch[ \\t]*#?[ \\t]*:?[ \\t]*\\d+", "Batch #: <BATCH>", 0)
                    .withEvidence("Batch 6", "Batch #: 5")
                    .withCorpusFiles(67),
            // ARQC is a fresh cryptogram per tap; IAD carries the application transaction
            // counter. TVR and TSI are not here: they are card identity, see below.
            Normaliser.mask("emv_cryptogram", "\\b(?:ARQC|IAD)[ \\t]*:?[ \\t]*[A-F0-9]{8,}", "<EMV>", 0)
                    .withEvidence("ARQC 03A88770BA1EDC7E", "IAD: 06020103A02002")
                    .withCorpusFiles(52)
                    .withNote("ARQC is a fresh cryptogram per tap; IAD carries the application "
                            + "transaction counter, so it moves too."),
            Normaliser.mask("ref_num", "\\bRef(?:erence)?[ \\t]*#?[ \\t]*:?[ \\t]*\\d{4,}", "Ref #: <REF>", 0)
                    .withEvidence("Reference:10285", "Ref: 00100010064")
                    .withCorpusFiles(42),
            Normaliser.mask("seq", "\\bSeq(?:uence)?[ \\t]*#?[ \\t]*:?[ \\t]*\\d+", "Seq #: <SEQ>", 0)
                    .withEvidence("Seq#: 020")
                    .withCorpusFiles(8),
            Normaliser.mask("daypart", "\\bdaypart#[ \\t]*:?[ \\t]*\\d+", "daypart#<DAYPART>", 0)
                    .withEvidence("daypart#007", "daypart#291")
                    .withCorpusFiles(173)
                    .inReceiptRuleTxt());

    // ------------------------------------------------------------------
    // RIG: constant on one rig, different on the next (store, register, cashier)
    // ------------------------------------------------------------------

    private static final List<Normaliser> RIG_VOLATILE = List.of(
            // PATS's RECEIPT-RULE.txt hardcodes the rig literal ST#CL120; the pattern does not
            // need editing when a third rig appears.
            Normaliser.mask("store_st", "\\bST#[ \\t]*\\S+", "ST#<STORE>", 0)
                    .withVolatility(Volatility.RIG)
                    .withEvidence("ST#10016", "ST#CL120")
                    .withCorpusFiles(6410)
                    .inReceiptRuleTxt(),
            Normaliser.mask("cashier", "\\bCSH:[ \\t]*\\d+", "CSH: <CASHIER>", 0)
                    .withVolatility(Volatility.RIG)
                    .withEvidence("CSH: 1", "CSH: 0")
                    .withCorpusFiles(6102)
                    .inDesign54()
                    .inReceiptRuleTxt()
                    .withNote("DESIGN.md 5.4 spelled this `Cashier:`: 0 matches in 7,077 files."),
            Normaliser.mask("register", "\\bDR#[ \\t]*\\d+", "DR#<REGISTER>", 0)
                    .withVolatility(Volatility.RIG)
                    .withEvidence("DR#1")
                    .withCorpusFiles(6044)
                    .withNote("Absent from the sketch and from RECEIPT-RULE.txt, yet present on 85.4% "
                            + "of receipts."),
            Normaliser.mask("shift_no", "\\bShift[ \\t]*#[ \\t]*:?[ \\t]*\\d+", "Shift #: <SHIFT>", 0)
                    .withVolatility(Volatility.RIG)
                    .withEvidence("Shift #: 1")
                    .withCorpusFiles(2502),
            Normaliser.mask("station_no", "\\bStn#[ \\t]*\\d+(?:[ \\t]+\\d+)?", "Stn# <STATION>", 0)
                    .withVolatility(Volatility.RIG)
                    .withEvidence("Stn#  00088810   001")
                    .withCorpusFiles(307),
            // The header's site number: the Buypass table's TLStNum once loaded, 9999999 before.
            Normaliser.mask("site_number", "(?m)^(?:9999999|99999)$", "<SITE_NUMBER>", 0)
                    .withVolatility(Volatility.RIG)
                    .withEvidence("9999999", "99999")
                    .withCorpusFiles(3345)
                    .withNote("The header's site number: rig state, not the case. One baseline read "
                            + "9999999 and the rig printed 99999 (run d8b6e195854a, 2026-10-20)."));

    // ------------------------------------------------------------------
    // DETERMINISTIC: decided by the card the test inserted. Opt-in only.
    // ------------------------------------------------------------------

    /**
     * Fields the test's own card decides. Masking them turns "the AMEX credit path printed an
     * AMEX AID" into an assertion about nothing, so they are not in
     * {@link #RECEIPT_NORMALISERS}. Opt in per suite where the card is not fixed.
     */
    public static final List<Normaliser> CARD_IDENTITY_NORMALISERS = List.of(
            Normaliser.mask("pan_masked", "[X\\*]{4,}\\d{2,4}", "<PAN>", 0)
                    .withVolatility(Volatility.DETERMINISTIC)
                    .withEvidence("**************3161", "************0005", "XXXXXXX5412")
                    .withCorpusFiles(5164)
                    .withNote("Already masked by the POS. The visible last four identify the test "
                            + "card, so they are an assertion, not noise."),
            Normaliser.mask("aid", "\\bAID:[ \\t]*[A-F0-9]{10,}", "AID: <AID>", 0)
                    .withVolatility(Volatility.DETERMINISTIC)
                    .withEvidence("AID: A0000001523010", "AID: A0000000031010")
                    .withCorpusFiles(2628),
            Normaliser.mask("authnet", "\\bAuthNet:[ \\t]*\\S+", "AuthNet: <AUTHNET>", 0)
                    .withVolatility(Volatility.DETERMINISTIC)
                    .withEvidence("AuthNet: DISCOVER", "AuthNet: AMEX", "AuthNet: VISA")
                    .withCorpusFiles(2454)
                    .inReceiptRuleTxt(),
            Normaliser.mask("emv_terminal_flags", "\\b(?:TVR|TSI)[ \\t]*:?[ \\t]*[A-F0-9]{8,}", "<EMV_FLAGS>", 0)
                    .withVolatility(Volatility.DETERMINISTIC)
                    .withEvidence("TVR: 0000048000", "TVR: 4200008000")
                    .withCorpusFiles(36)
                    .withNote("Derived from the card and the terminal's configuration, not from the "
                            + "run. A change is usually a real change in EMV behaviour."));

    /**
     * Padding whose width is a function of a field just masked. {@code ST#10016} masked to
     * {@code ST#<STORE>} would leave the run of spaces after it as variable as the store number
     * was. Deliberately narrow: only runs that immediately follow a replacement token.
     */
    private static final Normaliser PADDING_AFTER_MASK = Normaliser.mask("padding_after_mask",
            "(?<=[A-Z_]>)[ \\t]{2,}", " ", 0)
            .withEvidence("ST#<STORE>               DR#1")
            .withNote("Consequence of masking a variable-width field in fixed-width column output.");

    /**
     * The EPS network centres {@code Terminal Batch <n>} on a 40-column line, so the padding
     * before it is one space shorter once the batch reaches three digits (Cadence 79667,
     * 2026-10-22). The batch is masked, so its centring is too.
     */
    private static final Normaliser CENTRED_BATCH_PADDING = Normaliser.mask("centred_batch_padding",
            "(?m)^[ \\t]+(?=(?:[A-Za-z]+[ \\t]+)?Batch #: <BATCH>[ \\t]*$)", "", 0)
            .withEvidence("            Terminal Batch #: <BATCH>", "           Acquirer Batch #: <BATCH>")
            .withNote("The EPS network centres the batch line, and the centring shifts with the "
                    + "digit count. The batch is masked, so the padding before it goes too.");

    /**
     * The active list: the run and rig classes, then the centred-batch padding (after
     * {@code batch}, which it matches), then the padding collapse last (it consumes the others'
     * output).
     */
    public static final List<Normaliser> RECEIPT_NORMALISERS;

    static {
        List<Normaliser> all = new ArrayList<>(RUN_VOLATILE);
        all.addAll(RIG_VOLATILE);
        all.add(CENTRED_BATCH_PADDING);
        all.add(PADDING_AFTER_MASK);
        RECEIPT_NORMALISERS = List.copyOf(all);
    }

    /** The run-volatile class alone, for a suite that keeps the rig fields compared. */
    public static final List<Normaliser> RUN_VOLATILE_NORMALISERS = RUN_VOLATILE;

    /** The rig-volatile class, to be added back where snapshots are per rig. */
    public static final List<Normaliser> RIG_VOLATILE_NORMALISERS = RIG_VOLATILE;

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    /**
     * Non-breaking and other exotic spaces the Topaz GWT layout pads columns with: U+00A0,
     * U+2000 through U+200B, U+202F, U+205F and U+3000.
     */
    private static final Pattern ODD_SPACES = Pattern.compile("[\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]");

    /**
     * The last line of PATS reference receipts for a few SCO and Menu cases: the tail of a
     * printer command PATS's capture kept, not receipt text. UNVERIFIED AGAINST HARDWARE which
     * command it is.
     */
    private static final String PRINTER_TAIL = "(H00";

    /**
     * Line endings, exotic spaces and trailing padding. Interior spacing is kept: column
     * alignment inside a line is real receipt content. Trailing blank lines are dropped (a
     * printer feed is not content); interior blank lines are kept (they separate sections).
     * A last line of exactly {@code (H00} is dropped too.
     */
    public static String normaliseLayout(String text) {
        String unified = text.replace("\r\n", "\n").replace("\r", "\n");
        List<String> lines = new ArrayList<>();
        for (String line : ODD_SPACES.matcher(unified).replaceAll(" ").split("\n", -1)) {
            lines.add(line.stripTrailing());
        }
        dropTrailingBlankLines(lines);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(PRINTER_TAIL)) {
            lines.remove(lines.size() - 1);
            dropTrailingBlankLines(lines);
        }
        return String.join("\n", lines);
    }

    private static void dropTrailingBlankLines(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    // ------------------------------------------------------------------
    // Applying them
    // ------------------------------------------------------------------

    /** {@link #normaliseReceipt(String, List)} with the global {@link #RECEIPT_NORMALISERS}. */
    public static String normaliseReceipt(String text) {
        return normaliseReceipt(text, RECEIPT_NORMALISERS);
    }

    /**
     * {@code text} with layout normalised and every given field masked. This replaces
     * {@code -Rule.txt}: it answers "which fields are not mine to assert on?" once, for the
     * whole corpus, where {@code -Rule.txt} answered "which lines may I compare?" per test.
     *
     * <p>Trailing padding is stripped a second time after masking, because masking changes line
     * lengths (a transaction number gaining a digit).
     */
    public static String normaliseReceipt(String text, List<Normaliser> normalisers) {
        String out = normaliseLayout(text);
        for (Normaliser normaliser : normalisers) {
            out = normaliser.apply(out);
        }
        return stripEachLine(out);
    }

    /**
     * Which normalisers fired on {@code text}, and on what. Runs against the layout-normalised
     * text and does not chain substitutions, so each entry reports what that normaliser saw in
     * the original. Only normalisers that fired are returned.
     */
    public static List<Firing> explain(String text, List<Normaliser> normalisers) {
        String prepared = normaliseLayout(text);
        List<Firing> firings = new ArrayList<>();
        for (Normaliser normaliser : normalisers) {
            List<String> hits = new ArrayList<>();
            Matcher m = normaliser.pattern().matcher(prepared);
            while (m.find()) {
                hits.add(m.group());
            }
            if (!hits.isEmpty()) {
                firings.add(new Firing(normaliser, hits));
            }
        }
        return List.copyOf(firings);
    }

    /** {@link #explain(String, List)} with the global {@link #RECEIPT_NORMALISERS}. */
    public static List<Firing> explain(String text) {
        return explain(text, RECEIPT_NORMALISERS);
    }

    /**
     * Strips trailing whitespace from every line. Java's {@code stripTrailing} and Python's
     * {@code rstrip} differ only on U+0085, which a receipt does not carry.
     */
    static String stripEachLine(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line.stripTrailing());
        }
        return String.join("\n", lines);
    }
}
