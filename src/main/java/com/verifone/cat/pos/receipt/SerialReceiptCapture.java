package com.verifone.cat.pos.receipt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The printed receipt, read off the register's printer cable. Ported from the Python
 * {@code petrotest.pos.receipt.SerialReceiptCapture}, the retained fallback (DESIGN.md 5.3).
 *
 * <p>It picks a receipt as PATS's {@code ReceiptValidator} does, not by arrival order: the newest
 * print carrying the {@code TRAN#} the sale committed. That is the newest of the Commander's
 * transactions in the {@code TRAN#}'s own POS series ({@link #POS_SERIES}), or one the Commander
 * did not yet hold when this object was made. A print with no {@code TRAN#} is a report, picked
 * only when {@link #setReports(boolean) reports} is set. Lines are kept as printed, blank lines and
 * column spacing included, because the snapshot compares layout.
 *
 * <p>Settling rules (the {@code fresh*} and {@code settle*} setters) are ported as they are; the
 * Python comments record the runs that motivated each.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class SerialReceiptCapture implements ReceiptSource {

    /**
     * A {@code trSeq} is its POS number times this, plus a count: Reg101 commits 1010181, and the
     * Commander records a prepay's fuel completion as POS 901, 9010001 (HANDOFF.md 1.62).
     */
    public static final long POS_SERIES = 10_000L;

    /** Between looks at the serial buffer (the Python's {@code POLL_INTERVAL}). */
    static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final ReceiptPrinter printer;
    private final Supplier<Iterable<Long>> transactions;
    private final Set<Long> before;
    private int next;

    private Duration timeout = Duration.ofSeconds(30);
    private boolean reports = true;
    private int freshFrom = -1;
    private Duration freshGrace = Duration.ZERO;
    private boolean settleAll;
    private int settleFrom = -1;
    private int settleCount;
    private Supplier<String> abort;

    /**
     * @param printer      the printed-receipt source
     * @param transactions the Commander's committed transaction numbers, read on demand
     */
    public SerialReceiptCapture(ReceiptPrinter printer, Supplier<Iterable<Long>> transactions) {
        this.printer = printer;
        this.transactions = transactions;
        this.next = printer.receiptCount();
        this.before = copy(transactions.get());
    }

    /** How long to wait for the receipt before giving up. Default 30 s. */
    public void setTimeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout);
    }

    /** Whether a print with no {@code TRAN#} may be the receipt. True keeps the pytest behaviour. */
    public void setReports(boolean reports) {
        this.reports = reports;
    }

    /**
     * The printer's count when the case's last POS action began; -1 for none. A print from before
     * it is taken only once {@code grace} has passed with no newer one.
     */
    public void setFreshFrom(int freshFrom, Duration grace) {
        this.freshFrom = freshFrom;
        this.freshGrace = Objects.requireNonNull(grace);
    }

    /** Hold every print until the grace has passed, then take the newest. */
    public void setSettleAll(boolean settleAll) {
        this.settleAll = settleAll;
    }

    /**
     * With {@link #setSettleAll(boolean) settleAll}: the printer's count when the first of those
     * sales' presses began, and how many {@code TRAN#} prints are owed since.
     */
    public void setSettleOwed(int settleFrom, int settleCount) {
        this.settleFrom = settleFrom;
        this.settleCount = settleCount;
    }

    /**
     * Asked on every look while waiting: a reason the receipt cannot come, which ends the wait as a
     * dead end, or null to keep waiting. Set per wait by the PATS runner.
     */
    public void setAbort(Supplier<String> abort) {
        this.abort = abort;
    }

    @Override
    public Receipt capture() {
        long settled = System.nanoTime() + freshGrace.toNanos();
        long deadline = System.nanoTime() + timeout.toNanos();
        Pick got;
        while ((got = pick(settled)) == null) {
            if (System.nanoTime() - deadline >= 0) {
                printer.checkAlive();
                throw ReceiptWaitException.timedOut("the printed receipt");
            }
            pause();
        }
        next += got.offset() + 1;
        List<ReceiptLine> lines = new ArrayList<>();
        for (int i = 0; i < got.lines().size(); i++) {
            lines.add(ReceiptLine.ofRaw(i, got.lines().get(i)));
        }
        return Receipt.ofLines(lines, Source.SERIAL);
    }

    private Pick pick(long settled) {
        Pick got = pickOnce();
        if (got != null && (settleAll || next + got.offset() < freshFrom)) {
            boolean done = System.nanoTime() - settled >= 0 || (settleAll && allPrinted());
            got = done ? got : null;
        }
        if (got == null && abort != null) {
            String reason = abort.get();
            if (reason != null && !reason.isEmpty()) {
                throw ReceiptWaitException.deadEnd(reason);
            }
        }
        return got;
    }

    /** Whether {@code settleCount} {@code TRAN#} prints have arrived since {@code settleFrom}. */
    private boolean allPrinted() {
        if (settleCount <= 0 || settleFrom < 0) {
            return false;
        }
        int printed = 0;
        for (List<String> receipt : printer.receiptsSince(settleFrom)) {
            if (ReceiptParser.transactionNumber(receipt) != null) {
                printed++;
            }
        }
        return printed >= settleCount;
    }

    private Pick pickOnce() {
        List<List<String>> printed = printer.receiptsSince(next);
        List<Long> numbers = new ArrayList<>(printed.size());
        boolean anyNumber = false;
        for (List<String> receipt : printed) {
            Long number = ReceiptParser.transactionNumber(receipt);
            numbers.add(number);
            anyNumber |= number != null;
        }
        Set<Long> committed = anyNumber ? copy(transactions.get()) : Set.of();

        int picked = -1;
        for (int i = 0; i < printed.size(); i++) {
            Long number = numbers.get(i);
            boolean ok = number == null ? reports : isTheSale(number, committed);
            if (ok) {
                picked = i;
            }
        }
        return picked < 0 ? null : new Pick(picked, printed.get(picked));
    }

    /**
     * The newest the Commander holds, or the one after it: a transaction still open when it
     * printed. A spooled print carries an older number, not the next one, so it is passed over.
     */
    private boolean isTheSale(long number, Set<Long> committed) {
        long series = number / POS_SERIES;
        boolean haveNewest = false;
        long newest = 0;
        for (long t : committed) {
            if (t / POS_SERIES == series && (!haveNewest || t > newest)) {
                newest = t;
                haveNewest = true;
            }
        }
        if (before.contains(number) || !haveNewest) {
            return false;
        }
        return number == newest || number == newest + 1;
    }

    private void pause() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the printed receipt", interrupted);
        }
    }

    private static Set<Long> copy(Iterable<Long> values) {
        Set<Long> out = new HashSet<>();
        for (Long value : values) {
            out.add(value);
        }
        return out;
    }

    /** The picked print: its offset from the first unread one, and its lines as printed. */
    private record Pick(int offset, List<String> lines) {}
}
