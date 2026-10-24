package com.verifone.cat.pats.runner.handlers.cloudsettle;

/** The Commander's log could not be read (the Python's {@code TransportError} from the SSH read). */
public final class CommanderLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CommanderLogException(String message, Throwable cause) {
        super(message, cause);
    }

    public CommanderLogException(String message) {
        super(message);
    }
}
