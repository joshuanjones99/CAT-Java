package com.verifone.cat.pos.receipt;

import java.util.List;

/**
 * What serial capture needs from the printer. Ported from the Python {@code ReceiptPrinter}
 * protocol; {@code petrotest.devices.topaz_printer.TopazPrinter} has it there.
 *
 * <p>TODO(devices): the Java printer (the serial adapter on the controller) is not ported. Until it
 * is, a caller implements this over whatever feeds the printed text.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface ReceiptPrinter {

    /** How many receipts (prints) have arrived since the printer was made. */
    int receiptCount();

    /** The prints from index {@code index} onward, each as its lines. */
    List<List<String>> receiptsSince(int index);

    /**
     * Called when a receipt wait times out: lets an adapter that dropped off Windows say so, not
     * report a plain timeout. The Python's optional {@code check_alive}, as a default method.
     */
    default void checkAlive() {}
}
