package com.verifone.cat.devices;

/**
 * A device call that did not produce an answer.
 *
 * <p>The subclasses carry the distinction CAT's preflight depends on (DESIGN.md §6.2
 * in the Python CAT): an ERROR is the probe, a FAIL is the rig. None of these is a
 * FAIL. A caller that turns one into {@code false} reports a healthy rig as broken.
 */
public class DeviceError extends RuntimeException {

    public DeviceError(String message) {
        super(message);
    }

    public DeviceError(String message, Throwable cause) {
        super(message, cause);
    }
}
