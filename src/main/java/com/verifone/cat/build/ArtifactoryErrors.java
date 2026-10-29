package com.verifone.cat.build;

import java.net.http.HttpTimeoutException;

/**
 * An Artifactory failure as one sentence an operator can act on.
 *
 * <p>A bare HTTP 403 says nothing about what to do. The Python CAT found this on a second
 * controller (2026-10-08): a wrong password read as a 403 on the storage listing, and the
 * operator could not tell it from an outage. So a 401 or 403 is said to be a rejected login,
 * and a transport failure is said to be the network. The text never names the user or the
 * password.
 *
 * <p>The Python CAT's {@code petrotest.build.artifactory.describe_error}.
 */
public final class ArtifactoryErrors {

    /** What to do with a rejected or missing login: the Python's hint, with CAT-Java's own login command. */
    static final String REENTER_HINT =
            "re-enter it with `cat-java artifactory-login`, or set ARTIFACTORY_USER and ARTIFACTORY_PASSWORD";

    private ArtifactoryErrors() {}

    /**
     * @param exc      the failure, as the client threw it
     * @param repoPath the repo path being read, or {@code ""} when none is known
     */
    public static String describe(Throwable exc, String repoPath) {
        String where = repoPath == null || repoPath.isEmpty() ? "" : " " + repoPath;
        if (exc instanceof ArtifactoryAuthError) {
            return "no Artifactory login saved: " + REENTER_HINT;
        }
        if (exc instanceof ArtifactoryException failure) {
            int code = failure.status();
            if (code == 401 || code == 403) {
                return "Artifactory rejected the login (HTTP " + code + "): the username or password "
                        + "is wrong, the password has changed or expired, the account is locked after "
                        + "failed tries, or it may not read" + where + ". Check it by signing in to the "
                        + "Artifactory web UI in a browser, then " + REENTER_HINT;
            }
            if (code == 404) {
                return "Artifactory has no" + (where.isEmpty() ? " such path" : where) + " (HTTP 404)";
            }
            if (code > 0) {
                return "Artifactory answered HTTP " + code + (where.isEmpty() ? "" : " for" + where);
            }
            if (failure.getCause() instanceof HttpTimeoutException) {
                return "Artifactory did not answer in time: the server is slow or unreachable";
            }
            return "cannot reach Artifactory (" + failure.getMessage() + "): check the network or VPN";
        }
        return "Artifactory: " + exc.getClass().getSimpleName() + ": " + exc.getMessage();
    }
}
