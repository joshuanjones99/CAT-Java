package com.verifone.cat.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {

    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void pauseWaitsTheWholeDuration() {
        long start = System.nanoTime();
        assertTrue(Waits.pause(Duration.ofMillis(40)));
        assertTrue(System.nanoTime() - start >= Duration.ofMillis(40).toNanos());
    }

    @Test
    void pauseOfNothingReturnsAtOnce() {
        assertTrue(Waits.pause(Duration.ZERO));
        assertTrue(Waits.pause(Duration.ofSeconds(-1)));
    }

    @Test
    void anInterruptEndsAPauseAndStaysSet() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(Waits.pause(Duration.ofSeconds(30)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void untilChecksAtOnceAndReturnsTheFirstValue() {
        AtomicInteger calls = new AtomicInteger();
        Optional<String> value = Waits.until(
                () -> calls.incrementAndGet() == 3 ? Optional.of("ready") : Optional.empty(),
                Duration.ofSeconds(5), Duration.ofMillis(1));
        assertEquals(Optional.of("ready"), value);
        assertEquals(3, calls.get());
    }

    @Test
    void untilStopsAtTheDeadlineEvenWithAZeroInterval() {
        AtomicInteger calls = new AtomicInteger();
        Optional<String> value = Waits.until(() -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, Duration.ofMillis(30), Duration.ZERO);
        assertEquals(Optional.empty(), value);
        assertTrue(calls.get() >= 2 && calls.get() <= 40, "a zero interval must not spin: " + calls.get());
    }

    @Test
    void untilWithNoTimeChecksOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertFalse(Waits.poll(() -> calls.incrementAndGet() < 0, Duration.ZERO, Duration.ofMillis(5)));
        assertEquals(1, calls.get());
    }

    @Test
    void anInterruptEndsUntilAndStaysSet() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(Waits.poll(() -> false, Duration.ofSeconds(30), Duration.ofMillis(5)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aCheckThatThrowsEndsTheWait() {
        assertThrows(IllegalStateException.class, () -> Waits.until(() -> {
            throw new IllegalStateException("no");
        }, Duration.ofSeconds(5), Duration.ofMillis(1)));
    }

    @Test
    void sha256OfBytesAndOfAFileAgree(@TempDir Path dir) throws Exception {
        assertEquals(ABC, Hashes.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        Path file = Files.writeString(dir.resolve("abc.txt"), "abc");
        assertEquals(ABC, Hashes.sha256Hex(file));
    }

    @Test
    void theSharedMapperWritesCompactAndPrettyJson() throws Exception {
        assertEquals("{\"a\":1}", Json.MAPPER.writeValueAsString(java.util.Map.of("a", 1)));
        assertTrue(Json.PRETTY.writeValueAsString(java.util.Map.of("a", 1)).contains(System.lineSeparator())
                || Json.PRETTY.writeValueAsString(java.util.Map.of("a", 1)).contains("\n"));
    }

    @Test
    void catJavaWritesUnderDotCatJava(@TempDir Path home) {
        assertEquals(home.resolve(".cat").resolve("java"), CatHome.java(home));
        assertEquals(home.resolve(".cat"), CatHome.python(home));
        assertEquals(CatHome.user().resolve(".cat").resolve("java"), CatHome.java());
    }
}
