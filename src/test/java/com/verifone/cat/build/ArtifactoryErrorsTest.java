package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

class ArtifactoryErrorsTest {

    private static final String REPO = "RMS_Nightly_CLW/Petro/master";

    @Test
    void aRejectedLoginIsSaidInWordsAndNeverNamesACredential() {
        for (int code : new int[] {401, 403}) {
            String text = ArtifactoryErrors.describe(
                    new ArtifactoryException("x", code, null), REPO);

            assertTrue(text.contains("rejected the login (HTTP " + code + ")"), text);
            assertTrue(text.contains("ARTIFACTORY_PASSWORD"), text);
            assertTrue(text.contains(REPO), text);
            assertFalse(text.contains("mozilla"), text);
        }
    }

    @Test
    void aMissingLoginSaysHowToSetIt() {
        String text = ArtifactoryErrors.describe(new ArtifactoryAuthError("ARTIFACTORY_USER", "ARTIFACTORY_PASSWORD"), "");

        assertTrue(text.startsWith("no Artifactory login saved"), text);
        assertTrue(text.contains("ARTIFACTORY_USER"), text);
    }

    @Test
    void aMissingPathIsNamedAndAnOtherStatusIsGiven() {
        assertTrue(ArtifactoryErrors.describe(new ArtifactoryException("x", 404, null), REPO)
                .contains("has no " + REPO + " (HTTP 404)"));
        assertTrue(ArtifactoryErrors.describe(new ArtifactoryException("x", 500, null), "")
                .equals("Artifactory answered HTTP 500"));
    }

    @Test
    void aTimeoutAndANetworkFailureAreNotCalledABadLogin() {
        String timeout = ArtifactoryErrors.describe(new ArtifactoryException("x", ArtifactoryException.NO_RESPONSE,
                new HttpTimeoutException("timed out")), REPO);
        String network = ArtifactoryErrors.describe(new ArtifactoryException("refused",
                ArtifactoryException.NO_RESPONSE, new java.io.IOException("refused")), REPO);

        assertTrue(timeout.contains("did not answer in time"), timeout);
        assertTrue(network.contains("cannot reach Artifactory"), network);
        assertFalse(network.contains("rejected"), network);
    }
}
