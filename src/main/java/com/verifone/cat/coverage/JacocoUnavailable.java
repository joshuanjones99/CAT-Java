package com.verifone.cat.coverage;

/**
 * A JaCoCo tool this run needs is missing or too old. The message says which, and where to put it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class JacocoUnavailable extends RuntimeException {

    public JacocoUnavailable(String message) {
        super(message);
    }
}
