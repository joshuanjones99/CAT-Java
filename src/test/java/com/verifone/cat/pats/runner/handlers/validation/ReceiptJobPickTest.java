package com.verifone.cat.pats.runner.handlers.validation;

import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.action;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.context;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.kase;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.noEnv;
import static com.verifone.cat.pats.runner.handlers.validation.ValidationKit.verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.printer.PrinterReceipts;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.RunState;
import com.verifone.cat.pats.runner.RunnableCase;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.report.CaseRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which print job a {@code VALIDATE_RECEIPT} compares, over a fake serial printer through the run's real
 * {@link PrinterReceipts}: the Python's {@code validation._pos_receipt} rules on {@code SerialReceiptCapture}.
 *
 * <p>The live evidence (CAT-Java run {@code f6b5c0596966}, 2026-10-28): PETROCORE-36850 keys a PLU the POS does not
 * know, which prints a "MANUAL ADJUSTMENT / PLU ITEM NOT FOUND" slip mid-sale, with no {@code TRAN#}; the sale's
 * receipt prints after its tender. The Java took the slip, because it let any no-{@code TRAN#} print be the receipt;
 * the Python takes one only in a case that prints a report ({@code REPORT_ACTIONS}). Fake-tested only.
 */
class ReceiptJobPickTest {

    private static final String KEY = "PETROCORE-36850";
    private static final String NAME = KEY + "-Receipt.txt";
    private static final long SALE_NUMBER = 1_012_470L;

    private static final List<String> SLIP = List.of("MANUAL ADJUSTMENT", "PLU ITEM NOT FOUND", "",
            "   Description           Qty      Amount", "   00000000091138000       1       15.00",
            "   DEPT: 9911 PLU-NOT-FOUND", "", "ITEM DESCRIPTION");
    private static final List<String> SALE = List.of("WELCOME TO", "VERIFONE", "",
            "   00000000091138000       1       15.00", "                  FOODSTAMP  $      7.00",
            "                       CASH  $     10.00", "THANK YOU FOR",
            "ST#CL120               DR#1 TRAN#" + SALE_NUMBER);
    private static final List<String> REPORT = List.of("DEPARTMENT REPORT", "", "TOTAL     15.00");

    @TempDir
    Path folder;

    /**
     * The register's printer: a print is either on the cable now, or held until the capture has looked
     * {@code lateBy} more times, as the sale's receipt arrives a moment after its tender.
     */
    static final class FakePrinter implements ReceiptPrinter {
        final List<List<String>> prints = new CopyOnWriteArrayList<>();
        List<String> pending;
        int lateBy;

        void print(List<String> lines) {
            prints.add(lines);
        }

        void printLate(List<String> lines, int looks) {
            pending = lines;
            lateBy = looks;
        }

        @Override
        public int receiptCount() {
            return prints.size();
        }

        @Override
        public List<List<String>> receiptsSince(int index) {
            if (pending != null && lateBy-- <= 0) {
                prints.add(pending);
                pending = null;
            }
            return new ArrayList<>(prints.subList(Math.min(index, prints.size()), prints.size()));
        }
    }

    private final FakePrinter printer = new FakePrinter();
    private final List<Long> committed = new CopyOnWriteArrayList<>();
    private final PrinterReceipts receipts = new PrinterReceipts(printer, () -> List.copyOf(committed),
            Duration.ofSeconds(10));

    /** A case over the fake printer: the validation family, the run's per-case capture, and the stub POS actions. */
    private CaseRun run(RunnableCase kase, ActionRegistry registry) {
        ValidationContext ctx = context(receipts, new ValidationKit.FakePos(), new ValidationKit.FakeCommander(),
                new ValidationKit.FakePackets(), new ValidationKit.FakeReferences(), noEnv());
        ValidationFamily.register(registry, ctx);
        registry.onBegin(run -> receipts.begin());
        Set<FixtureName> declared = EnumSet.of(FixtureName.POS);
        CaseRecord record = new CaseRecord("test::" + kase.key(), kase.key(), folder.resolve(kase.key()),
                Instant.now());
        return new CaseRun(kase, record, registry, name -> {
            throw new IllegalStateException("no fixture " + name.key() + " in this test");
        }, declared, new RunState()).withBaselineDir(folder).withUpdate(false);
    }

    private void baseline(List<String> lines) throws IOException {
        Files.writeString(folder.resolve(KEY + "-CATReceipt.txt"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
    }

    private String captured(CaseRun run) throws IOException {
        return Files.readString(folder.resolve(KEY).resolve("receipt.txt"), StandardCharsets.UTF_8);
    }

    @Test
    void anInterimSlipThenTheSaleReceiptComparesTheSaleReceipt() throws IOException {
        ActionRegistry registry = new ActionRegistry();
        registry.register("SCAN_ITEM", (r, a) -> printer.print(SLIP)); // the PLU-not-found slip, mid-sale
        registry.register("TOUCH_EVENT", (r, a) -> {
            committed.add(SALE_NUMBER);
            printer.printLate(SALE, 2); // the sale's receipt, a moment after its tender
        });
        baseline(SALE);
        CaseRun run = run(kase(KEY, action(0, "SCAN_ITEM", "SELECT_FILE", "PLU-9113.xml"),
                action(1, "TOUCH_EVENT", "WEB ELEMENT", "tender", "TYPE[ID/XPATH]", "ID"),
                action(2, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), registry);

        assertEquals("", verdict(run));
        assertTrue(run.record().validations().get(0).passed());
        String text = captured(run);
        assertTrue(text.contains("THANK YOU FOR"), text);
        assertFalse(text.contains("PLU ITEM NOT FOUND"), text);
    }

    /**
     * The slip printed by the case's last POS action itself, so the fresh-print mark cannot hold it back: only the
     * Python's report rule (no {@code TRAN#} print outside a report case) keeps it from being taken.
     */
    @Test
    void aSlipPrintedByTheLastPosActionIsNeverTheReceiptOfACaseWithNoReport() throws IOException {
        ActionRegistry registry = new ActionRegistry();
        registry.register("TOUCH_EVENT", (r, a) -> {
            printer.print(SLIP);
            committed.add(SALE_NUMBER);
            printer.printLate(SALE, 2);
        });
        baseline(SALE);
        CaseRun run = run(kase(KEY, action(0, "TOUCH_EVENT", "WEB ELEMENT", "tender", "TYPE[ID/XPATH]", "ID"),
                action(1, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), registry);

        assertEquals("", verdict(run));
        String text = captured(run);
        assertTrue(text.contains("THANK YOU FOR"), text);
        assertFalse(text.contains("PLU ITEM NOT FOUND"), text);
    }

    @Test
    void theSlipAndTheSaleReceiptBothOnTheCableStillCompareTheSaleReceipt() throws IOException {
        ActionRegistry registry = new ActionRegistry();
        registry.register("SCAN_ITEM", (r, a) -> printer.print(SLIP));
        registry.register("TOUCH_EVENT", (r, a) -> {
            committed.add(SALE_NUMBER);
            printer.print(SALE);
        });
        baseline(SALE);
        CaseRun run = run(kase(KEY, action(0, "SCAN_ITEM", "SELECT_FILE", "PLU-9113.xml"),
                action(1, "TOUCH_EVENT", "WEB ELEMENT", "tender", "TYPE[ID/XPATH]", "ID"),
                action(2, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), registry);

        assertEquals("", verdict(run));
        assertTrue(captured(run).contains("THANK YOU FOR"));
    }

    @Test
    void aSingleSaleReceiptIsComparedAndPasses() throws IOException {
        ActionRegistry registry = new ActionRegistry();
        registry.register("TOUCH_EVENT", (r, a) -> {
            committed.add(SALE_NUMBER);
            printer.print(SALE);
        });
        baseline(SALE);
        CaseRun run = run(kase(KEY, action(0, "TOUCH_EVENT", "WEB ELEMENT", "tender", "TYPE[ID/XPATH]", "ID"),
                action(1, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), registry);

        assertEquals("", verdict(run));
        assertTrue(run.record().validations().get(0).passed());
    }

    @Test
    void aCaseThatPrintsAReportTakesItsNoTranPrint() throws IOException {
        ActionRegistry registry = new ActionRegistry();
        registry.register("REPORT_PRINT", (r, a) -> printer.print(REPORT));
        baseline(REPORT);
        CaseRun run = run(kase(KEY, action(0, "REPORT_PRINT", "REPORT", "DEPARTMENT"),
                action(1, "VALIDATE_RECEIPT", "SELECT_FILE", NAME)), registry);

        assertEquals("", verdict(run));
        assertTrue(captured(run).contains("DEPARTMENT REPORT"));
    }

    @Test
    void thePrintMarkIsTakenBeforeEachPosDrivingActionOnly() {
        assertTrue(ValidationFamily.passive("SLEEP"));
        assertTrue(ValidationFamily.passive("VALIDATE_RECEIPT"));
        assertTrue(ValidationFamily.passive("WIDGET_VALIDATION"));
        assertTrue(ValidationFamily.passive("WAIT_FOR_ELEMENT"));
        assertTrue(ValidationFamily.passive("RESET_PACKETS"));
        assertTrue(ValidationFamily.passive("store_trans_map"));
        assertFalse(ValidationFamily.passive("TOUCH_EVENT"));
        assertFalse(ValidationFamily.passive("SCAN_ITEM"));
    }
}
