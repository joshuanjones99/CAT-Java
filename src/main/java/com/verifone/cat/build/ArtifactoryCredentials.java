package com.verifone.cat.build;

/**
 * Where the Artifactory login comes from. {@link ArtifactoryClient} takes one of these and
 * asks for the login once, when it is built.
 *
 * <p>The only implementation is {@link EnvArtifactoryCredentials}, which reads two
 * environment variables. The Python CAT also keeps the login in a DPAPI-encrypted file in
 * {@code .secrets/}, written by {@code petrotest artifactory-login}. Reading that file, and
 * writing it, is a later slice: it would be a second implementation of this interface.
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
