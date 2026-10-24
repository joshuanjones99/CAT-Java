package com.verifone.cat.pos.driver;

/**
 * The POS session could not do what was asked. Port of {@code petrotest.pos.driver.DriverError}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class DriverError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DriverError(String message) {
        super(message);
    }

    public DriverError(String message, Throwable cause) {
        super(message, cause);
    }
}
