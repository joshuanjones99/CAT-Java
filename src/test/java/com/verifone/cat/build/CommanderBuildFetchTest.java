package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link CommanderBuildFetch} against the loopback Artifactory fake: evidence about this code, not the server. */
class CommanderBuildFetchTest {

    @TempDir
    Path root;

    @Test
    void thePsiFilesLandInTheBuildsDistFolderMatchedCaseInsensitively() throws IOException {
        byte[] overlay = BuildFixtures.overlay(BuildFixtures.at(2026, 10, 7, 9, 0));
        Map<String, Object> dist = Map.of("overlay.zip", overlay, "Petro_Suite_Installer.jar", "psi".getBytes(),
                "Auto_Upgrade.jar", "au".getBytes());
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("RMS_Nightly_CLW", Map.of("Petro", Map.of(
                "Solutions", Map.of("134", Map.of("Buypass", dist))))))) {
            List<String> log = new ArrayList<>();
            Path dir = CommanderBuildFetch.fetch(fake.client(), "Solutions", "134", "buypass",
                    CommanderBuildFetch.PSI_FILES, root, log::add);

            assertEquals(root.resolve("Solutions134").resolve("buypass"), dir);
            assertArrayEquals(overlay, Files.readAllBytes(dir.resolve("overlay.zip")));
            assertArrayEquals("psi".getBytes(), Files.readAllBytes(dir.resolve("Petro_Suite_Installer.jar")));
            assertArrayEquals("au".getBytes(), Files.readAllBytes(dir.resolve("Auto_Upgrade.jar")));
            assertTrue(log.get(0).startsWith("Solutions 134, Buypass -> "), log.toString());
        }
    }

    @Test
    void aViperOnlyBuildSaysTheMergeIsUnavailable() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("RMS_Nightly_CLW", Map.of("Petro", Map.of(
                "master", Map.of("268", Map.of("viper-buypass", Map.of("x", new byte[1])))))))) {
            BuildException e = assertThrows(BuildException.class, () -> CommanderBuildFetch.fetch(fake.client(),
                    "master", "268", "buypass", CommanderBuildFetch.PSI_FILES, root, line -> { }));
            assertTrue(e.getMessage().contains("holds only viper-buypass"), e.getMessage());
            assertTrue(e.getMessage().contains("unavailable in CAT-Java"), e.getMessage());
        }
    }

    @Test
    void aBuildNotOnArtifactoryIsANamedError() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of())) {
            BuildException e = assertThrows(BuildException.class, () -> CommanderBuildFetch.fetch(fake.client(),
                    "Solutions", "1", "buypass", CommanderBuildFetch.PSI_FILES, root, line -> { }));
            assertTrue(e.getMessage().contains("is not on Artifactory"), e.getMessage());
        }
    }
}
