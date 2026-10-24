package com.verifone.cat.pos.receipt;

import java.util.List;

/**
 * What serial capture needs from the printer. Ported from the Python {@code ReceiptPrinter}
 * protocol; {@code petrotest.devices.topaz_printer.TopazPrinter} has it there.
 *
 * <p>The Java printer is {@code com.verifone.cat.devices.printer.TopazPrinter}, which reads the
 * register's COM port through jSerialComm. Any other source of printed text can implement this.
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
