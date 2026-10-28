package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stored Artifactory login (Monarch's format) with a fake decryptor: the real DPAPI is never called and no real
 * credential file is read. The "blob" here is the UTF-16LE password reversed, which the fake reverses back.
 */
class StoredArtifactoryCredentialsTest {

    @TempDir
    Path dir;

    /** A stand-in for DPAPI: reverses the bytes. Records each blob it is given. */
    private final List<byte[]> asked = new ArrayList<>();
    private final StoredArtifactoryCredentials.Decryptor fake = blob -> {
        asked.add(blob);
        byte[] out = new byte[blob.length];
        for (int i = 0; i < blob.length; i++) {
            out[i] = blob[blob.length - 1 - i];
        }
        return out;
    };

    private static String blobHex(String password) {
        byte[] plain = password.getBytes(StandardCharsets.UTF_16LE);
        byte[] blob = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            blob[i] = plain[plain.length - 1 - i];
        }
        return HexFormat.of().formatHex(blob);
    }

    private Path file(String name, String text) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    @Test
    void monarchsFormatIsReadAndTheUserNormalised() throws IOException {
        Path stored = file("artifactory.credentials",
                "{\"Username\": \"jdoe@verifone.com\", \"Password\": \"" + blobHex("not-a-real-secret") + "\"}");

        ArtifactoryCredentials.Login login =
                new StoredArtifactoryCredentials(Map.of(), List.of(stored), fake).login();

        assertEquals("jdoe", login.user());
        assertEquals("not-a-real-secret", login.password());
        assertEquals(1, asked.size());
    }

    @Test
    void aByteOrderMarkAndUppercaseHexAreAccepted() throws IOException {
        Path stored = file("bom.credentials", "\uFEFF{\"Username\":\"jdoe\",\"Password\":\""
                + blobHex("pw").toUpperCase() + " \"}");

        assertEquals("pw", new StoredArtifactoryCredentials(Map.of(), List.of(stored), fake).login().password());
    }

    @Test
    void theEnvironmentWinsAndNoFileIsDecrypted() throws IOException {
        Path stored = file("artifactory.credentials",
                "{\"Username\": \"file-user\", \"Password\": \"" + blobHex("file-pw") + "\"}");

        ArtifactoryCredentials.Login login = new StoredArtifactoryCredentials(Map.of(
                "ARTIFACTORY_USER", "env-user@verifone.com", "ARTIFACTORY_PASSWORD", "env-pw"), List.of(stored), fake)
                .login();

        assertEquals("env-user", login.user());
        assertEquals("env-pw", login.password());
        assertTrue(asked.isEmpty());
    }

    @Test
    void aFileThatWillNotDecryptOrParseIsSkippedForTheNext() throws IOException {
        Path missing = dir.resolve("absent.credentials");
        Path garbage = file("garbage.credentials", "not json");
        Path noPassword = file("nopw.credentials", "{\"Username\": \"x\"}");
        Path otherAccount = file("other.credentials", "{\"Username\": \"other\", \"Password\": \"00ff\"}");
        Path good = file("good.credentials", "{\"Username\": \"jdoe\", \"Password\": \"" + blobHex("pw") + "\"}");
        StoredArtifactoryCredentials.Decryptor refusesShort = blob -> {
            if (blob.length < 4) {
                throw new IllegalStateException("Key not valid for use in specified state.");
            }
            return fake.unprotect(blob);
        };

        ArtifactoryCredentials.Login login = new StoredArtifactoryCredentials(Map.of(),
                List.of(missing, garbage, noPassword, otherAccount, good), refusesShort).login();

        assertEquals("jdoe", login.user());
    }

    @Test
    void noSourceIsTheNamedErrorWithoutAValue() throws IOException {
        Path otherAccount = file("other.credentials", "{\"Username\": \"other\", \"Password\": \"00ff\"}");
        StoredArtifactoryCredentials.Decryptor refuses = blob -> {
            throw new IllegalStateException("Key not valid for use in specified state.");
        };

        ArtifactoryAuthError error = assertThrows(ArtifactoryAuthError.class,
                () -> new StoredArtifactoryCredentials(Map.of(), List.of(otherAccount), refuses).login());
        assertEquals("ARTIFACTORY_USER", error.userEnv());
        assertFalse(error.getMessage().contains("00ff"));
    }

    @Test
    void aTestsEnvironmentNeverReachesTheStoredFiles() {
        // Only the process's own environment falls back to the files, as the Python's `environ is os.environ`.
        assertInstanceOf(EnvArtifactoryCredentials.class, StoredArtifactoryCredentials.forEnvironment(Map.of()));
        assertInstanceOf(EnvArtifactoryCredentials.class,
                StoredArtifactoryCredentials.forEnvironment(Map.of("CAT_PYTHON_ROOT", "x")));
    }

    @Test
    void theFilesAreThePythonCatsThenMonarchs() {
        assertEquals(List.of(Path.of("D:\\cat").resolve(".secrets").resolve("artifactory.credentials"),
                StoredArtifactoryCredentials.MONARCH_FILES.get(0), StoredArtifactoryCredentials.MONARCH_FILES.get(1)),
                StoredArtifactoryCredentials.defaultFiles(Map.of("CAT_PYTHON_ROOT", "D:\\cat")));
        assertEquals(Path.of("C:\\gitrepos\\CAT\\.secrets\\artifactory.credentials"),
                StoredArtifactoryCredentials.defaultFiles(Map.of()).get(0));
    }

    @Test
    void anEmailLosesItsDomain() {
        assertEquals("jdoe", StoredArtifactoryCredentials.normaliseUsername(" jdoe@verifone.com "));
        assertEquals("jdoe", StoredArtifactoryCredentials.normaliseUsername("jdoe"));
        assertEquals("", StoredArtifactoryCredentials.normaliseUsername("@x"));
    }
}
