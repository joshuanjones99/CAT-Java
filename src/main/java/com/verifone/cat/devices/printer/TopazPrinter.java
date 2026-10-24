package com.verifone.cat.devices.printer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pos.receipt.ReceiptPrinter;

/**
 * The register's receipt printer on one serial port: it answers the register's status queries, and
 * keeps the receipts it prints. Ported from the Python {@code petrotest.devices.topaz_printer.TopazPrinter}.
 *
 * <p>This is the {@link ReceiptPrinter} that {@link com.verifone.cat.pos.receipt.SerialReceiptCapture}
 * reads. A background thread reads the {@link PrinterPort} and decodes it with {@link PrinterProtocol};
 * each receipt is logged to {@link #RECEIPT_LOGGER} as one record per line between a
 * {@link #RECEIPT_START} and a {@link #RECEIPT_END} record, as the Python does, so CAT's Receipt Printer
 * row can show the paper roll.
 *
 * <p>The hand-over: a holder opens with {@link #open(String)}, and {@link #close()} releases the port
 * (the read loop is stopped and joined for a bounded time, then the port closed) so the next holder can
 * open it. Nothing here waits without a bound. A lost port (an adapter dropped off Windows) is sticky:
 * once found, every later call fails at once.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Not yet run against Reg101's COM port.
 */
public final class TopazPrinter implements ReceiptPrinter, AutoCloseable {

    /** What CAT calls this printer: its Simulators row, beside PATS's simulators. */
    public static final String PRINTER_NAME = "Receipt Printer";

    /**
     * Every printed line, and the port's comings and goings, are logged here. {@code sim.<name>} is how
     * CAT keys one simulator's log, so these records land under its Receipt Printer row.
     */
    public static final String RECEIPT_LOGGER = "sim." + PRINTER_NAME;

    /** The three record shapes of one receipt: box-drawing characters, which no latin-1 print can hold. */
    public static final String RECEIPT_START = "┌ ";
    public static final String RECEIPT_LINE = "│ ";
    public static final String RECEIPT_END = "└ ";

    /** Names a file the raw bytes from the register are appended to, for a print that decodes short. */
    public static final String RAW_ENV = "CAT_PRINTER_RAW";

    private static final Logger log = LoggerFactory.getLogger(RECEIPT_LOGGER);
    private static final Duration CLOSE_JOIN = Duration.ofSeconds(2);
    private static final int READ_CHUNK = 4096;

    private final PrinterPort port;
    private final String name;
    private final Supplier<Collection<String>> listed;
    private final Path rawPath;
    private final PrinterProtocol protocol = new PrinterProtocol();
    private final Set<String> unknownLogged = new HashSet<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Thread thread;

    /** Guarded by {@code this}. */
    private final List<List<String>> receipts = new ArrayList<>();
    /** Guarded by {@code this}; sticky once set. */
    private IOException error;

    /**
     * @param port the byte stream to answer on, not yet started
     * @param name the port's name, e.g. {@code COM1}; checked against the OS port listing
     */
    public TopazPrinter(PrinterPort port, String name) {
        this(port, name, () -> PrinterPorts.visible());
    }

    /** As {@link #TopazPrinter(PrinterPort, String)}, with the OS port listing injected. */
    public TopazPrinter(PrinterPort port, String name, Supplier<Collection<String>> listed) {
        this.port = port;
        this.name = name;
        this.listed = listed;
        String raw = System.getenv(RAW_ENV);
        this.rawPath = raw == null || raw.isEmpty() ? null : Path.of(raw);
        this.thread = new Thread(this::readLoop, "printer-" + name);
        this.thread.setDaemon(true);
    }

    /**
     * Opens {@code device} (e.g. {@code COM1}) as PATS does and starts answering on it.
     *
     * @throws DeviceUnreachable if the port will not open
     */
    public static TopazPrinter open(String device) {
        JSerialCommPort port;
        try {
            port = JSerialCommPort.open(device);
        } catch (DeviceUnreachable exc) {
            log.warn("{} will not open: {}", device, exc.getMessage());
            throw exc;
        }
        TopazPrinter printer = new TopazPrinter(port, device);
        printer.start();
        return printer;
    }

    /** Starts the read loop. Logged first, so the roll never shows a receipt above its port opening. */
    public void start() {
        log.info("{} open: answering the register as its printer", name);
        thread.start();
    }

