package com.verifone.cat.devices.printer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pos.receipt.ReceiptCaptureException;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.pos.receipt.ReceiptWait;
import com.verifone.cat.pos.receipt.ReceiptWaitException;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The per-case printed receipt: nothing before a case begins, a case's own wait, and a Commander read that fails. */
class PrinterReceiptsTest {

    private static final Duration SHORT = Duration.ofMillis(50);

    @Test
    void aCaptureNeverBegunFailsByName() {
        PrinterReceipts receipts = new PrinterReceipts(new SilentPrinter(), List::of, SHORT);
        assertThrows(ReceiptCaptureException.class, receipts::capture);
    }

    @Test
    void aCommanderThatCannotBeReadAtTheStartFailsTheCaseByName() {
        PrinterReceipts receipts = new PrinterReceipts(new SilentPrinter(), () -> {
            throw new DeviceUnreachable("the Commander", "refused");
        }, SHORT);
        receipts.begin();
        ReceiptCaptureException failed = assertThrows(ReceiptCaptureException.class, receipts::capture);
        assertTrue(failed.getMessage().contains("refused"), failed.getMessage());
    }

    @Test
    void aBegunCaptureWaitsOnlyForItsOwnTimeout() {
        PrinterReceipts receipts = new PrinterReceipts(new SilentPrinter(), List::of, SHORT);
        receipts.begin();
        assertThrows(ReceiptWaitException.class, receipts::capture);
    }

    @Test
    void aTunedCaptureRefusesAReportAndPutsItsSettingsBackAfter() {
        List<List<String>> prints = List.of(List.of("MANUAL ADJUSTMENT", "PLU ITEM NOT FOUND"));
        ReceiptPrinter slipOnly = new ReceiptPrinter() {
            @Override
            public int receiptCount() {
                return 0;
            }

            @Override
            public List<List<String>> receiptsSince(int index) {
                return prints.subList(Math.min(index, prints.size()), prints.size());
            }
        };
        PrinterReceipts receipts = new PrinterReceipts(slipOnly, List::of, SHORT);
        receipts.begin();
        ReceiptWait noReports = new ReceiptWait(SHORT, false, -1, Duration.ZERO, false, -1, 0, null);
        assertThrows(ReceiptWaitException.class, () -> receipts.capture(noReports));
        // The source's own setting is back: an untuned capture may take a no-TRAN# print, as the pytest suites do.
        assertTrue(receipts.capture().text().contains("PLU ITEM NOT FOUND"));
        assertEquals(0, receipts.printCount());
    }

    /** A printer that never prints: every capture waits and gives up. */
    private static final class SilentPrinter implements ReceiptPrinter {

        @Override
        public int receiptCount() {
            return 0;
        }

        @Override
        public List<List<String>> receiptsSince(int index) {
            return List.of();
        }
    }
}
