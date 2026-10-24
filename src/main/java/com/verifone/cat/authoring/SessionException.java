package com.verifone.cat.authoring;

/** An author's input or a session file the authoring code will not act on. Nothing was changed when it is thrown. */
public class SessionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