    /**
     * Stops the read loop, waits at most two seconds for it, and closes the port. Idempotent.
     */
    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            thread.join(CLOSE_JOIN.toMillis());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
        port.close();
        log.info("{} closed", name);
    }

    @Override
    public synchronized int receiptCount() {
        return receipts.size();
    }

    /** Every receipt printed from position {@code index} (0-based) on, oldest first. */
    @Override
    public synchronized List<List<String>> receiptsSince(int index) {
        raiseIfLost();
        if (index >= receipts.size()) {
            return List.of();
        }
        return List.copyOf(receipts.subList(Math.max(index, 0), receipts.size()));
    }

    /**
     * Raises {@link DeviceUnreachable} if the port has gone, and keeps it gone. Asks the OS whether
     * the port is still listed, since the read loop does not always notice an adapter that dropped off.
     */
    @Override
    public synchronized void checkAlive() {
        if (error == null && !stopped.get()) {
            if (!thread.isAlive()) {
                error = new IOException("the read loop has stopped");
            } else {
                Set<String> ports = new HashSet<>();
                for (String listedPort : listed.get()) {
                    ports.add(listedPort.toUpperCase(Locale.ROOT));
                }
                if (!ports.contains(name.toUpperCase(Locale.ROOT))) {
                    error = new IOException(name + " is no longer listed by Windows");
                }
            }
        }
        raiseIfLost();
    }

    /** The name of the port this printer answers on. */
    public String name() {
        return name;
    }

    private synchronized void raiseIfLost() {
        if (error != null) {
            throw new DeviceUnreachable(
                    "TopazPrinter on " + name,
                    "serial read failed: " + error.getMessage() + ". The USB-serial adapter has dropped "
                            + "off Windows; unplug and replug it, then start the run again.");
        }
    }

    private void readLoop() {
        byte[] buf = new byte[READ_CHUNK];
        while (!stopped.get()) {
            List<List<String>> finished;
            try {
                int n = port.read(buf);
                if (n == 0) {
                    continue;
                }
                byte[] data = Arrays.copyOf(buf, n);
                if (rawPath != null) {
                    Files.write(rawPath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                PrinterProtocol.Feed feed = protocol.feed(data);
                for (byte[] reply : feed.replies()) {
                    port.write(reply);
                }
                logNewUnknownCommands();
                finished = feed.receipts();
            } catch (IOException exc) {
                fail(exc);
                return;
            }
            if (!finished.isEmpty()) {
                int first;
                synchronized (this) {
                    first = receipts.size() + 1;
                    receipts.addAll(finished);
                }
                for (int k = 0; k < finished.size(); k++) {
                    logReceipt(first + k, finished.get(k));
                }
            }
        }
    }

    private void logNewUnknownCommands() {
        for (String cmd : protocol.unknown()) {
            if (unknownLogged.add(cmd)) {
                log.info("{} sent ESC/POS command {}, not in the decoder's tables", name, cmd);
            }
        }
    }

    private void fail(IOException exc) {
        synchronized (this) {
            error = exc;
        }
        log.warn("{} read failed: {}; the USB-serial adapter may be gone", name, exc.getMessage());
    }

    private void logReceipt(int number, List<String> lines) {
        log.info(RECEIPT_START + "receipt {} on {}, {} lines [{}]",
                number, name, lines.size(), receiptKey(lines));
        for (String line : lines) {
            log.info(RECEIPT_LINE + "{}", line);
        }
        log.info(RECEIPT_END + "end of receipt {}", number);
    }

    /**
     * A short name for a receipt's text, the same for the printed lines and the text a receipt check
     * compared: lines right-stripped, blank lines at either end dropped, SHA-1 of the UTF-8 text,
     * first ten hex digits. Equal to the Python's {@code receipt_key} for the same lines.
     */
    public static String receiptKey(Iterable<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            kept.add(rstrip(line));
        }
        int start = 0;
        int end = kept.size();
        while (start < end && kept.get(start).isEmpty()) {
            start++;
        }
        while (end > start && kept.get(end - 1).isEmpty()) {
            end--;
        }
        String joined = String.join("\n", kept.subList(start, end));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-1 is required by every Java runtime", exc);
        }
    }

    private static String rstrip(String line) {
        int end = line.length();
        while (end > 0 && (Character.isWhitespace(line.charAt(end - 1)) || Character.isSpaceChar(line.charAt(end - 1)))) {
            end--;
        }
        return line.substring(0, end);
    }
}
