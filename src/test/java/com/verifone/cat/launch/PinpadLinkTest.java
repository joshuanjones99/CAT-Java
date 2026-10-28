package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pinpad link's log reads: the Python's {@code _eps_reached} and {@code _size_of}, on files under a temp dir. */
class PinpadLinkTest {

    @TempDir
    Path dir;

    private static final String LINE = "2026-10-28 INFO DeviceRequest Receieved from EPS\n";

    @Test
    void onlyARequestLineAfterTheMarkCounts() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, LINE);
        long mark = Files.size(log);
        assertFalse(PinpadLink.seen(log, mark));
        Files.writeString(log, LINE + "other\n" + LINE);
        assertTrue(PinpadLink.seen(log, mark));
    }

    @Test
    void aLogThatShrankBelowTheMarkIsReadFromTheStart() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, LINE);
        assertTrue(PinpadLink.seen(log, 1_000_000));
    }

    @Test
    void aMissingLogIsNeverSeenAndHasSizeZero() {
        Path log = dir.resolve("absent.log");
        assertFalse(PinpadLink.seen(log, 0));
        assertEquals(0, PinpadLink.sizeOf(log));
    }

    @Test
    void theWaitPollsUntilTheLineArrives() throws IOException {
        Path log = dir.resolve("petro.log");
        Files.writeString(log, "");
        AtomicInteger pauses = new AtomicInteger();
        PinpadLink link = new PinpadLink(duration -> {
            if (pauses.incrementAndGet() == 3) {
                try {
                    Files.writeString(log, LINE);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }, new PinpadLink.Timing(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMillis(1)));
        assertTrue(link.reached(log, 0, Duration.ofMinutes(1)));
        assertEquals(3, pauses.get());
    }
}
