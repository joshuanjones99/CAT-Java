package com.verifone.cat.devices.printer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Bytes from the register in; replies and finished receipts out. No I/O. Ported from the Python
 * {@code petrotest.devices.topaz_printer.PrinterProtocol}.
 *
 * <p>A receipt is a print job holding a cut: text between line feeds, with ESC/POS commands removed.
 * The job ends at the process-ID request ({@code GS ( H 06 00 30 30}) after a cut, so two copies in
 * one job are one receipt. Printed bytes below 0x20 other than LF are dropped; the rest are kept as
 * latin-1 text, so the layout survives for the snapshot.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PrinterProtocol {

    /** The replies owed to the register, and the receipts a cut completed, from one {@code feed}. */
    record Feed(List<byte[]> replies, List<List<String>> receipts) {}

    private static final int ESC = 0x1B;
    private static final int GS = 0x1D;
    private static final int DLE = 0x10;
    private static final int LF = 0x0A;

    /** Argument bytes after {@code ESC <op>}, indexed by op; -1 when the op has no entry. */
    private static final int[] ESC_ARGS = escTable();
    /** Argument bytes after {@code GS <op>}; {@code GS (} and {@code GS V} are sized in {@link #commandLength}. */
    private static final int[] GS_ARGS = gsTable();

    private byte[] pending = new byte[0];
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private List<String> lines = new ArrayList<>();
    private boolean cut;

    /** ESC/GS commands with no table entry, as their first two bytes in hex ("1b 4d"). */
    private final Set<String> unknown = new HashSet<>();

    /** Consumes {@code data}; returns the replies owed and any receipts a cut completed. */
    Feed feed(byte[] data) {
        byte[] buf = new byte[pending.length + data.length];
        System.arraycopy(pending, 0, buf, 0, pending.length);
        System.arraycopy(data, 0, buf, pending.length, data.length);
        List<byte[]> replies = new ArrayList<>();
        List<List<String>> receipts = new ArrayList<>();
        int i = 0;
        while (i < buf.length) {
            int b = u(buf[i]);
            if (b == ESC || b == GS || b == DLE) {
                int length = commandLength(buf, i);
                if (length < 0) {
                    break;
                }
                byte[] cmd = Arrays.copyOfRange(buf, i, i + length);
                i += length;
                noteUnknown(b, cmd);
                if (startsWith(cmd, GS, 'V')) {
                    if (line.size() > 0) {
                        endLine();
                    }
                    cut = true;
                } else if (cut && startsWith(cmd, GS, '(', 'H', 0x06, 0x00, '0', '0')) {
                    // The process-ID request closes a print job: two copies in one job are one receipt.
                    if (!lines.isEmpty()) {
                        receipts.add(List.copyOf(lines));
                    }
                    lines = new ArrayList<>();
                    cut = false;
                }
                byte[] reply = reply(cmd);
                if (reply != null) {
                    replies.add(reply);
                }
                continue;
            }
            if (b == LF) {
                endLine();
            } else if (b >= 0x20) {
                line.write(b);
            }
            i++;
        }
        pending = Arrays.copyOfRange(buf, i, buf.length);
        return new Feed(replies, receipts);
    }

    /** The ESC/GS ops seen with no table entry, as hex pairs, so a caller can log each once. */
    Set<String> unknown() {
        return Set.copyOf(unknown);
    }

    private void noteUnknown(int lead, byte[] cmd) {
        if (lead == DLE || startsWith(cmd, GS, '(') || startsWith(cmd, GS, 'V')) {
            return;
        }
        int[] table = lead == ESC ? ESC_ARGS : GS_ARGS;
        if (table[u(cmd[1])] < 0) {
            unknown.add(HexFormat.ofDelimiter(" ").formatHex(cmd, 0, 2));
        }
    }

    private void endLine() {
        lines.add(new String(line.toByteArray(), StandardCharsets.ISO_8859_1));
        line.reset();
    }

    /** Bytes in the ESC/GS/DLE command at {@code buf[i]}, or -1 if it has not all arrived. */
    static int commandLength(byte[] buf, int i) {
        int avail = buf.length - i;
        if (avail < 2) {
            return -1;
        }
        int lead = u(buf[i]);
        int op = u(buf[i + 1]);
        int length;
        if (lead == GS && op == '(') { // GS ( fn pL pH data
            if (avail < 5) {
                return -1;
            }
            length = 5 + u(buf[i + 3]) + 256 * u(buf[i + 4]);
        } else if (lead == GS && op == 'V') { // GS V m [n]
            if (avail < 3) {
                return -1;
            }
            int m = u(buf[i + 2]);
            length = (m == 65 || m == 66) ? 4 : 3;
        } else if (lead == DLE) {
            if (op == 0x14) { // DLE DC4 fn ...: fn 8 carries seven bytes, the rest two
                if (avail < 3) {
                    return -1;
                }
                length = u(buf[i + 2]) == 8 ? 10 : 5;
            } else { // DLE EOT n, DLE ENQ n
                length = 3;
            }
        } else {
            int args = (lead == ESC ? ESC_ARGS : GS_ARGS)[op];
            length = args < 0 ? 2 : 2 + args;
        }
        return avail >= length ? length : -1;
    }

    /**
     * The reply owed to {@code cmd}, or null. PATS's replies, except that the process ID echoes the
     * request's four ID bytes, as ESC/POS specifies (PATS sends bytes cut from its own framing).
     */
    static byte[] reply(byte[] cmd) {
        if (startsWith(cmd, GS, 'I')) { // transmit printer ID
            return printerId(u(cmd[2]));
        }
        if (startsWith(cmd, DLE, 0x14, 0x08)) { // clear buffers
            return new byte[] {0x37, 0x25, 0x00};
        }
        if (cmd.length == 3 && startsWith(cmd, GS, 'a', 0xFF)) { // enable automatic status back
            return new byte[] {0x14, 0x00, 0x00, 0x0f};
        }
        if (cmd.length >= 11 && startsWith(cmd, GS, '(', 'H', 0x06, 0x00) && cmd[5] == '0' && cmd[6] == '0') {
            byte[] out = new byte[7];
            out[0] = 0x37;
            out[1] = 0x22;
            System.arraycopy(cmd, 7, out, 2, 4);
            return out;
        }
        if (startsWith(cmd, GS, '(', 'H', 0x03, 0x00)) {
            return new byte[] {0x37, 0x22, 0x20, 0x20, 0x28, 0x4a, 0x00}; // PATS's reply to pL=3
        }
        return null;
    }

    private static byte[] printerId(int n) {
        return switch (n) {
            case 0x01 -> new byte[] {0x04};
            case 0x02 -> new byte[] {0x02};
            case 0x03 -> new byte[] {0x62};
            case 0x04 -> new byte[] {0x04};
            case 0x71 -> new byte[] {0x5f, 0x31, 0x00};
            default -> null; // unlisted n gets no reply
        };
    }

    /** Whether {@code cmd} begins with the given bytes (a {@code char} or an int 0..255 each). */
    private static boolean startsWith(byte[] cmd, int... prefix) {
        if (cmd.length < prefix.length) {
            return false;
        }
        for (int k = 0; k < prefix.length; k++) {
            if (u(cmd[k]) != prefix[k]) {
                return false;
            }
        }
        return true;
    }

    private static int[] escTable() {
        int[] table = blankTable();
        for (char c : "={tRM3EaJ-d!G".toCharArray()) {
            table[c] = 1;
        }
        table['@'] = 0;
        return table;
    }

    private static int[] gsTable() {
        int[] table = blankTable();
        for (char c : "!bBaIr".toCharArray()) {
            table[c] = 1;
        }
        table['L'] = 2;
        table['P'] = 2;
        return table;
    }

    private static int[] blankTable() {
        int[] table = new int[256];
        Arrays.fill(table, -1);
        return table;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
