package com.verifone.cat.pos.receipt;

/**
 * A receipt wait ended without a receipt. {@link #deadEnd()} is true when the wait was aborted by
 * a reason the receipt cannot come (the Python's {@code DeadEnd}), and false on a plain timeout
 * (the Python's {@code TimeoutExpired}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class ReceiptWaitException extends RuntimeException {

    private final boolean deadEnd;

    private ReceiptWaitException(String message, boolean deadEnd) {
        super(message);
        this.deadEnd = deadEnd;
    }

    /** The wait timed out with no receipt. */
    public static ReceiptWaitException timedOut(String what) {
        return new ReceiptWaitException("timed out waiting for " + what, false);
    }

    /** The wait was aborted because the receipt cannot come. */
    public static ReceiptWaitException deadEnd(String reason) {
        return new ReceiptWaitException(reason, true);
    }

    public boolean deadEnd() {
        return deadEnd;
    }
}
