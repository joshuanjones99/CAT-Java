package com.verifone.cat.validate.tlog;

/**
 * The TLog could not be parsed as XML. A truncated TLog is a capture failure, not a content
 * mismatch, so it is raised loudly rather than compared (the Python CAT's {@code TlogParseError}).
 */
public final class TlogParseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public TlogParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
