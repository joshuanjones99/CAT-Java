package com.verifone.cat.devices.printer;

import java.io.IOException;

/**
 * The byte stream a {@link TopazPrinter} answers on: a serial port in production
 * ({@link JSerialCommPort}), an in-memory stream in tests. Ported from the duck-typed
 * {@code petrotest.devices.topaz_printer.SerialPort} protocol (pyserial's {@code serial.Serial}).
 *
 * <p>{@link #read(byte[])} waits a bounded time and returns 0 when nothing arrived, so the read loop
 * can check whether it has been stopped. It throws {@link IOException} when the port has failed (a
 * USB-serial adapter that dropped off Windows).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface PrinterPort extends AutoCloseable {

    /**
     * Reads up to {@code buffer.length} bytes, waiting at most the port's read timeout.
     *
     * @return the number of bytes read, 0 on timeout
     * @throws IOException if the port has failed
     */
    int read(byte[] buffer) throws IOException;

    /** Writes every byte, or throws if the port has failed. */
    void write(byte[] data) throws IOException;

    /** Releases the port. Idempotent. */
    @Override
    void close();
}
