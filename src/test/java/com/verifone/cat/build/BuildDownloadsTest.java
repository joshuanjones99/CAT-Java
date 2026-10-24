package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class BuildDownloadsTest {

    private static final String FOLDER = "RMS_Nightly_CLW/Petro/Solutions/1082/buypass";

    @TempDir
    Path dir;

    private static byte[] overlay() {
        return BuildFixtures.overlay(BuildFixtures.at(2026, 10, 7, 9, 0));
    }

    @Test
    void theFilesAreFetchedAndALaterRunSkipsThoseAlreadyAtTheRightSize() throws IOException {
        byte[] overlay = overlay();
        byte[] jar = "psi".getBytes();
        Map<String, Object> dist = Map.of("overlay.zip", overlay, "Petro_Suite_Installer.jar", jar);
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("RMS_Nightly_CLW", Map.of("Petro", Map.of(
                "Solutions", Map.of("1082", Map.of("buypass", dist))))))) {
            ArtifactoryClient client = fake.client();
            List<String> names = List.of("overlay.zip", "Petro_Suite_Installer.jar");
            List<String> log = new ArrayList<>();

            BuildDownloads.fetchFiles(client, FOLDER, names, dir, log::add);
            assertArrayEquals(overlay, Files.readAllBytes(dir.resolve("overlay.zip")));
            assertArrayEquals(jar, Files.readAllBytes(dir.resolve("Petro_Suite_Installer.jar")));
            assertEquals(2, fake.downloads().size());

            BuildDownloads.fetchFiles(client, FOLDER, names, dir, log::add);
            assertEquals(2, fake.downloads().size());
            assertTrue(log.stream().anyMatch(line -> line.contains("already downloaded")));
        }
    }

    @Test
    void aFileNotOnArtifactoryIsANamedError() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of())) {
            BuildException error = assertThrows(BuildException.class, () -> BuildDownloads.fetchFile(
                    fake.client(), FOLDER + "/overlay.zip", dir.resolve("overlay.zip"), line -> { }));

            assertTrue(error.getMessage().contains("is not on Artifactory"));
            assertFalse(Files.exists(dir.resolve("overlay.zip")));
        }
    }

    @Test
    void aPartDownloadNeverSatisfiesTheSizeCheckOnTheNextRun() throws IOException {
        byte[] overlay = overlay();
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("overlay.zip", overlay)))) {
            // A cut-short file under the final name: the size check refuses to take it for finished.
            Files.write(dir.resolve("overlay.zip"), new byte[3]);

            BuildDownloads.fetchFile(fake.client(), "repo/overlay.zip", dir.resolve("overlay.zip"), line -> { });

            assertArrayEquals(overlay, Files.readAllBytes(dir.resolve("overlay.zip")));
        }
    }
}
