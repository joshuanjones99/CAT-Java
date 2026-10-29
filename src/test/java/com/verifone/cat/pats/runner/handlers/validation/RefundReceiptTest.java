package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Page;
import com.verifone.cat.devices.printer.PrinterReceipts;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.HandlerFakes;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;
import com.verifone.cat.pats.runner.handlers.sale.LastTlog;
import com.verifone.cat.pats.runner.handlers.sale.SaleContext;
import com.verifone.cat.pats.runner.handlers.sale.SaleFamily;
import com.verifone.cat.pats.runner.handlers.sale.SaleScreen;
import com.verifone.cat.pats.runner.handlers.sale.SaleTiming;
import com.verifone.cat.pats.runner.handlers.sale.ScannerData;
import com.verifone.cat.pats.runner.handlers.sale.TouchPacer;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.pages.Pos;
import com.verifone.cat.pos.receipt.StaticReceiptSource;
import com.verifone.cat.report.CaseRecord;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PETROCORE-36853's refund with a transaction map, over a fake serial printer through the run's real
 * {@link PrinterReceipts} and the production {@link SaleScreen} receipt read.
 *
 * <p>The live evidence (CAT-Java run {@code dec5794107fa}, 2026-10-28): the sale family's receipt read was a bare
 * {@code Pos}'s DOM capture, the refund ticket on screen, which has no {@code TRAN#} and no {@code Stan:}. With no
 * TLog validated earlier in the case, REFUND_WITH_TRANS_MAP keyed VOID_TICKET, the refund never posted, the receipt
 * and TLog checks read the original sale, and the next case found the ticket open. The Python reads
 * {@code run.pos.receipt()}, the run's printed receipt: the sale's, with its STAN. Fake-tested only.
 */
class RefundReceiptTest {

    private static final String KEY = "PETROCORE-36853";
    private static final String NAME = KEY + "-Receipt.txt";
    private static final long SALE_NUMBER = 1_010_015L;
    private static final long REFUND_NUMBER = 1_010_016L;
    private static final String STAN = "0003717";

    private static final List<String> SALE = List.of("WELCOME TO", "VERIFONE", "",
            "   PLU-AA                  2       20.00", "TOTAL     39.16", "SALE Receipt", "AMEX   USD$39.16",
            "Stan: " + STAN, "THANK YOU FOR", "ST#CL120               DR#1 TRAN#" + SALE_NUMBER);
    private static final List<String> REFUND = List.of("WELCOME TO", "VERIFONE", "", "R E F U N D", "",
            "R  PLU-AA                  2      -20.00", "TOTAL    -39.16", "AMEX   USD-$39.16",
            "Stan: 0003720", "THANK YOU FOR", "ST#CL120               DR#1 TRAN#" + REFUND_NUMBER);

    private static final SaleTiming FAST = new SaleTiming(Duration.ofMillis(200), Duration.ofMillis(200),
            Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(200), Duration.ofSeconds(1),
            Duration.ofMillis(10));

    @TempDir
    Path folder;

    private final ReceiptJobPickTest.FakePrinter printer = new ReceiptJobPickTest.FakePrinter();
    private final List<Long> committed = new CopyOnWriteArrayList<>();
    private final PrinterReceipts receipts = new PrinterReceipts(printer, () -> List.copyOf(committed),
            Duration.ofSeconds(5));
    private final HandlerFakes.Rmi rmi = new HandlerFakes.Rmi();

    /** A page that refuses every call: the production reader must not reach it for the receipt. */
    private static PosDriver refusingDriver() {
        Page refusing = (Page) Proxy.newProxyInstance(Page.class.getClassLoader(), new Class<?>[] {Page.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("no browser in this test: Page." + method.getName());
                });
        return PosDriver.builder(refusing).registerId("local").settleOverlays(false).build();
    }

    /**
     * The register's screen: the STAN prompt up, as the register raises it once the refund's card is read. Its
     * receipt is the production reader's, over the run's printed receipts.
     */
    private SaleScreen screen() {
        SaleScreen production = SaleScreen.of(new Pos(refusingDriver()), receipts);
        HandlerFakes.Screen prompt = new HandlerFakes.Screen();
        prompt.overlay = Map.of("TITLE", "", "LINE1", "ENTER STAN", "LINE2", "", "LINE3", "");
        return new SaleScreen() {
            @Override
            public String screenText() {
                return prompt.screenText();
            }

            @Override
            public boolean settled(String before, Duration cap) {
                return prompt.settled(before, cap);
            }

            @Override
            public Map<String, String> overlay() {
                return prompt.overlay();
            }

            @Override
            public List<String> expressLabels() {
                return prompt.expressLabels();
            }

            @Override
            public void clearBlockingOverlays() {
                prompt.clearBlockingOverlays();
            }

            @Override
            public String receiptText() {
                return production.receiptText();
            }
        };
    }

