package com.verifone.cat.build;

/**
 * No Artifactory login is available: neither of the two environment variables is set.
 *
 * <p>Raised instead of prompting. CAT runs unattended, so there is no console to ask on.
 * The Python CAT's {@code petrotest.build.artifactory.ArtifactoryAuthError}.
 */
public final class ArtifactoryAuthError extends BuildException {

    private final String userEnv;
    private final String passwordEnv;

    public ArtifactoryAuthError(String userEnv, String passwordEnv) {
        super("Artifactory credentials not set: set " + userEnv + " and " + passwordEnv
                + " in the environment (CAT never prompts for them and never takes them inline)");
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
