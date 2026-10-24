package com.verifone.cat.rig;

/**
 * A rig file is missing, unreadable or invalid. The message names the file, the field path and
 * the expectation, because the person who reads it is the person who mistyped the file.
 *
 * <p>An {@link IllegalArgumentException}, as the loader has always thrown: a caller that caught
 * that keeps working, and a caller that wants to tell a bad file from a bad argument can catch
 * this.
 */
public class RigConfigException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public RigConfigException(String message) {
        super(message);
    }

    public RigConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
