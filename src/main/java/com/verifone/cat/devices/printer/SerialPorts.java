package com.verifone.cat.devices.printer;

import java.util.List;
import java.util.Optional;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;

/**
 * The serial ports Windows lists now, and whether one opens: what System Checks' printer lines and Connections'
 * Receipt Printer row ask. The Python CAT's {@code topaz_printer.visible_serial_ports} and
 * {@code cat.readiness._try_open}, behind one seam so tests pass a fake listing and never touch a COM port.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): {@link #SYSTEM} lists through jSerialComm ({@link PrinterPorts#visible}),
 * which leaves the PC's own built-in port out by its description, not by its PnP ID as the Python does.
 */
public interface SerialPorts {

    /** The ports that could be the printer's, built-in ones left out. Opens nothing. */
    List<String> listed();

    /** Empty when {@code port} opens (and is closed again), else why it would not. */
    Optional<String> openProblem(String port);

    /** This controller's ports, through jSerialComm. */
    SerialPorts SYSTEM = new SerialPorts() {
        @Override
        public List<String> listed() {
            return PrinterPorts.visible();
        }

        @Override
        public Optional<String> openProblem(String port) {
            SerialPort serial;
            try {
                serial = SerialPort.getCommPort(port);
            } catch (SerialPortInvalidPortException e) {
                return Optional.of("will not open: " + e.getMessage());
            }
            if (!serial.openPort()) {
                return Optional.of("will not open: error " + serial.getLastErrorCode());
            }
            serial.closePort();
            return Optional.empty();
        }
    };
}
