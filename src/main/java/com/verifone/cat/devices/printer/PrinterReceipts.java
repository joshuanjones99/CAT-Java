package com.verifone.cat.devices.printer;

import com.verifone.cat.pos.receipt.ReceiptCaptureException;
import com.verifone.cat.pos.receipt.ReceiptPrinter;
import com.verifone.cat.pos.receipt.ReceiptSource;
import com.verifone.cat.pos.receipt.ReceiptWait;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.SerialReceiptCapture;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The register's printed receipt as a {@link ReceiptSource}, for a run: the Python's {@code SerialReceiptCapture} the
 * {@code pos} fixture makes for each test.
 *
 * <p>{@link SerialReceiptCapture} takes the Commander's transaction numbers as they stand when it is made, so the
 * numbers a case's own sale is about to commit are not mistaken for old ones. This source is therefore made for each
 * case by {@link #begin()}, which the run calls at the case's start; {@link #capture()} then picks from that case's
 * capture. A capture never begun (no case in hand, or the Commander could not be read at the start) is a
 * {@link ReceiptCaptureException}, so the validation fails by name.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PrinterReceipts implements ReceiptSource {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.devices.printer");

    private final ReceiptPrinter printer;
    private final Supplier<Iterable<Long>> transactions;
    private final Duration timeout;
    private volatile SerialReceiptCapture current;
    private volatile String beginFailure = "no case has begun";

    /**
     * @param printer      the printer the register's receipts arrive on
     * @param transactions the Commander's committed transaction numbers, read on demand
     * @param timeout      how long a receipt is waited for (the rig's {@code prompt_wait})
     */
    public PrinterReceipts(ReceiptPrinter printer, Supplier<Iterable<Long>> transactions, Duration timeout) {
        this.printer = Objects.requireNonNull(printer, "printer");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Makes the capture for a case that is starting. A Commander that cannot be read here is logged, and the case's
     * receipt then fails by name.
     */
    public void begin() {
        try {
            SerialReceiptCapture capture = new SerialReceiptCapture(printer, transactions);
            capture.setTimeout(timeout);
            current = capture;
            beginFailure = null;
        } catch (RuntimeException e) {
            current = null;
            beginFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warn("the printed receipt cannot be read for this case: {}", beginFailure);
        }
    }

    @Override
    public Receipt capture() {
        return current().capture();
    }

    /**
     * One capture tuned as the PATS runner's {@code _pos_receipt} tunes the Python's source: the wait's ceiling
     * ({@code max} of this source's and the case's), whether a no-{@code TRAN#} print may be the receipt, the case's
     * fresh-print mark and its grace, the settle for an unread sale, and the abort. Every setting is put back after
     * it, as the Python's {@code finally} does.
     */
    @Override
    public Receipt capture(ReceiptWait wait) {
        SerialReceiptCapture capture = current();
        capture.setTimeout(wait.timeout().compareTo(timeout) > 0 ? wait.timeout() : timeout);
        capture.setReports(wait.reports());
        capture.setFreshFrom(wait.freshFrom(), wait.freshGrace());
        capture.setAbort(wait.abort());
        capture.setSettleAll(wait.settleAll());
        capture.setSettleOwed(wait.settleFrom(), wait.settleCount());
        try {
            return capture.capture();
        } finally {
            capture.setTimeout(timeout);
            capture.setReports(true);
            capture.setFreshFrom(-1, Duration.ZERO);
            capture.setAbort(null);
            capture.setSettleAll(false);
            capture.setSettleOwed(-1, 0);
        }
    }

    /** The printer's receipt count, for the case's print mark (the Python's {@code _print_count}). */
    @Override
    public int printCount() {
        return printer.receiptCount();
    }

    private SerialReceiptCapture current() {
        SerialReceiptCapture capture = current;
        if (capture == null) {
            throw new ReceiptCaptureException("the printed receipt was not set up for this case ("
                    + beginFailure + ")");
        }
        return capture;
    }
}
