package com.verifone.cat.build;

/**
 * No Artifactory login is available: neither the two environment variables nor a saved login.
 *
 * <p>Raised instead of prompting. CAT runs unattended, so there is no console to ask on; the
 * one prompt is {@code cat-java artifactory-login}'s, run by hand. The Python CAT's
 * {@code petrotest.build.artifactory.ArtifactoryAuthError}, its wording with CAT-Java's command.
 */
public final class ArtifactoryAuthError extends BuildException {

    private final String userEnv;
    private final String passwordEnv;

    public ArtifactoryAuthError(String userEnv, String passwordEnv) {
        super("Artifactory credentials not set: export " + userEnv + " and " + passwordEnv
                + ", or run `cat-java artifactory-login` once (never inline a credential).");
        this.userEnv = userEnv;
        this.passwordEnv = passwordEnv;
    }

    public String userEnv() {
        return userEnv;
    }

    public String passwordEnv() {
        return passwordEnv;
    }
}
