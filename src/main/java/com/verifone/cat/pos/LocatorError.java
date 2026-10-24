package com.verifone.cat.pos;

/**
 * A locator was used in a way the catalogue cannot satisfy.
 *
 * <p>Port of {@code petrotest.pos.locator_types.LocatorError}. UNVERIFIED AGAINST HARDWARE:
 * this type only builds selector strings; nothing here has touched a POS.
 */
public class LocatorError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LocatorError(String message) {
        super(message);
    }
}
