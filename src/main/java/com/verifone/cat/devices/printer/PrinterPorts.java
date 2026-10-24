package com.verifone.cat.devices.printer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.fazecast.jSerialComm.SerialPort;

/**
 * Serial ports the OS lists right now that could be the printer's. Ported from the Python
 * {@code petrotest.devices.topaz_printer.visible_serial_ports}.
 *
 * <p>A PC's own built-in COM port is left out: the printer cable reaches the controller through a
 * USB-serial adapter (HANDOFF.md 1.34), and a built-in port is listed and opens with nothing cabled
 * to it. The Python tells the built-in port by its hardware ID ({@code ACPI\PNP0500} or
 * {@code PNP0501}), which jSerialComm does not expose. This port uses the closest it offers: a
 * built-in port is described as a "Communications Port" and carries no USB vendor ID. That mapping
 * is untested on a rig.
 *
 * <p>Listing opens nothing. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PrinterPorts {

    /** One port as the OS describes it. {@code description} and the IDs may be null or -1. */
    public record PortInfo(String name, String description, int vendorId, int productId) {}

    private static final String BUILT_IN_DESCRIPTION = "communications port";

    private PrinterPorts() {}

    /** The ports the OS lists now, built-in ones left out. Opens none of them. */
    public static List<String> visible() {
        List<PortInfo> listed = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            listed.add(new PortInfo(
                    port.getSystemPortName(),
                    port.getPortDescription(),
                    port.getVendorID(),
                    port.getProductID()));
        }
        return visible(listed);
    }

    /** The filter behind {@link #visible()}, over ports already listed. */
    public static List<String> visible(Collection<PortInfo> ports) {
        List<String> out = new ArrayList<>();
        for (PortInfo port : ports) {
            if (!isBuiltIn(port)) {
                out.add(port.name());
            }
        }
        return out;
    }

    static boolean isBuiltIn(PortInfo port) {
        String description = port.description() == null ? "" : port.description().trim();
        return description.toLowerCase(Locale.ROOT).startsWith(BUILT_IN_DESCRIPTION)
                && port.vendorId() < 0;
    }
}
