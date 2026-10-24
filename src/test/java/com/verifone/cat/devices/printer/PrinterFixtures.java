package com.verifone.cat.devices.printer;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import com.verifone.cat.validate.Normalisers;

/**
 * Shared test support for the printer: Reg101's own bytes, an in-memory {@link PrinterPort} (no serial
 * port is ever opened), and bounded waits. Ported from the Python {@code tests/unit/test_devices_topaz_printer.py}.
 *
 * <p>The fixtures are bytes Reg101 sent to COM1 on 2026-09-14 (HANDOFF.md 1.34), copied from the Python
 * suite's {@code tests/fixtures/topaz_printer/}. The snapshot is the Charity suite's 61334 receipt.
 */
final class PrinterFixtures {

    static final byte[] PRINT_JOB = resource("reg101_cash_sale_1010009.bin");
    static final byte[] HANDSHAKE = resource("reg101_status_handshake.bin");
    static final String SNAPSHOT = Normalisers.normaliseReceipt(
            new String(resource("PETROCORE-61334-CATReceipt.txt"), StandardCharsets.UTF_8))
            .replaceAll("\n+$", "");

    private PrinterFixtures() {}

    static byte[] resource(String name) {
        try (InputStream in = PrinterFixtures.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exc) {
            throw new IllegalStateException("cannot read test resource " + name, exc);
        }
    }

    /** The bytes as latin-1 text, one char per byte, for the string operations the Python does on bytes. */
    static String latin1(byte[] data) {
        return new String(data, ISO_8859_1);
    }

    static byte[] bytes(String latin1) {
        return latin1.getBytes(ISO_8859_1);
    }

    /** {@code bytes.replace(from, to)} over latin-1 text. */
    static byte[] replace(byte[] data, String from, String to) {
        return bytes(latin1(data).replace(from, to));
    }

    /** The receipt text as the snapshot compares it: normalised, trailing newlines dropped. */
    static String receiptText(List<String> lines) {
        return Normalisers.normaliseReceipt(String.join("\n", lines)).replaceAll("\n+$", "");
    }

    /** Reg101's 61334 print job, as if for transaction {@code tran}. */
    static byte[] job(long tran) {
        return replace(PRINT_JOB, "TRAN#1010009", "TRAN#" + tran);
    }

    static String hex(byte[] data) {
        return java.util.HexFormat.ofDelimiter(" ").formatHex(data);
    }

    /** Waits, bounded, until {@code condition} holds; fails the test if it never does. */
    static void until(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("condition not met within " + timeout);
            }
            LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        }
    }

    /**
     * An in-memory port: serves queued chunks to {@link #read}, records {@link #write}s. An empty queue
     * is a read timeout (0 bytes after at most 10 ms), as a serial port's is. {@link #fail} makes every
     * later read throw, as a dropped adapter does.
     */
    static final class FakePort implements PrinterPort {

        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        final List<byte[]> written = Collections.synchronizedList(new java.util.ArrayList<>());
        private volatile IOException fail;
        private volatile boolean closed;
        private byte[] carry = new byte[0];

        FakePort(byte[]... initial) {
            for (byte[] chunk : initial) {
                send(chunk);
            }
        }

        void send(byte[] chunk) {
            chunks.add(chunk);
        }

        void fail(IOException exc) {
            fail = exc;
        }

        boolean closed() {
            return closed;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            IOException failure = fail;
            if (failure != null) {
                throw failure;
            }
            if (carry.length == 0) {
                byte[] next;
                try {
                    next = chunks.poll(10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exc) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exc);
                }
                if (next == null) {
                    return 0;
                }
                carry = next;
            }
            int n = Math.min(buffer.length, carry.length);
            System.arraycopy(carry, 0, buffer, 0, n);
            carry = Arrays.copyOfRange(carry, n, carry.length);
            return n;
        }

        @Override
        public void write(byte[] data) {
            written.add(data.clone());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Splits {@code data} into fixed-size reads, as COM1 delivered Reg101's job. */
    static byte[][] chunks(byte[] data, int size) {
        int count = (data.length + size - 1) / size;
        byte[][] out = new byte[count][];
        for (int i = 0; i < count; i++) {
            out[i] = Arrays.copyOfRange(data, i * size, Math.min(data.length, (i + 1) * size));
        }
        return out;
    }
}
