package com.verifone.cat.pos.receipt;

/**
 * The receipt could not be captured. Ported from the Python {@code ReceiptCaptureError}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class ReceiptCaptureException extends RuntimeException {

    public ReceiptCaptureException(String message) {
        super(message);
    }
}
