package com.verifone.cat.pos.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.verifone.cat.pos.receipt.DomReceiptCapture.DomDriver;
import com.verifone.cat.pos.receipt.DomReceiptCapture.DomElement;
import com.verifone.cat.pos.receipt.DomReceiptCapture.ItemFamilies;

/**
 * The receipt capture and normalisation (DESIGN.md 5.3). Ported from the Python CAT's
 * {@code tests/unit/test_pos_receipt.py}, hardware-free: the DOM path runs against a fake driver,
 * the serial path against a fake printer, and the text path against a real corpus receipt.
 *
 * <p>The sample receipt is {@code pos/receipt/PETROCORE-121630-Receipt.txt}, copied byte for byte
 * from the Python test. It is column-aligned, and the layout is what the snapshot compares.
 *
 * <p>Not ported: the Python's catalogue-prefix test and its {@code Pos} TLog test, which need the
 * locator catalogue and the page objects (both left for {@code com.verifone.cat.pos}).
 */
class ReceiptCaptureTest {

    static final String DESCRIPTIONS = "ReceiptView_ItemLineDescription_";
    static final String AMOUNTS = "ReceiptView_ItemLineAmount_";
    static final String QUANTITIES = "ReceiptView_ItemLineQuantity_";
    static final String WHOLE_LINES = "ReceiptView_ItemLine_";

    static final ItemFamilies FAMILIES =
            new ItemFamilies(DESCRIPTIONS, AMOUNTS, QUANTITIES, WHOLE_LINES);

    /** Named rather than written literally: the Topaz GWT layout pads columns with these. */
    static final String NBSP = " ";
    static final String FIGURE_SPACE = " ";

    static final String SAMPLE_RECEIPT = sampleReceipt();

    // ------------------------------------------------------------------
    // Fakes: no Playwright, no rig
    // ------------------------------------------------------------------

    /**
     * Answers {@code findFamily} from a map keyed by ID prefix, and records every query. It
     * implements only that one method, so a capture that starts probing element by element fails
     * here.
     */
    static final class FakeDom implements DomDriver {

        private final Map<String, List<Object[]>> dom = new HashMap<>();
        final List<String> queries = new ArrayList<>();

