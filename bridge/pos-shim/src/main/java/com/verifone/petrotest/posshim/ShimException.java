package com.verifone.petrotest.posshim;

/**
 * A failure with an HTTP status and a machine-readable code.
 *
 * <p>The code matters as much as the message. `petrotest` distinguishes a probe
 * that could not run from a probe that ran and failed (§6.2: "ERROR and TIMEOUT
 * are the probe"), and it cannot make that distinction from prose. So every
 * error this shim returns carries a stable {@code code} the Python client
 * branches on — {@code no-clients} and {@code unsupported} become ERROR, while a
 * real answer of {@code false} becomes FAIL — and a human-readable
 * {@code message} that says which box to go look at.
 */
final class ShimException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    ShimException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
