package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BoundedTest {

    @Test
    void returnsTheFirstValueTheProbeGives() {
        int[] calls = {0};

        String value = Bounded.pollUntil(() -> ++calls[0] < 3 ? Optional.empty() : Optional.of("up"),
                Duration.ofSeconds(5), Duration.ofMillis(5), "the thing");

        assertEquals("up", value);
        assertEquals(3, calls[0]);
    }

    @Test
    @Timeout(10)
    void givesUpAtTheDeadlineNamingWhatItWaitedFor() {
        var ex = assertThrows(Bounded.TimedOut.class, () -> Bounded.pollUntil(Optional::empty,
                Duration.ofMillis(50), Duration.ofMillis(5), "CGILink"));

        assertTrue(ex.getMessage().contains("CGILink"));
    }

    @Test
    void aZeroIntervalIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Bounded.pollUntil(() -> Optional.of(1),
                Duration.ofSeconds(1), Duration.ZERO, "x"));
    }
}
