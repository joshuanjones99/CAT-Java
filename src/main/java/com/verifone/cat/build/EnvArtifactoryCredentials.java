package com.verifone.cat.build;

import java.util.Map;

/**
 * The Artifactory login from two environment variables: {@value #USER_ENV} and
 * {@value #PASSWORD_ENV}. These are the names the Python CAT reads, so one setting serves
 * both.
 *
 * <p>The environment is passed in as a {@link Map}, so a test never touches the real
 * environment. A variable that is unset or blank counts as unset, and a missing login is an
 * {@link ArtifactoryAuthError} naming both variables.
 */
public final class EnvArtifactoryCredentials implements ArtifactoryCredentials {

    /** The environment variable that names the Artifactory user. */
    public static final String USER_ENV = "ARTIFACTORY_USER";

    /** The environment variable that holds the Artifactory password or API key. */
    public static final String PASSWORD_ENV = "ARTIFACTORY_PASSWORD";

    private final Map<String, String> env;

    /** @param env the environment to read, normally {@code System.getenv()} */
    public EnvArtifactoryCredentials(Map<String, String> env) {
        this.env = env;
    }

    @Override
    public Login login() {
        String user = env.getOrDefault(USER_ENV, "");
        String password = env.getOrDefault(PASSWORD_ENV, "");
        if (user.isBlank() || password.isBlank()) {
            throw new ArtifactoryAuthError(USER_ENV, PASSWORD_ENV);
        }
        return new Login(user, password);
    }
}
