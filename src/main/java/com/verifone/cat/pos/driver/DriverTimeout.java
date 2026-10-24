package com.verifone.cat.pos.driver;

/**
 * A bounded wait ran out. Port of the Python {@code recovery.timeouts.TimeoutExpired} as the POS
 * layer sees it; a distinct type so a caller can tell "not yet" from a broken probe.
 */
public final class DriverTimeout extends DriverError {

    private static final long serialVersionUID = 1L;

    public DriverTimeout(String message) {
        super(message);
    }
}
