package com.verifone.cat.runconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The saved-choices file: its location, its keys, and what a bad file does. */
class SavedChoicesTest {

    @TempDir
    Path tmp;

    @Test
    void theOverrideEnvWinsOverTheHomeDirectory() {
        assertEquals(Path.of(System.getProperty("user.home"), ".cat", "last-config.json"),
                SavedChoices.defaultPath(Map.of(), System.getProperty("user.home")));
        assertEquals(Path.of("elsewhere.json"),
                SavedChoices.defaultPath(Map.of("CAT_SAVED_CONFIG", "elsewhere.json"), "C:/home"));
    }

    @Test
    void anAbsentFileIsNoChoices() {
        assertTrue(SavedChoices.read(tmp.resolve("none.json")).isEmpty());
    }

    @Test
    void aFileThatIsNotAnObjectIsNoChoices() throws IOException {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "[1, 2, 3]");
        assertTrue(SavedChoices.read(file).isEmpty());
        Files.writeString(file, "{not json");
        assertTrue(SavedChoices.read(file).isEmpty());
    }

    @Test
    void retiredKeysAreDroppedOnRead() throws IOException {
        Path file = tmp.resolve("old.json");
        Files.writeString(file, "{\"config-jacoco\": true, \"config-plans\": [\"PETROCORE-1\"]}");
        Map<String, Object> read = SavedChoices.read(file);
        assertFalse(read.containsKey("config-jacoco"));
        assertEquals(List.of("PETROCORE-1"), read.get("config-plans"));
    }

    @Test
    void aWriteCreatesItsFolderAndReadsBack() {
        Path file = tmp.resolve("cat").resolve("last-config.json");
        Map<String, Object> choices = new LinkedHashMap<>();
        choices.put("config-scope", "tags");
        choices.put("config-tags-cases", null);
        choices.put("config-prerequisites", true);

        assertTrue(SavedChoices.write(file, choices));
        Map<String, Object> read = SavedChoices.read(file);
        assertEquals("tags", read.get("config-scope"));
        assertEquals(null, read.get("config-tags-cases"));
        assertEquals(true, read.get("config-prerequisites"));
    }

    @Test
    void aWriteToAPathThatIsAFileReportsFailureInsteadOfThrowing() throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "a file, not a folder");
        assertFalse(SavedChoices.write(blocker.resolve("last-config.json"), Map.of("config-scope", "whole")));
    }
}
