package com.verifone.cat.build;

/**
 * Artifactory answered with an error status, or did not answer at all.
 *
 * <p>{@link #status()} is the HTTP status, or {@link #NO_RESPONSE} when there was no
 * response (the network, a timeout). {@link ArtifactoryErrors#describe} turns this into one
 * sentence an operator can act on.
 */
public final class ArtifactoryException extends BuildException {

    /** The status for a request that got no response at all. */
    public static final int NO_RESPONSE = 0;

    private final int status;

    public ArtifactoryException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
