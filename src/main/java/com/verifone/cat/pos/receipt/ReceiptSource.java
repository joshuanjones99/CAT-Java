package com.verifone.cat.pos.receipt;

/**
 * The DESIGN.md 5.3 seam: DOM now, serial as the retained fallback. Ported from the Python
 * {@code ReceiptSource} protocol.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface ReceiptSource {

    Receipt capture();
}
