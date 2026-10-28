package com.verifone.cat.pos.receipt;

/**
 * The DESIGN.md 5.3 seam: DOM now, serial as the retained fallback. Ported from the Python
 * {@code ReceiptSource} protocol.
 *
 * <p>The two default methods stand for the Python's duck-typed attributes the PATS runner reads and sets on a
 * {@code receipt_source} when it has them ({@code printer.receipt_count}, and {@code timeout}, {@code reports},
 * {@code fresh_from} and the rest): a source without them, as the DOM capture is, keeps these defaults.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface ReceiptSource {

    Receipt capture();

    /**
     * One capture, waiting and picking as {@code wait} says, the source's own settings put back after it. The default
     * ignores {@code wait}: a source with no wait to tune.
     */
    default Receipt capture(ReceiptWait wait) {
        return capture();
    }

    /** The serial printer's receipt count (the Python's {@code _print_count}); -1 where there is no serial printer. */
    default int printCount() {
        return -1;
    }
}
