package com.verifone.cat.pats;

/**
 * A scenario line no PATS step definition matches, or a table the step cannot take. Port of
 * {@code petrotest.pats.scenario.UnknownStep} (a {@code ValueError} there).
 */
public class UnknownStepException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnknownStepException(String message) {
        super(message);
    }
}