        /** {@code indexAndText} alternates a row index (Integer) and its text (String). */
        FakeDom with(String prefix, Object... indexAndText) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < indexAndText.length; i += 2) {
                rows.add(new Object[] {indexAndText[i], indexAndText[i + 1]});
            }
            dom.put(prefix, rows);
            return this;
        }

        @Override
        public List<DomElement> findFamily(String idPrefix) {
            queries.add(idPrefix);
            List<DomElement> out = new ArrayList<>();
            for (Object[] row : dom.getOrDefault(idPrefix, List.of())) {
                out.add(new DomElement(idPrefix + row[0], (String) row[1]));
            }
            return out;
        }
    }

    /**
     * A printer holding a fixed list of prints. Its count starts at zero, so a capture made on it
     * reads every print it holds, as a capture made before the sale would.
     */
    static final class FakePrinter implements ReceiptPrinter {

        final List<List<String>> prints;
        int alive;

        FakePrinter(List<List<String>> prints) {
            this.prints = new ArrayList<>(prints);
        }

        @Override
        public int receiptCount() {
            return 0;
        }

        @Override
        public List<List<String>> receiptsSince(int index) {
            return prints.subList(index, prints.size());
        }

        @Override
        public void checkAlive() {
            alive++;
        }
    }

    // ------------------------------------------------------------------
    // DOM capture
    // ------------------------------------------------------------------

    @Test
    void domCaptureReadsItemLines() {
        FakeDom dom = new FakeDom()
                .with(DESCRIPTIONS, 0, "PREPAY CA #02", 1, "WOODSTOCK SINGLE T")
                .with(AMOUNTS, 0, "10.00", 1, "9.60")
                .with(QUANTITIES, 1, "1");
        Receipt receipt = new DomReceiptCapture(dom, FAMILIES).capture();

        assertEquals(
                List.of("PREPAY CA #02", "WOODSTOCK SINGLE T"),
                receipt.lines().stream().map(ReceiptLine::description).toList());
        assertEquals("1", receipt.lines().get(1).quantity());
        assertEquals(Source.DOM, receipt.source());
    }

    @Test
    void domCaptureIsOneQueryPerFamilyNotOnePerRow() {
        // Probing index 0, 1, 2 until one is missing costs a timeout per miss (DESIGN.md 4.2 rule 2).
        Object[] forty = new Object[80];
        for (int i = 0; i < 40; i++) {
            forty[2 * i] = i;
            forty[2 * i + 1] = "ITEM " + i;
        }
        FakeDom dom = new FakeDom().with(DESCRIPTIONS, forty);
        Receipt receipt = new DomReceiptCapture(dom, FAMILIES).capture();

        assertEquals(40, receipt.lines().size());
        assertEquals(4, dom.queries.size(), dom.queries.toString());
    }

    @Test
    void rowIndexComesFromTheElementIdNotFromDomOrder() {
        // A gap in the DOM must not shift every line below it: the DESIGN.md 3.3 failure class.
        FakeDom dom = new FakeDom()
                .with(DESCRIPTIONS, 5, "FIFTH", 0, "ZEROTH", 2, "SECOND")
                .with(AMOUNTS, 0, "1.00", 2, "2.00", 5, "5.00");
        Receipt receipt = new DomReceiptCapture(dom, FAMILIES).capture();

        assertEquals(List.of(0, 2, 5), receipt.lines().stream().map(ReceiptLine::index).toList());
        assertEquals(
                List.of("ZEROTH", "SECOND", "FIFTH"),
                receipt.lines().stream().map(ReceiptLine::description).toList());
        assertEquals("5.00", receipt.lines().get(2).amount());
    }

    @Test
    void domCaptureNormalisesTheLayoutPadding() {
        String padded = NBSP + NBSP + "PREPAY" + FIGURE_SPACE + " CA #02  ";
        FakeDom dom = new FakeDom().with(DESCRIPTIONS, 0, padded);
        Receipt receipt = new DomReceiptCapture(dom, FAMILIES).capture();
        assertEquals("PREPAY CA #02", receipt.lines().get(0).description());
    }

    @Test
    void emptyDomIsAnErrorNotAnEmptyReceipt() {
        // An empty snapshot would compare equal to an empty baseline and pass.
        ReceiptCaptureException thrown = assertThrows(
                ReceiptCaptureException.class,
                () -> new DomReceiptCapture(new FakeDom(), FAMILIES).capture());
        assertTrue(thrown.getMessage().contains("no ReceiptView_"), thrown.getMessage());
    }

    @Test
    void domCaptureReportsThatItHasNoHeaderTotalMopOrFooter() {
        // DESIGN.md 5.3 and 14 Q3: item lines are proven, the rest is not confirmed. The capture
        // must not present a partial receipt as a whole one.
        FakeDom dom = new FakeDom()
                .with(DESCRIPTIONS, 0, "PREPAY CA #02")
                .with(AMOUNTS, 0, "10.00");
        Receipt receipt = new DomReceiptCapture(dom, FAMILIES).capture();

        assertEquals(Set.of("items"), receipt.coverage());
        assertFalse(receipt.coverage().contains("header"));
        assertFalse(receipt.coverage().contains("total"));
    }

    // ------------------------------------------------------------------
    // Serial capture
    // ------------------------------------------------------------------

    @Test
    void serialCaptureKeepsThePrintedLayout() {
        // Blank lines and column spacing are receipt content; the snapshot compares them.
        FakePrinter printer = new FakePrinter(List.of(
                List.of("WELCOME TO", "", "   CHARITY                 1        0.51")));
        Receipt receipt = serial(printer, List.of()).capture();

        assertEquals(Source.SERIAL, receipt.source());
        assertEquals("WELCOME TO\n\n   CHARITY                 1        0.51", receipt.text());
    }

    @Test
    void serialCapturePicksTheSalesReceiptNotAnEarlierSpooledOne() {
        // The Commander commits the sale after the capture is made, so the list starts empty. The
        // spooled print (1010005) is older than the sale's (1010010), so it is passed over.
        FakePrinter printer = new FakePrinter(List.of(
                List.of("TRAN#1010005"),
                List.of("TRAN#1010010")));
        List<Long> committed = new ArrayList<>();
        SerialReceiptCapture capture = serial(printer, committed);
        committed.add(1010010L);

        assertEquals("TRAN#1010010", capture.capture().text());
    }

    @Test
    void reportsAreSkippedUnlessTheCaseKeysThem() {
        FakePrinter printer = new FakePrinter(List.of(List.of("PLU NOT FOUND")));

        SerialReceiptCapture noReports = serial(printer, List.of());
        noReports.setReports(false);
        noReports.setTimeout(Duration.ZERO);
        assertThrows(ReceiptWaitException.class, noReports::capture);

        SerialReceiptCapture withReports = serial(printer, List.of());
        assertEquals("PLU NOT FOUND", withReports.capture().text());
    }

    @Test
    void aTimeoutCallsCheckAliveBeforeItFails() {
        FakePrinter printer = new FakePrinter(List.of());
        SerialReceiptCapture capture = serial(printer, List.of());
        capture.setTimeout(Duration.ZERO);

        ReceiptWaitException thrown = assertThrows(ReceiptWaitException.class, capture::capture);
        assertFalse(thrown.deadEnd());
        assertEquals(1, printer.alive);
    }

    @Test
    void anAbortReasonEndsTheWaitAsADeadEnd() {
        SerialReceiptCapture capture = serial(new FakePrinter(List.of()), List.of());
        capture.setAbort(() -> "the printer dropped off Windows");

        ReceiptWaitException thrown = assertThrows(ReceiptWaitException.class, capture::capture);
        assertTrue(thrown.deadEnd());
        assertEquals("the printer dropped off Windows", thrown.getMessage());
    }

    @Test
    void theSourcesSatisfyTheSameSeam() {
        assertInstanceOf(ReceiptSource.class, new DomReceiptCapture(new FakeDom(), FAMILIES));
        assertInstanceOf(ReceiptSource.class, serial(new FakePrinter(List.of()), List.of()));
        assertInstanceOf(ReceiptSource.class, new StaticReceiptSource(""));
    }

    // ------------------------------------------------------------------
    // Text parsing
    // ------------------------------------------------------------------

    @Test
    void parsesARealCorpusReceipt() {
        Receipt receipt = ReceiptParser.parse(SAMPLE_RECEIPT, Source.SERIAL);
        assertEquals(Source.SERIAL, receipt.source());

        Map<String, ReceiptLine> byDescription = receipt.lines().stream()
                .filter(line -> !line.description().isEmpty())
                .collect(Collectors.toMap(ReceiptLine::description, line -> line, (a, b) -> a));
        assertEquals("10.00", byDescription.get("PREPAY CA #02").amount());
        assertEquals("9.60", byDescription.get("WOODSTOCK SINGLE T").amount());
        assertEquals("1", byDescription.get("WOODSTOCK SINGLE T").quantity());
        assertEquals("19.60", byDescription.get("Subtotal").amount());
        assertEquals("-0.15", byDescription.get("DISCOUNT").amount());
    }

    @Test
    void blankLinesAreDroppedAndTrailingWhitespaceDoesNotMatter() {
        Receipt receipt = ReceiptParser.parse(SAMPLE_RECEIPT, Source.SERIAL);
        assertTrue(receipt.lines().stream().allMatch(line -> !line.text().isEmpty()));
        assertTrue(receipt.lines().stream().anyMatch(line -> line.text().equals("WELCOME TO")));
        ReceiptLine total = receipt.lines().stream()
                .filter(line -> line.text().startsWith("TOTAL"))
                .findFirst()
                .orElseThrow();
        assertFalse(total.text().endsWith(" "));
    }

    @Test
    void noSemanticMaskingHappensHere() {
        // Dates, STANs and cashier names are DESIGN.md 5.4's job, declared once, globally.
        Receipt receipt = ReceiptParser.parse(SAMPLE_RECEIPT, Source.SERIAL);
        assertTrue(receipt.text().contains("Stan: 000412893"), receipt.text());
    }

    @Test
    void crlfAndLfCapturesOfTheSameReceiptCompareEqual() {
        assertEquals(
                ReceiptParser.parse(SAMPLE_RECEIPT, Source.SERIAL).text(),
                ReceiptParser.parse(SAMPLE_RECEIPT.replace("\n", "\r\n"), Source.SERIAL).text());
    }

    @Test
    void staticSourceParsesOnce() {
        StaticReceiptSource source = new StaticReceiptSource(SAMPLE_RECEIPT);
        assertSame(source.capture(), source.capture());
    }

    @Test
    void transactionNumberIsTheDigitsAfterTheLastHash() {
        assertEquals(
                1010009L,
                ReceiptParser.transactionNumber(List.of("ST#CL120 DR#1 TRAN#1010009")));
        assertNull(ReceiptParser.transactionNumber(List.of("WELCOME TO", "THANK YOU")));
    }

    // ------------------------------------------------------------------
    // Receipt value type
    // ------------------------------------------------------------------

    @Test
    void receiptTextOrdersTheSections() {
        Receipt receipt = new Receipt(
                List.of(ReceiptLine.item(0, "ITEM", "1.00")),
                Source.DOM,
                List.of("WELCOME TO"),
                List.of("THANK YOU"),
                "TOTAL 1.00",
                List.of("CASH $ 1.00"));
        assertEquals(
                List.of("WELCOME TO", "ITEM  1.00", "TOTAL 1.00", "CASH $ 1.00", "THANK YOU"),
                List.of(receipt.text().split("\n")));
        assertEquals(Set.of("header", "items", "total", "mop", "footer"), receipt.coverage());
    }

    @Test
    void receiptLinePrefersItsRawFormWhenPresent() {
        ReceiptLine line = new ReceiptLine(0, "ITEM", "1.00", "", "ITEM        1.00");
        assertEquals("ITEM        1.00", line.text());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SerialReceiptCapture serial(FakePrinter printer, List<Long> committed) {
        return new SerialReceiptCapture(printer, () -> committed);
    }

    private static String sampleReceipt() {
        try (InputStream in = ReceiptCaptureTest.class.getResourceAsStream(
                "/pos/receipt/PETROCORE-121630-Receipt.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "missing fixture pos/receipt/PETROCORE-121630-Receipt.txt");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
