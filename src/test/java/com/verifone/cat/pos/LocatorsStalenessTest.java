package com.verifone.cat.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pos.gen.GenLocators;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The generated catalogue is not stale: regenerating from the copied corpus in memory gives the
 * committed {@code Locators.java}, byte for byte. Mirrors the Python's
 * {@code test_generated_catalogue_is_not_stale}.
 *
 * <p>The committed file is read with CRLF folded to LF, because git's {@code text=auto} may check
 * it out either way on Windows.
 */
class LocatorsStalenessTest {

    private static final Path COMMITTED = Path.of("src", "main", "java", "com", "verifone", "cat", "pos", "Locators.java");

    private static final Path PYTHON_CORPUS = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON_CORPUS", "C:\\gitrepos\\CAT\\analysis\\locators.json"));

    @Test
    void committedLocatorsJavaIsARegenerationOfTheCopiedCorpus() throws IOException {
        JsonNode data;
        try (InputStream in = LocatorsStalenessTest.class.getResourceAsStream("/pos/locators.json")) {
            assertTrue(in != null, "src/main/resources/pos/locators.json is not on the classpath");
            data = new ObjectMapper().readTree(in);
        }
        String fresh = GenLocators.build(data);
        String committed = Files.readString(COMMITTED, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(fresh, committed,
                "Locators.java is stale: re-run com.verifone.cat.pos.gen.GenLocators (see its Javadoc)");
    }

    @Test
    void generationIsDeterministic() throws IOException {
        JsonNode data;
        try (InputStream in = LocatorsStalenessTest.class.getResourceAsStream("/pos/locators.json")) {
            data = new ObjectMapper().readTree(in);
        }
        assertEquals(GenLocators.build(data), GenLocators.build(data));
    }

    @Test
    void theCopiedCorpusIsByteIdenticalToThePythonCatsAnalysisFile() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(PYTHON_CORPUS),
                "the Python CAT's analysis/locators.json is not present: " + PYTHON_CORPUS);
        byte[] copied;
        try (InputStream in = LocatorsStalenessTest.class.getResourceAsStream("/pos/locators.json")) {
            assertTrue(in != null, "src/main/resources/pos/locators.json is not on the classpath");
            copied = in.readAllBytes();
        }
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(PYTHON_CORPUS), copied),
                "src/main/resources/pos/locators.json differs from the Python CAT's analysis/locators.json");
    }
}
