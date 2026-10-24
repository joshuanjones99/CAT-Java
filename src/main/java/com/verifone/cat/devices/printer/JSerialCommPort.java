package com.verifone.cat.devices.printer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import com.verifone.cat.devices.DeviceUnreachable;

/**
 * A real serial port, through jSerialComm, as {@link PrinterPort}. Opened as PATS's
 * {@code TopazPrinterSim} does: 38400 baud, 8 data bits, 1 stop bit, no parity, DTR and RTS asserted,
 * and a 100 ms semi-blocking read so the read loop can notice a stop.
 *
 * <p>Nothing in the tests opens one of these. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class JSerialCommPort implements PrinterPort {

    /** PATS {@code TopazPrinterSim.dump}: 38400 baud, 8N1. */
    public static final int BAUD_RATE = 38400;

    /** The read timeout, as the Python's {@code timeout=0.1}. */
    static final int READ_TIMEOUT_MS = 100;

    private final SerialPort port;
    private final AtomicBoolean closed = new AtomicBoolean();

    private JSerialCommPort(SerialPort port) {
        this.port = port;
    }

    /**
     * Opens {@code device} (e.g. {@code COM1}) and asserts DTR and RTS.
     *
     * @throws DeviceUnreachable if the port will not open
     */
    public static JSerialCommPort open(String device) {
        SerialPort port;
        try {
            port = SerialPort.getCommPort(device);
        } catch (SerialPortInvalidPortException exc) {
            throw unreachable(device, exc.getMessage(), exc);
        }
        port.setComPortParameters(BAUD_RATE, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);
        if (!port.openPort()) {
            throw unreachable(device, "could not open the port (error " + port.getLastErrorCode() + ")", null);
        }
        port.setDTR();
        port.setRTS();
        return new JSerialCommPort(port);
    }

    private static DeviceUnreachable unreachable(String device, String detail, Throwable cause) {
        String message = detail + ". A USB-serial adapter left plugged in can drop off Windows; replug it.";
        String what = "TopazPrinter on " + device;
        return cause == null
                ? new DeviceUnreachable(what, message)
                : new DeviceUnreachable(what, message, cause);
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        int n = port.readBytes(buffer, buffer.length);
        if (n < 0) {
            throw new IOException("read failed on " + port.getSystemPortName() + " (error " + port.getLastErrorCode() + ")");
        }
        return n;
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (port.writeBytes(data, data.length) < 0) {
            throw new IOException("write failed on " + port.getSystemPortName() + " (error " + port.getLastErrorCode() + ")");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            port.closePort();
        }
    }
}
