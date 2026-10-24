package com.verifone.cat.validate;

/**
 * No baseline exists for this test yet. A subtype of {@link ValidationFailedException}, so a
 * missing baseline fails the test rather than skipping it.
 */
public final class SnapshotMissingException extends ValidationFailedException {

    private static final long serialVersionUID = 1L;

    public SnapshotMissingException(String message) {
        super(message);
    }
}
