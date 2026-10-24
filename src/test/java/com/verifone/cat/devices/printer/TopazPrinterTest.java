package com.verifone.cat.devices.printer;

import static com.verifone.cat.devices.printer.PrinterFixtures.FakePort;
import static com.verifone.cat.devices.printer.PrinterFixtures.PRINT_JOB;
import static com.verifone.cat.devices.printer.PrinterFixtures.SNAPSHOT;
import static com.verifone.cat.devices.printer.PrinterFixtures.receiptText;
import static com.verifone.cat.devices.printer.PrinterFixtures.until;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.devices.printer.PrinterPorts.PortInfo;

/**
 * {@link TopazPrinter} over an in-memory port. Ported from the Python {@code TestTopazPrinter},
 * {@code TestReceiptLog} and {@code test_a_pcs_own_com_port_is_not_a_printer_port}. No serial port is
 * opened, and the OS port listing is injected, except the one call that only lists.
 */
class TopazPrinterTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Test
    void readsRepliesAndHandsOverTheReceipt() {
        FakePort port = new FakePort(PrinterFixtures.chunks(PRINT_JOB, 220));
        try (TopazPrinter printer = new TopazPrinter(port, "fake", () -> List.of("fake"))) {
            printer.start();
            until(() -> printer.receiptCount() > 0, WAIT);
            List<List<String>> got = printer.receiptsSince(0);
            assertEquals(1, got.size());
            assertEquals(SNAPSHOT, receiptText(got.get(0)));
            assertEquals(List.of("37 22 20 20 20 23 00"), port.written.stream().map(PrinterFixtures::hex).toList());
        }
        assertTrue(port.closed());
    }

    @Test
    void aDeadPortSaysSo() {
        FakePort port = new FakePort();
        port.fail(new IOException("device reports readiness to read but returned no data"));
        try (TopazPrinter printer = new TopazPrinter(port, "COM1", () -> List.of("COM1"))) {
            printer.start();
            until(() -> lostMessage(printer) != null, WAIT);
            assertTrue(lostMessage(printer).contains("serial read failed"), lostMessage(printer));
        }
    }

    @Test
    void closeReleasesThePortAndIsIdempotent() {
        FakePort port = new FakePort();
        TopazPrinter printer = new TopazPrinter(port, "COM7", () -> List.of("COM7"));
        printer.start();
        printer.close();
        printer.close();
        assertTrue(port.closed());
    }

    @Test
    void theReceiptKeyIsTheSameAsThePythonsForTheSameLines() {
        List<List<String>> receipts = new PrinterProtocol().feed(PRINT_JOB).receipts();
        assertEquals("e8e55f5eb9", TopazPrinter.receiptKey(receipts.get(0)));
    }

    @Test
    void theReceiptKeyIgnoresTrailingSpaceAndBlankEnds() {
        assertEquals(
                TopazPrinter.receiptKey(List.of("A", "B")),
                TopazPrinter.receiptKey(List.of("", "A  ", "B", "", "")));
    }

    @Test
    void theReceiptMarkersCannotPassForAPrintedLine() {
        for (String marker : List.of(TopazPrinter.RECEIPT_START, TopazPrinter.RECEIPT_LINE, TopazPrinter.RECEIPT_END)) {
            assertTrue(marker.charAt(0) > 0xFF, "a latin-1 char would let a printed line pass for a marker");
        }
    }

    @Test
    void aPcsOwnComPortIsNotAPrinterPort() {
        List<PortInfo> ports = List.of(
                new PortInfo("COM1", "Communications Port", -1, -1), // ACPI\PNP0501, built in
                new PortInfo("COM2", "Communications Port", -1, -1), // ACPI\PNP0500, built in
                new PortInfo("COM3", "USB Serial Port", 0x067B, 0x2303), // the USB-serial adapter
                new PortInfo("COM4", null, -1, -1)); // no description: left in, as the Python does
        assertEquals(List.of("COM3", "COM4"), PrinterPorts.visible(ports));
    }

    @Test
    void listingTheRealPortsOpensNone() {
        // Enumerates the OS's ports only; nothing is opened.
        assertTrue(PrinterPorts.visible() instanceof List);
    }

    private static String lostMessage(TopazPrinter printer) {
        try {
            printer.receiptsSince(0);
            return null;
        } catch (DeviceUnreachable exc) {
            return exc.getMessage();
        }
    }
}
