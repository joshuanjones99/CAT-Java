package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.ArtifactoryClient.FileInfo;

class ArtifactoryClientTest {

    private static final String REPO = "RMS_Nightly_CLW/Petro/master";

    @TempDir
    Path dir;

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    @Test
    void theLoginIsAskedForOnceWhenTheClientIsBuilt() {
        assertThrows(ArtifactoryAuthError.class, () -> new ArtifactoryClient(
                URI.create("http://127.0.0.1:9/artifactory"),
                new EnvArtifactoryCredentials(Map.of()), Duration.ofSeconds(5)));
    }

    @Test
    void listFolderParsesChildrenInServerOrderAndSendsTheLogin() {
        // A LinkedHashMap, so the fake lists the children in the order written here.
        Map<String, Object> petro = new LinkedHashMap<>();
        petro.put("master", FakeArtifactory.dir("2026-08-20T00:00:00Z", "60", FakeArtifactory.dir("")));
        petro.put("readme.txt", "hello".getBytes());
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("RMS_Nightly_CLW", Map.of("Petro", petro)))) {
            List<ArtifactoryEntry> entries = fake.client().listFolder("/RMS_Nightly_CLW/Petro/");

            assertEquals(List.of("master", "readme.txt"), entries.stream().map(ArtifactoryEntry::name).toList());
            assertTrue(entries.get(0).folder());
            assertEquals("", entries.get(0).lastModified());
            assertFalse(entries.get(1).folder());
        }
    }

    @Test
    void aRejectedLoginIsAnHttpErrorWithItsStatus() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of())) {
            ArtifactoryClient wrong = new ArtifactoryClient(URI.create(fake.baseUrl()),
                    () -> new ArtifactoryCredentials.Login("u", "wrong"), Duration.ofSeconds(5));

            ArtifactoryException error = assertThrows(ArtifactoryException.class,
                    () -> wrong.listFolder(REPO));
            assertEquals(401, error.status());
        }
    }

    @Test
    void aMissingFolderIsAn404WithItsStatus() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of())) {
            ArtifactoryException error = assertThrows(ArtifactoryException.class,
                    () -> fake.client().listFolder("nowhere"));
            assertEquals(404, error.status());
        }
    }

    @Test
    void listTreeAsksForTheFileListWithTimesOneLevelDeep() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of(
                "60", FakeArtifactory.dir("2026-10-07T09:00:00.000Z"),
                "61", FakeArtifactory.dir("2026-10-08T09:00:00.000Z"))))) {
            List<ArtifactoryEntry> entries = fake.client().listTree("repo", 1);

            assertEquals(List.of("repo?list&deep=0&listFolders=1"),
                    fake.requests.stream().map(r -> r.replace("/api/storage/", "")).toList());
            assertEquals(2, entries.size());
            ArtifactoryEntry sixty = entries.stream().filter(e -> e.name().equals("60")).findFirst().orElseThrow();
            assertEquals("2026-10-07T09:00:00.000Z", sixty.lastModified());
        }
    }

    @Test
    void listTreeWithADepthReturnsTheNestedEntriesWithTheirFullRelativeUris() {
        Map<String, Object> sixty = FakeArtifactory.dir("2026-10-07T09:00:00.000Z",
                "simulators", FakeArtifactory.dir("2026-10-07T09:01:00.000Z"));
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("60", sixty)))) {
            List<ArtifactoryEntry> entries = fake.client().listTree("repo", 2);

            assertTrue(entries.stream().anyMatch(e -> e.uri().equals("/60/simulators") && e.folder()));
            assertTrue(fake.requests.get(0).contains("deep=1&depth=2"));
        }
    }

    @Test
    void fileInfoReadsTheSizeAndTheSha256() {
        byte[] bytes = "overlay bytes".getBytes();
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("overlay.zip", bytes)))) {
            FileInfo info = fake.client().fileInfo("repo/overlay.zip");

            assertEquals(bytes.length, info.size());
            assertEquals(64, info.sha256().length());
        }
    }

    @Test
    void aSmallFileIsStreamedWholeAndLeavesNoPartFile() throws IOException {
        byte[] bytes = payload(100);
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("a.jar", bytes)))) {
            Path dest = dir.resolve("nested").resolve("a.jar");

            Path result = fake.client().downloadFile(fake.client().fileUrl("repo/a.jar"), dest);

            assertEquals(dest, result);
            assertArrayEquals(bytes, Files.readAllBytes(dest));
            assertFalse(Files.exists(dir.resolve("nested").resolve("a.jar.part")));
            assertTrue(fake.ranges.isEmpty());
        }
    }

    @Test
    void aLargeFileWithAKnownSizeIsFetchedAsParallelRanges() throws IOException {
        byte[] bytes = payload(10240);
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("overlay.zip", bytes)))) {
            ArtifactoryClient client = fake.client(16);
            FileInfo info = client.fileInfo("repo/overlay.zip");
            Path dest = dir.resolve("overlay.zip");

            client.downloadFile(client.fileUrl("repo/overlay.zip"), dest, info.size(), info.sha256(), 6);

            assertArrayEquals(bytes, Files.readAllBytes(dest));
            assertEquals(sha256Hex(bytes), ArtifactoryClient.sha256Of(dest));
            assertFalse(Files.exists(dir.resolve("overlay.zip.part")));

            // The ranges arrive in no fixed order, so compare the sorted set of what was asked for.
            // Slice = ceil(10240 / 6) = 1707; the last slice ends at size - 1.
            List<String> expected = List.of("bytes=0-1706", "bytes=1707-3413", "bytes=3414-5120",
                    "bytes=5121-6827", "bytes=6828-8534", "bytes=8535-10239");
            List<String> asked = fake.ranges.stream().sorted().toList();
            assertEquals(expected.stream().sorted().toList(), asked);

            // Coverage: sorted by start, each range begins one byte after the last ended, from 0 to size - 1.
            long next = 0;
            for (String range : expected) {
                String[] bounds = range.replace("bytes=", "").split("-");
                assertEquals(next, Long.parseLong(bounds[0]), "gap or overlap before " + range);
                next = Long.parseLong(bounds[1]) + 1;
            }
            assertEquals(bytes.length, next);
        }
    }

    @Test
    void aSmallFileWithAKnownSizeIsStillStreamedWhole() throws IOException {
        byte[] bytes = payload(100);
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("b.jar", bytes)))) {
            ArtifactoryClient client = fake.client();

            client.downloadFile(client.fileUrl("repo/b.jar"), dir.resolve("b.jar"), 100L, null, 6);

            assertTrue(fake.ranges.isEmpty());
            assertArrayEquals(bytes, Files.readAllBytes(dir.resolve("b.jar")));
        }
    }

    @Test
    void aFailedDownloadLeavesNoDestinationFile() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of())) {
            Path dest = dir.resolve("overlay.zip");

            ArtifactoryException error = assertThrows(ArtifactoryException.class,
                    () -> fake.client().downloadFile(fake.client().fileUrl("repo/overlay.zip"), dest));

            assertEquals(404, error.status());
            assertFalse(Files.exists(dest));
        }
    }

    @Test
    void aSha256MismatchIsRefusedAndLeavesNoFile() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("a.jar", payload(50))))) {
            ArtifactoryClient client = fake.client();
            Path dest = dir.resolve("a.jar");

            BuildException error = assertThrows(BuildException.class, () ->
                    client.downloadFile(client.fileUrl("repo/a.jar"), dest, null, "0".repeat(64), 1));

            assertTrue(error.getMessage().contains("sha256"));
            assertFalse(Files.exists(dest));
            assertFalse(Files.exists(dir.resolve("a.jar.part")));
        }
    }

    @Test
    void aPartFailedParallelDownloadLeavesNoDestinationAndNoPartFile() {
        byte[] bytes = payload(10240);
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("overlay.zip", bytes)))) {
            fake.failRangeStarts.add(1707L);
            ArtifactoryClient client = fake.client(16);
            Path dest = dir.resolve("overlay.zip");

            assertThrows(ArtifactoryException.class, () ->
                    client.downloadFile(client.fileUrl("repo/overlay.zip"), dest, (long) bytes.length, null, 6));

            assertFalse(Files.exists(dest));
            assertFalse(Files.exists(dir.resolve("overlay.zip.part")));
        }
    }

    @Test
    void aSizeMismatchIsRefused() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("a.jar", payload(50))))) {
            ArtifactoryClient client = fake.client();
            Path dest = dir.resolve("a.jar");

            assertThrows(BuildException.class, () ->
                    client.downloadFile(client.fileUrl("repo/a.jar"), dest, 49L, null, 1));
            assertFalse(Files.exists(dest));
        }
    }

    @Test
    void theRepoPathIsEncodedSegmentBySegmentWithSpacesAsPercent20() {
        ArtifactoryClient client = new ArtifactoryClient(URI.create("http://127.0.0.1:9/artifactory"),
                FakeArtifactory.validCredentials(), Duration.ofSeconds(5));

        assertEquals("http://127.0.0.1:9/artifactory/RMS/a%20b/c.zip", client.fileUrl("RMS/a b/c.zip"));
        assertEquals("a%20b/c", ArtifactoryClient.encodePath("a b/c"));
    }

    @Test
    void aStorageReplyWithoutASizeIsAnError() {
        try (FakeArtifactory fake = new FakeArtifactory(Map.of("repo", Map.of("folder", Map.of())))) {
            assertThrows(BuildException.class, () -> fake.client().fileInfo("repo/folder"));
        }
    }

    @Test
    void anUnreachableServerIsNoResponseNotAnHttpStatus() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        ArtifactoryClient client = new ArtifactoryClient(
                URI.create("http://127.0.0.1:" + freePort + "/artifactory"),
                FakeArtifactory.validCredentials(), Duration.ofSeconds(5));

        ArtifactoryException error = assertThrows(ArtifactoryException.class, () -> client.listFolder("x"));

        assertEquals(ArtifactoryException.NO_RESPONSE, error.status());
    }
}
