package com.verifone.cat.pats.runner;

/**
 * A PATS validation that failed: recorded, and the case carries on (PATS's "Validation Failed."). Thrown
 * by a handler, never caught by one. Ported from the Python {@code core._Soft}, which was private; a
 * handler family needs to throw it, so it is public here.
 */
public class ValidationFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidationFailure(String message) {
        super(message);
    }
}
