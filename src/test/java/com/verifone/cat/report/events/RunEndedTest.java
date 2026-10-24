package com.verifone.cat.report.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunEndedTest {

    @TempDir
    Path dir;

    @Test
    void theMarkerHoldsTheTimeInPythonsIsoSpelling() throws IOException {
        Path run = Files.createDirectories(dir.resolve("run-1"));
        RunEnded.mark(run, Instant.parse("2026-10-24T12:00:00.123456789Z"));

        String text = Files.readString(run.resolve(RunEnded.RUN_ENDED_FILE));
        assertEquals("2026-10-24T12:00:00.123456+00:00\n", text);
    }

    @Test
    void aWholeSecondIsWrittenWithoutAFraction() {
        assertEquals("2026-10-24T12:00:00+00:00", RunEnded.isoFormat(Instant.parse("2026-10-24T12:00:00Z")));
    }

    @Test
    void aMissingRunFolderIsLeftAloneAndNothingIsCreated() {
        Path run = dir.resolve("never-made");
        assertDoesNotThrow(() -> RunEnded.mark(run, Instant.now()));
        assertFalse(Files.exists(run));
    }

    @Test
    void aMarkerThatCannotBeWrittenDoesNotThrow() throws IOException {
        // A folder whose marker name is already a directory cannot take the file.
        Path run = Files.createDirectories(dir.resolve("run-2"));
        Files.createDirectories(run.resolve(RunEnded.RUN_ENDED_FILE));
        assertDoesNotThrow(() -> RunEnded.mark(run, Instant.now()));
        assertTrue(Files.isDirectory(run.resolve(RunEnded.RUN_ENDED_FILE)));
    }

    @Test
    void theMarkerNameIsTheReadersName() {
        assertEquals("run.ended", RunEnded.RUN_ENDED_FILE);
    }
}
