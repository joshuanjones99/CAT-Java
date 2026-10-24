package com.verifone.cat.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ArtifactIndexTest {

    @TempDir
    Path base;

    @Test
    void indexListsEveryFileWithItsSizeAndLeavesItselfOut() throws Exception {
        Path run = base.resolve("run-20260804-001");
        Files.createDirectories(run.resolve("PETROCORE-1"));
        Files.write(run.resolve("PETROCORE-1/steps.log"), new byte[1024]);
        Files.write(run.resolve("junit.xml"), new byte[10]);
        Files.write(run.resolve("index.json"), new byte[999]);

        ArtifactIndex.write(run);

        JsonNode index = new ObjectMapper().readTree(run.resolve(ArtifactIndex.INDEX_FILE).toFile());
        assertEquals("run-20260804-001", index.get("run_id").asText());
        assertEquals(2, index.get("artifact_count").asInt());
        assertEquals(1034, index.get("total_size_bytes").asLong());
        assertEquals("1.0 KB", index.get("total_size_human").asText());

        // Entries are listed in path order; look each one up by its path rather than its position.
        JsonNode steps = entry(index, "PETROCORE-1");
        assertEquals(1024, steps.get("size_bytes").asLong());
        assertEquals("1.0 KB", steps.get("size_human").asText());
        JsonNode junit = entry(index, "junit.xml");
        assertEquals("10 B", junit.get("size_human").asText());
        assertFalse(index.toString().contains("index.json"), "the index does not list itself");
    }

    /** The entry whose path starts with {@code prefix}; the path separator is the platform's. */
    private static JsonNode entry(JsonNode index, String prefix) {
        for (JsonNode artifact : index.get("artifacts")) {
            if (artifact.get("path").asText().startsWith(prefix)) {
                return artifact;
            }
        }
        throw new AssertionError("no index entry for " + prefix);
    }

    @Test
    void theRunIdIsTheFoldersNameNotAPath() throws Exception {
        Path run = base.resolve("run-1");
        ArtifactIndex.write(run);

        JsonNode index = new ObjectMapper().readTree(run.resolve(ArtifactIndex.INDEX_FILE).toFile());
        assertEquals("run-1", index.get("run_id").asText());
        assertEquals(0, index.get("artifact_count").asInt());
        assertTrue(index.get("artifacts").isArray());
    }

    @Test
    void sizesReadInTheSameWordsAsThePythonCatalogue() {
        assertEquals("0 B", ArtifactIndex.formatSize(0));
        assertEquals("512 B", ArtifactIndex.formatSize(512));
        assertEquals("1.0 KB", ArtifactIndex.formatSize(1024));
        assertEquals("1.0 MB", ArtifactIndex.formatSize(1024L * 1024));
        assertEquals("1.0 GB", ArtifactIndex.formatSize(1024L * 1024 * 1024));
        assertEquals("1.0 TB", ArtifactIndex.formatSize(1L << 40));
        assertEquals("1.0 PB", ArtifactIndex.formatSize(1L << 50));
        assertEquals("1.5 KB", ArtifactIndex.formatSize(1536));
    }
}
