package com.verifone.cat.pats.runner.handlers.configuration;

/** A cloud request that could not be sent or answered (the Python's {@code csite.CsiteError}). */
public class CloudRequestException extends Exception {

    private static final long serialVersionUID = 1L;

    public CloudRequestException(String message) {
        super(message);
    }

    public CloudRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
