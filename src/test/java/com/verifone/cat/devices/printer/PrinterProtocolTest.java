package com.verifone.cat.devices.printer;

import static com.verifone.cat.devices.printer.PrinterFixtures.HANDSHAKE;
import static com.verifone.cat.devices.printer.PrinterFixtures.PRINT_JOB;
import static com.verifone.cat.devices.printer.PrinterFixtures.SNAPSHOT;
import static com.verifone.cat.devices.printer.PrinterFixtures.bytes;
import static com.verifone.cat.devices.printer.PrinterFixtures.hex;
import static com.verifone.cat.devices.printer.PrinterFixtures.latin1;
import static com.verifone.cat.devices.printer.PrinterFixtures.receiptText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The decoder against Reg101's own bytes. Ported from the Python {@code TestPrinterProtocol} and
 * {@code TestPrintJobs}. Decodes in memory only: no port is opened.
 */
class PrinterProtocolTest {

    @Test
    void printJobDecodesToThe61334Snapshot() {
        PrinterProtocol protocol = new PrinterProtocol();
        PrinterProtocol.Feed feed = protocol.feed(PRINT_JOB);
        assertEquals(1, feed.receipts().size());
        assertEquals(SNAPSHOT, receiptText(feed.receipts().get(0)));
        assertTrue(protocol.unknown().isEmpty(), protocol.unknown().toString());
    }

    @Test
    void sameReceiptWhenBytesArriveOneAtATime() {
        // COM1 delivered this job in 220-byte reads that split commands mid-way.
        PrinterProtocol protocol = new PrinterProtocol();
        List<List<String>> receipts = new ArrayList<>();
        for (byte b : PRINT_JOB) {
            receipts.addAll(protocol.feed(new byte[] {b}).receipts());
        }
        assertEquals(1, receipts.size());
        assertEquals(SNAPSHOT, receiptText(receipts.get(0)));
    }

    @Test
    void printJobOwesOnlyTheProcessIdEcho() {
        List<byte[]> replies = new PrinterProtocol().feed(PRINT_JOB).replies();
        // GS ( H 06 00 30 30 "   #" -> 37 22 "   #" 00; the GS I 0x34 polls get none.
        assertEquals(1, replies.size());
        assertEquals("37 22 20 20 20 23 00", hex(replies.get(0)));
    }

    @Test
    void statusHandshakeGetsTheRepliesReg101Accepted() {
        PrinterProtocol.Feed feed = new PrinterProtocol().feed(HANDSHAKE);
        assertTrue(feed.receipts().isEmpty());
        List<String> got = new ArrayList<>();
        for (byte[] reply : feed.replies()) {
            got.add(hex(reply));
        }
        assertEquals(
                List.of(
                        "04", // GS I 04
                        "04",
                        "37 25 00", // DLE DC4 08: clear buffers
                        "14 00 00 0f", // GS a FF: automatic status back
                        "62", // GS I 03
                        "02", // GS I 02
                        "04", // GS I 01
                        "5f 31 00"), // GS I 71
                got);
    }

    @Test
    void aCutInsideAJobDoesNotSplitTheReceipt() {
        // PATS's receipt is a print job holding a cut: a card sale's two copies in one job are one receipt.
        String job = latin1(PRINT_JOB);
        int cut = job.indexOf("\u001dV\u0001") + 3;
        int body = job.indexOf("\u001b=\u0001\u001b{");
        byte[] twoCopies = concat(PRINT_JOB, 0, cut, PRINT_JOB, body, cut, PRINT_JOB, cut, PRINT_JOB.length);
        List<List<String>> receipts = new PrinterProtocol().feed(twoCopies).receipts();
        assertEquals(1, receipts.size());
        assertEquals(SNAPSHOT + "\n" + SNAPSHOT, receiptText(receipts.get(0)));
    }

    @Test
    void aJobWithNoCutCarriesIntoTheNext() {
        byte[] uncut = PrinterFixtures.replace(PRINT_JOB, "\u001dV\u0001", "");
        List<List<String>> receipts = new PrinterProtocol().feed(concat(uncut, 0, uncut.length, PRINT_JOB, 0, PRINT_JOB.length)).receipts();
        assertEquals(1, receipts.size());
        assertEquals(SNAPSHOT + "\n" + SNAPSHOT, receiptText(receipts.get(0)));
    }

    @Test
    void theProcessIdReplyEchoesTheRequestsIdBytes() {
        // GS ( H 06 00 "00" then the four ID bytes "ABCD".
        byte[] request = bytes("\u001d(H\u0006\u000000ABCD");
        assertEquals("37 22 41 42 43 44 00", hex(PrinterProtocol.reply(request)));
    }

    private static byte[] concat(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        return concat(a, aFrom, aTo, b, bFrom, bTo, new byte[0], 0, 0);
    }

    private static byte[] concat(
            byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo, byte[] c, int cFrom, int cTo) {
        byte[] out = new byte[(aTo - aFrom) + (bTo - bFrom) + (cTo - cFrom)];
        System.arraycopy(a, aFrom, out, 0, aTo - aFrom);
        System.arraycopy(b, bFrom, out, aTo - aFrom, bTo - bFrom);
        System.arraycopy(c, cFrom, out, (aTo - aFrom) + (bTo - bFrom), cTo - cFrom);
        return out;
    }
}
