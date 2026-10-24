package com.verifone.cat.devices.commander;

import com.verifone.cat.devices.DeviceError;

/**
 * A CGILink reply the Commander sent as a fault.
 *
 * <p>The Commander answers HTTP 200 even for a refused command, so the fault is in the XML
 * body. The message is the Python's, {@code "CGILink fault: <code>"}, which its callers match
 * on; {@link #code()} keeps the code apart for a Java caller that wants to branch on it. The
 * Python raises a plain {@code DeviceError}; this is one.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderFault extends DeviceError {

    private final String code;

    public CommanderFault(String code) {
        super("CGILink fault: " + code);
        this.code = code;
    }

    /** The fault code, for example {@code CGIPortal.OTPRequired}. */
    public String code() {
        return code;
    }
}
