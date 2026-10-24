package com.verifone.cat.build;

/**
 * A build could not be named, found, downloaded or verified. The message says what the
 * operator can do about it and never carries a credential.
 *
 * <p>The Python CAT's {@code petrotest.build.nightly.NightlyBuildError}.
 */
public class BuildException extends RuntimeException {

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
