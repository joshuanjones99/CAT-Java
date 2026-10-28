package com.verifone.cat.build;

/**
 * Where the Artifactory login comes from. {@link ArtifactoryClient} takes one of these and
 * asks for the login once, when it is built.
 *
 * <p>{@link EnvArtifactoryCredentials} reads two environment variables;
 * {@link StoredArtifactoryCredentials} reads them, then the Python CAT's DPAPI-encrypted
 * {@code .secrets/artifactory.credentials} and Monarch's file, as the Python's
 * {@code load_credentials}. Writing that file ({@code petrotest artifactory-login}) is not ported.
 *
 * <p>A {@link Login} never appears in a log line, an exception message or a file. Its
 * {@code toString} is redacted.
 */
public interface ArtifactoryCredentials {

    /**
     * The user and password, or {@link ArtifactoryAuthError} when none is available.
     */
    Login login();

    /** The Basic-auth pair. {@code toString} is redacted so it cannot leak into a log. */
    record Login(String user, String password) {

        @Override
        public String toString() {
            return "Login[redacted]";
        }
    }
}
