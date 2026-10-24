package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

/** The follower reads the tee as it grows, as the Python's {@code cat.xray.tail} tests do. */
class EventFollowerTest {

    private ScheduledExecutorService scheduler;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "follow-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    private static final Duration POLL = Duration.ofMillis(2);

    /** Waits for a follow to end, bounded: a hang fails the test rather than the build. */
    private static void awaitEnd(CompletableFuture<Void> done) throws Exception {
        done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void yieldsWhatIsAppendedAndCompletesAHalfWrittenLine() throws Exception {
        Path file = tmp.resolve("events.ndjson");
        Files.write(file, "{\"type\": \"a\", \"n\": 1}\n{\"message\": \"x\", \"type\": \"log.line\"}\n{\"type\": \"b\", \"n\"".getBytes(StandardCharsets.UTF_8));
        List<String> seen = new ArrayList<>();
        AtomicBoolean appended = new AtomicBoolean();
        EventFollower follower = new EventFollower(file, idle -> {
            if (!appended.getAndSet(true)) {
                try {
                    Files.write(file, ": 2}\n{\"type\": \"c\", \"n\": 3}\n".getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return false;
            }
            return idle >= 0.02;
        }, POLL, new AtomicBoolean(), event -> seen.add(event.get("type").asText()));

        awaitEnd(follower.start(scheduler));

        assertEquals(List.of("a", "b", "c"), seen);
    }

    @Test
    void logLinesAreDroppedByTheirTailWithoutParsing() throws Exception {
        Path file = tmp.resolve("events.ndjson");
        Files.writeString(file, "{\"message\": \"m\", \"type\": \"log.line\"}\n{\"type\": \"x\"}\nnot json\n");
        List<String> seen = new ArrayList<>();
        awaitEnd(new EventFollower(file, idle -> true, POLL, new AtomicBoolean(),
                event -> seen.add(event.get("type").asText())).start(scheduler));
        assertEquals(List.of("x"), seen);
    }

    @Test
    void aFileThatDoesNotExistYetIsWaitedForThenRead() throws Exception {
        Path file = tmp.resolve("events.ndjson");
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        awaitEnd(new EventFollower(file, idle -> {
            if (calls.incrementAndGet() == 2) {
                try {
                    Files.writeString(file, "{\"type\": \"late\"}\n");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            return calls.get() >= 3;
        }, POLL, new AtomicBoolean(), event -> seen.add(event.get("type").asText())).start(scheduler));
        assertEquals(List.of("late"), seen);
    }

    @Test
    void theIdleClockIsReportedToStop() throws Exception {
        Path file = tmp.resolve("events.ndjson");
        Files.writeString(file, "{\"type\": \"x\"}\n");
        List<Double> idles = new ArrayList<>();
        awaitEnd(new EventFollower(file, idle -> {
            idles.add(idle);
            return idle >= 0.03;
        }, POLL, new AtomicBoolean(), event -> { }).start(scheduler));
        assertEquals(0.0, idles.get(0));
        assertTrue(idles.get(idles.size() - 1) >= 0.03);
    }

    @Test
    void cancelEndsTheFollowAtOnce() throws Exception {
        Path file = tmp.resolve("events.ndjson");
        Files.writeString(file, "{\"type\": \"x\"}\n");
        AtomicBoolean cancel = new AtomicBoolean(true);
        List<JsonNode> seen = new ArrayList<>();
        awaitEnd(new EventFollower(file, idle -> false, POLL, cancel, seen::add).start(scheduler));
        assertTrue(seen.isEmpty());
    }
}