    private CaseRun run(RunnableCase kase) {
        ActionRegistry registry = new ActionRegistry();
        SaleFamily.register(registry, new SaleContext(screen(), rmi, FuelPumps.UNAVAILABLE, ScannerData.UNAVAILABLE,
                LastTlog.VALIDATED, d -> { }, new TouchPacer(d -> { }, FAST.touchSettle()), FAST));
        // The original sale's tender: it commits and prints.
        registry.register("TOUCH_EVENT", (r, a) -> {
            committed.add(SALE_NUMBER);
            printer.print(SALE);
        });
        // The register posts the refund once it has been keyed the original sale's STAN, and only then.
        registry.register("INSERT_OR_SLIDE_CARD", (r, a) -> {
            if (rmi.calls.contains("enter:" + STAN)) {
                committed.add(REFUND_NUMBER);
                printer.print(REFUND);
            }
        });
        ValidationContext ctx = context(receipts, new ValidationKit.FakePos(), new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), new ValidationKit.FakeReferences(), noEnv());
        ValidationFamily.register(registry, ctx);
        registry.onBegin(r -> receipts.begin());
        CaseRecord record = new CaseRecord("test::" + kase.key(), kase.key(), folder.resolve(kase.key()),
                Instant.now());
        return new CaseRun(kase, record, registry, name -> {
            throw new IllegalStateException("no fixture " + name.key() + " in this test");
        }, EnumSet.of(FixtureName.POS), new RunState()).withBaselineDir(folder).withUpdate(false);
    }

    @Test
    void theRefundKeysTheStanOfThePrintedSaleAndItsReceiptIsTheRefunds() throws IOException {
        Files.writeString(folder.resolve(KEY + "-CATReceipt.txt"), String.join("\n", REFUND) + "\n",
                StandardCharsets.UTF_8);
        CaseRun run = run(kase(KEY,
                action(0, "STORE_TRANS_MAP", "REFUND_KEY", "REF"),
                action(1, "TOUCH_EVENT", "WEB ELEMENT", "tender", "TYPE[ID/XPATH]", "ID"),
                action(2, "REFUND_WITH_TRANS_MAP", "REFUND_KEY", "REF"),
                action(3, "INSERT_OR_SLIDE_CARD", "CARD_NAME", "AMEXCARD", "DEVICE", "VIPERPAY"),
                action(4, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)));

        assertEquals("", verdict(run));
        assertEquals(List.of("enter:" + STAN), rmi.calls, "the STAN, not VOID_TICKET");
        String captured = Files.readString(folder.resolve(KEY).resolve("receipt.txt"), StandardCharsets.UTF_8);
        assertTrue(captured.contains("R E F U N D"), captured);
        assertFalse(captured.contains("TRAN#" + SALE_NUMBER), captured);
    }

    /** The Python's {@code _validate_receipt} binds a waiting STORE_TRANS_MAP key to the receipt it compared. */
    @Test
    void aReceiptValidationBindsTheWaitingRefundKey() throws IOException {
        Files.writeString(folder.resolve(KEY + "-CATReceipt.txt"), String.join("\n", SALE) + "\n",
                StandardCharsets.UTF_8);
        CaseRun run = run(kase(KEY,
                action(0, "STORE_TRANS_MAP", "REFUND_KEY", "REF"),
                action(1, "TOUCH_EVENT", "WEB ELEMENT", "tender", "TYPE[ID/XPATH]", "ID"),
                action(2, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)));

        assertEquals("", verdict(run));
        Map<String, AbstractMap.SimpleImmutableEntry<String, String>> map = run.scratch(ScratchKey.TRANS_MAP).orElseThrow();
        assertEquals(new AbstractMap.SimpleImmutableEntry<>(String.valueOf(SALE_NUMBER), STAN), map.get("REF"));
        assertTrue(run.scratch(ScratchKey.TRANS_MAP_KEY).isEmpty(), "the key is bound, not left waiting");
    }

    /** The bare reader's receipt is the page's (the DOM capture): the read the refund made before this fix. */
    @Test
    void theProductionReaderTakesTheReceiptFromTheRunsSourceNotThePage() {
        PosDriver driver = refusingDriver();

        assertEquals("TRAN# 7 Stan: 8",
                SaleScreen.of(new Pos(driver), new StaticReceiptSource("TRAN# 7 Stan: 8")).receiptText().strip());
        assertThrows(RuntimeException.class, () -> SaleScreen.of(new Pos(driver)).receiptText());
    }
}
