package com.verifone.cat.pats.runner;

/**
 * A hard PATS failure: the action could not be done, so the case stops there. Ported from the Python
 * {@code PatsStepError}. The runner raises it with the case key and the action in front, and records the
 * step as failed before it does.
 */
public class PatsStepError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PatsStepError(String message) {
        super(message);
    }

    public PatsStepError(String message, Throwable cause) {
        super(message, cause);
    }
}
