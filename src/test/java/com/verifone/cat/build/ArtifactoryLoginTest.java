package com.verifone.cat.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code cat-java artifactory-login} with scripted answers and a stand-in for DPAPI (it reverses the bytes), writing
 * into a temp folder: the file it writes is the Python's format, and {@link StoredArtifactoryCredentials} reads it
 * back. No test touches {@code ~/.cat} or the real DPAPI.
 */
class ArtifactoryLoginTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private static byte[] reverse(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[bytes.length - 1 - i];
        }
        return out;
    }

    private static ArtifactoryLogin.Prompt answers(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return new ArtifactoryLogin.Prompt() {
            @Override
            public String line(String prompt) {
                return queue.poll();
            }

            @Override
            public String secret(String prompt) {
                return queue.poll();
            }
        };
    }

    private int run(Path file, String... lines) {
        return new ArtifactoryLogin(answers(lines), ArtifactoryLoginTest::reverse, file)
                .run(new String[0], new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @Test
    void theLoginIsSavedInMonarchsFormatAndReadsBack() throws Exception {
        Path file = dir.resolve(".cat").resolve("java").resolve("artifactory.credentials");
        assertEquals(0, run(file, " jdoe@verifone.com ", "s3cret!"));

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("{\"Username\": \"jdoe\", \"Password\": \""), text);
        assertFalse(text.contains("s3cret"), "the password is never in the clear");
        assertEquals("saved to " + file + " (DPAPI, this Windows account only)",
                outBytes.toString(StandardCharsets.UTF_8).strip());
        ArtifactoryCredentials.Login login = StoredArtifactoryCredentials.read(file, ArtifactoryLoginTest::reverse)
                .orElseThrow();
        assertEquals("jdoe", login.user());
        assertEquals("s3cret!", login.password());
    }

    @Test
    void nothingIsSavedWithoutBothAnswers() {
        Path file = dir.resolve("artifactory.credentials");
        assertEquals(1, run(file, "jdoe", ""));
        assertEquals(1, run(file, "@verifone.com", "pw"));
        assertEquals(1, run(file));
        assertFalse(Files.exists(file));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8)
                .contains("nothing saved: username and password are both required"));
    }

    @Test
    void theRealFileIsUnderCatJava() {
        assertEquals(Path.of("H:\\u", ".cat", "java", "artifactory.credentials"),
                StoredArtifactoryCredentials.catJavaFile("H:\\u"));
    }
}
