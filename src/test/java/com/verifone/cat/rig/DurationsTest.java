package com.verifone.cat.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/** The one duration grammar, pinned to the Python {@code petrotest/rig/duration.py} cases. */
class DurationsTest {

    @Test
    void parsesEveryFormTheRigFilesUse() {
        assertEquals(Duration.ofSeconds(15), Durations.parse("15s"));
        assertEquals(Duration.ofSeconds(30), Durations.parse("30 s"));
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
        assertEquals(Duration.ofMinutes(2), Durations.parse("2m"));
        assertEquals(Duration.ofHours(1), Durations.parse("1h"));
        assertEquals(Duration.ofMillis(1500), Durations.parse("1.5s"));
    }

    @Test
    void aBareNumberIsSeconds() {
        assertEquals(Duration.ofSeconds(15), Durations.parse(15));
        assertEquals(Duration.ofMillis(1500), Durations.parse(1.5));
        assertEquals(Duration.ofSeconds(15), Durations.parse("15"));
    }

    @Test
    void unitsAreCaseInsensitiveAndSurroundingSpaceIsIgnored() {
        assertEquals(Duration.ofSeconds(90), Durations.parse("  90S "));
        assertEquals(Duration.ofMillis(250), Durations.parse("250 MS"));
    }

    @Test
    void aDurationPassesThroughUnchanged() {
        Duration d = Duration.ofSeconds(7);
        assertEquals(d, Durations.parse(d));
    }

    @Test
    void compoundFormsAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Durations.parse("1m30s"));
        assertTrue(e.getMessage().contains("expected a duration like '15s'"), e.getMessage());
        assertTrue(e.getMessage().contains("got '1m30s'"), e.getMessage());
    }

    @Test
    void unknownUnitsAndWordsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("15 sec"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("15d"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(""));
    }

    @Test
    void zeroAndNegativeAreRejectedAsNotPositive() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class, () -> Durations.parse("0s"));
        assertTrue(zero.getMessage().contains("greater than zero"), zero.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(-5));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("-5s"));
    }

    @Test
    void booleansAreNeverDurations() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Durations.parse(Boolean.TRUE));
        assertTrue(e.getMessage().contains("got boolean true"), e.getMessage());
    }

    @Test
    void nonFiniteNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(Double.POSITIVE_INFINITY));
    }

    @Test
    void formatsLikeThePythonCatInItsLogs() {
        assertEquals("15s", Durations.format(Duration.ofSeconds(15)));
        assertEquals("90s", Durations.format(Duration.ofSeconds(90)));
        assertEquals("500ms", Durations.format(Duration.ofMillis(500)));
        assertEquals("1.5s", Durations.format(Duration.ofMillis(1500)));
    }

    @Test
    void formatRoundTripsThroughParse() {
        for (String s : new String[] {"15s", "500ms", "1.5s", "90s"}) {
            assertEquals(Durations.parse(s), Durations.parse(Durations.format(Durations.parse(s))));
        }
    }

    @Test
    void millisecondsIsTheUnitPlaywrightWants() {
        assertEquals(15000.0, Durations.milliseconds(Duration.ofSeconds(15)));
        assertEquals(new BigDecimal("0.5").doubleValue() * 1000, Durations.milliseconds(Duration.ofMillis(500)));
    }
}
