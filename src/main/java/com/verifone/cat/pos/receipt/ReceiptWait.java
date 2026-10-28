package com.verifone.cat.pos.receipt;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How one receipt read waits and picks: what the Python's {@code validation._pos_receipt} sets on the POS's
 * {@code receipt_source} for the length of one {@code pos.receipt()} call, and puts back after it. Only a serial
 * capture ({@link SerialReceiptCapture}, through {@code devices.printer.PrinterReceipts}) honours it; a source with
 * none of these knobs (the DOM capture, a fixed text) ignores it, as the Python's {@code getattr} guards skip it.
 *
 * @param timeout     the wait's ceiling; the source keeps its own when that is longer ({@code max(before, timeout)})
 * @param reports     whether a print with no {@code TRAN#} may be the receipt: only in a case that prints a report
 * @param freshFrom   the printer's count when the case's latest POS-driving action began; -1 for none
 * @param freshGrace  how long a print from before {@code freshFrom} is held back for a newer one
 * @param settleAll   hold every print for the grace, then take the newest: a sale closed before the last POS action
 *                    is still unread
 * @param settleFrom  with {@code settleAll}: the printer's count when the first unread sale's press began; -1 for none
 * @param settleCount with {@code settleAll}: how many {@code TRAN#} prints are owed since {@code settleFrom}
 * @param abort       asked on every look: a reason the receipt cannot come, or null; may itself be null
 */
public record ReceiptWait(Duration timeout, boolean reports, int freshFrom, Duration freshGrace, boolean settleAll,
        int settleFrom, int settleCount, Supplier<String> abort) {

    public ReceiptWait {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(freshGrace, "freshGrace");
    }
}
