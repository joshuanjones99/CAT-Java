package com.verifone.cat.validate;

/**
 * A receipt or TLog did not match its baseline, or there was no baseline to match.
 *
 * <p>The Python CAT raises {@code AssertionError} here, deliberately: a test with no snapshot
 * has not passed, and a silently skipped assertion is how a green nightly stops meaning
 * anything (DESIGN.md 13.9). This is the Java equivalent. The message names what differed.
 */
public class ValidationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidationFailedException(String message) {
        super(message);
    }
}
