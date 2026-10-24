package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ArtifactoryCredentialsTest {

    @Test
    void bothVariablesSetGiveTheLogin() {
        ArtifactoryCredentials.Login login = new EnvArtifactoryCredentials(Map.of(
                "ARTIFACTORY_USER", "jdoe", "ARTIFACTORY_PASSWORD", "not-a-real-secret")).login();

        assertEquals("jdoe", login.user());
        assertEquals("not-a-real-secret", login.password());
    }

    @Test
    void noVariablesIsANamedErrorNotAPrompt() {
        ArtifactoryAuthError error = assertThrows(ArtifactoryAuthError.class,
                () -> new EnvArtifactoryCredentials(Map.of()).login());

        assertTrue(error.getMessage().contains("ARTIFACTORY_USER"));
        assertTrue(error.getMessage().contains("ARTIFACTORY_PASSWORD"));
    }

    @Test
    void aBlankVariableCountsAsUnset() {
        assertThrows(ArtifactoryAuthError.class, () -> new EnvArtifactoryCredentials(Map.of(
                "ARTIFACTORY_USER", "jdoe", "ARTIFACTORY_PASSWORD", "  ")).login());
        assertThrows(ArtifactoryAuthError.class, () -> new EnvArtifactoryCredentials(Map.of(
                "ARTIFACTORY_USER", "", "ARTIFACTORY_PASSWORD", "x")).login());
    }

    @Test
    void theLoginNeverPrintsItsPassword() {
        ArtifactoryCredentials.Login login = new ArtifactoryCredentials.Login("jdoe", "hunter2-secret");

        assertFalse(login.toString().contains("hunter2-secret"));
        assertFalse(login.toString().contains("jdoe"));
    }

    @Test
    void theErrorNamesTheVariablesAndNeverAValue() {
        ArtifactoryAuthError error = assertThrows(ArtifactoryAuthError.class,
                () -> new EnvArtifactoryCredentials(Map.of("ARTIFACTORY_PASSWORD", "hunter2-secret")).login());

        assertFalse(error.getMessage().contains("hunter2-secret"));
        assertEquals("ARTIFACTORY_USER", error.userEnv());
        assertEquals("ARTIFACTORY_PASSWORD", error.passwordEnv());
    }
}
