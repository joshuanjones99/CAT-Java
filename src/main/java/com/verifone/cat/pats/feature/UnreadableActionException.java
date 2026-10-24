package com.verifone.cat.pats.feature;

/**
 * An action no phrase writes so that it reads back the same. Port of {@code petrotest.pats.feature.UnreadableAction},
 * a {@code ValueError}, so it is an {@link IllegalArgumentException} here.
 */
public class UnreadableActionException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public UnreadableActionException(String message) {
        super(message);
    }

    public UnreadableActionException(String message, Throwable cause) {
        super(message, cause);
    }
}
