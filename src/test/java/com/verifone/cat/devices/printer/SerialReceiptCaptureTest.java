package com.verifone.cat.devices.printer;

import static com.verifone.cat.devices.printer.PrinterFixtures.FakePort;
import static com.verifone.cat.devices.printer.PrinterFixtures.PRINT_JOB;
import static com.verifone.cat.devices.printer.PrinterFixtures.SNAPSHOT;
import static com.verifone.cat.devices.printer.PrinterFixtures.job;
import static com.verifone.cat.devices.printer.PrinterFixtures.replace;
import static com.verifone.cat.devices.printer.PrinterFixtures.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptWaitException;
import com.verifone.cat.pos.receipt.SerialReceiptCapture;
import com.verifone.cat.pos.receipt.Source;
import com.verifone.cat.validate.Normalisers;

/**
 * {@link SerialReceiptCapture} over a {@link TopazPrinter} on an in-memory port. Selection is by
 * {@code TRAN#}, as PATS's {@code ReceiptValidator} selects. Ported from the Python
 * {@code TestSerialReceiptCapture}; the timing-only cases keep their Python bounds.
 */
class SerialReceiptCaptureTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** A {@code committed} list the test can grow, as the Commander's transaction list grows. */
    private static List<Long> committed(long... numbers) {
        List<Long> out = new CopyOnWriteArrayList<>();
        for (long number : numbers) {
            out.add(number);
        }
        return out;
    }

    private static String text(Receipt receipt) {
        return Normalisers.normaliseReceipt(receipt.text()).replaceAll("\n+$", "");
    }

    @Test
    void aSpooledReceiptArrivingDuringTheTestIsNotTheSale() {
        // 61348, HANDOFF.md 1.42: a VOID TICKET spooled while COM1 was closed was read.
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            port.send(job(1010007L)); // spooled; arrives after the test began
            until(() -> printer.receiptCount() > 0, WAIT);
            committed.add(1010009L);
            port.send(PRINT_JOB);
            Receipt receipt = capture.capture();
            assertEquals(Source.SERIAL, receipt.source());
            assertEquals(SNAPSHOT, text(receipt));
        }
    }

    @Test
    void theSaleIsNotPickedBeforeTheCommanderHasIt() {
        // Until the Commander's newest trSeq moves past the test's start, a print with the old newest
        // number is an earlier transaction's, however late it arrives.
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> List.of(1010008L));
            capture.setTimeout(Duration.ofMillis(1500));
            port.send(job(1010008L));
            until(() -> printer.receiptCount() > 0, WAIT);
            assertThrows(ReceiptWaitException.class, capture::capture);
        }
    }

    @Test
    void aPrepayCompletionOnTheCommanderDoesNotHideTheSale() {
        // HANDOFF.md 1.62: the Commander records a prepay's fuel completion as POS 901 (9010001),
        // above every Reg101 number. Taking the highest across both series matched nothing.
        List<Long> committed = committed(1010008L, 9010001L);
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            committed.add(1010009L);
            port.send(PRINT_JOB);
            assertTrue(capture.capture().text().contains("THANK YOU"));
        }
    }

    @Test
    void ofTwoCopiesTheNewestIsPicked() {
        // PATS stores a repeated TRAN# as <tran>_1 and validates that (61343).
        byte[] merchant = replace(PRINT_JOB, "THANK YOU", "MERCHANT ");
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            committed.add(1010009L);
            port.send(merchant);
            port.send(PRINT_JOB);
            until(() -> printer.receiptsSince(0).size() == 2, WAIT);
            assertTrue(capture.capture().text().contains("THANK YOU"));
        }
    }

    @Test
    void aPortThatDropsOffWindowsMidRunIsNamedAndStaysLost() {
        // The adapter drops off Windows; only a replug fixes it. The timeout asks the OS whether the
        // port is still listed, and the loss stays found for every later call.
        List<String> listed = new CopyOnWriteArrayList<>(List.of("COM1"));
        FakePort port = new FakePort();
        try (TopazPrinter printer = new PrinterOn(port, listed).printer()) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> List.<Long>of());
            capture.setTimeout(Duration.ofMillis(300));
            assertThrows(ReceiptWaitException.class, capture::capture); // still listed: a plain timeout
            listed.clear();
            DeviceUnreachable lost = assertThrows(DeviceUnreachable.class, printer::checkAlive);
            assertTrue(lost.getMessage().contains("unplug and replug"), lost.getMessage());
            listed.add("COM1"); // replugged: this handle is still dead
            assertThrows(DeviceUnreachable.class, () -> printer.receiptsSince(0));
        }
    }

    @Test
    void aReceiptTimeoutOnALostPortRaisesDeviceUnreachable() {
        FakePort port = new FakePort();
        try (TopazPrinter printer = new PrinterOn(port, new CopyOnWriteArrayList<>()).printer()) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> List.<Long>of());
            capture.setTimeout(Duration.ofMillis(300));
            DeviceUnreachable lost = assertThrows(DeviceUnreachable.class, capture::capture);
            assertTrue(lost.getMessage().contains("no longer listed"), lost.getMessage());
        }
    }

    @Test
    void aPrintWithNoTranIsAReport() {
        byte[] report = replace(PRINT_JOB, "TRAN#1010009", "PERIOD 1    ");
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> List.of(1010009L));
            capture.setTimeout(Duration.ofSeconds(5));
            port.send(report);
            assertTrue(capture.capture().text().contains("PERIOD 1"));
        }
    }

    @Test
    void outsideAReportCaseAPrintWithNoTranIsPassedOver() throws Exception {
        // 36850: a PLU-not-found slip printed just before the sale's receipt, and was taken for it.
        byte[] slip = replace(replace(PRINT_JOB, "TRAN#1010009", "PLU NOT FOUND"), "THANK YOU", "SLIP     ");
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(daemon());
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            capture.setReports(false);
            port.send(slip);
            until(() -> printer.receiptCount() > 0, WAIT);
            later.schedule(() -> {
                committed.add(1010009L);
                port.send(PRINT_JOB);
            }, 500, TimeUnit.MILLISECONDS);
            assertTrue(capture.capture().text().contains("THANK YOU"));
        } finally {
            later.shutdownNow();
        }
    }

    @Test
    void aPrintFromBeforeTheLastActionWaitsForANewerSale() throws Exception {
        // 36993: a sale, then a refund; the sale's receipt was taken before the refund committed.
        byte[] sale = replace(PRINT_JOB, "THANK YOU", "THE SALE ");
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(daemon());
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            committed.add(1010009L);
            port.send(sale);
            until(() -> printer.receiptCount() > 0, WAIT);
            capture.setFreshFrom(printer.receiptCount(), Duration.ofSeconds(3)); // the tender
            later.schedule(() -> {
                committed.add(1010010L);
                port.send(job(1010010L));
            }, 500, TimeUnit.MILLISECONDS);
            assertTrue(capture.capture().text().contains("THANK YOU"));
        } finally {
            later.shutdownNow();
        }
    }

    @Test
    void aLatePrintOfAnEarlierSaleWaitsForTheNewest() throws Exception {
        // 36993: the sale's receipt printed after the refund's tender began, so it was "fresh".
        byte[] sale = replace(PRINT_JOB, "THANK YOU", "THE SALE ");
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(daemon());
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            capture.setFreshFrom(printer.receiptCount(), Duration.ofSeconds(3));
            capture.setSettleAll(true);
            committed.add(1010009L);
            port.send(sale); // the sale's receipt, late, after the mark
            until(() -> printer.receiptCount() > 0, WAIT);
            later.schedule(() -> {
                committed.add(1010010L);
                port.send(job(1010010L));
            }, 500, TimeUnit.MILLISECONDS);
            Receipt receipt = capture.capture();
            assertTrue(receipt.text().contains("THANK YOU"));
            assertTrue(receipt.text().contains("TRAN#1010010"));
        } finally {
            later.shutdownNow();
        }
    }

    @Test
    void aSettleEndsOnceEveryOwedReceiptHasPrinted() throws Exception {
        // 36993: the sale's receipt printed 8.7 s late, just before the refund's. Once both owed
        // prints are in, the newest is taken without waiting out the grace.
        byte[] sale = replace(PRINT_JOB, "THANK YOU", "THE SALE ");
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(daemon());
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(10));
            capture.setSettleOwed(printer.receiptCount(), 2);
            capture.setFreshFrom(printer.receiptCount(), Duration.ofSeconds(5));
            capture.setSettleAll(true);
            committed.add(1010009L);
            port.send(sale);
            until(() -> printer.receiptCount() > 0, WAIT);
            later.schedule(() -> {
                committed.add(1010010L);
                port.send(job(1010010L));
            }, 500, TimeUnit.MILLISECONDS);
            long started = System.nanoTime();
            Receipt receipt = capture.capture();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(receipt.text().contains("TRAN#1010010"));
            assertTrue(elapsedMillis < 2500, "settled after " + elapsedMillis + " ms");
        } finally {
            later.shutdownNow();
        }
    }

    @Test
    void aPrintFromTheSaleStillOpenIsTheReceipt() {
        // 37073: a card decline prints mid-sale; the sale commits after its cash tender.
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            port.send(job(1010009L)); // not on the Commander yet
            assertTrue(capture.capture().text().contains("TRAN#1010009"));
        }
    }

    @Test
    void withNoNewerSaleTheOlderPrintIsTakenAfterTheGrace() {
        List<Long> committed = committed(1010008L);
        FakePort port = new FakePort();
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, () -> committed);
            capture.setTimeout(Duration.ofSeconds(5));
            committed.add(1010009L);
            port.send(PRINT_JOB);
            until(() -> printer.receiptCount() > 0, WAIT);
            capture.setFreshFrom(printer.receiptCount(), Duration.ofMillis(500));
            long started = System.nanoTime();
            Receipt receipt = capture.capture();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(receipt.text().contains("THANK YOU"));
            assertTrue(elapsedMillis >= 450, "taken after " + elapsedMillis + " ms");
        }
    }

    private static java.util.concurrent.ThreadFactory daemon() {
        return runnable -> {
            Thread thread = new Thread(runnable, "test-delay");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Builds a printer on a port with an injected, mutable OS listing. */
    private record PrinterOn(FakePort port, List<String> listed) {
        TopazPrinter printer() {
            return new TopazPrinter(port, "COM1", () -> listed);
        }
    }
}
