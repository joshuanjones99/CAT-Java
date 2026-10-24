package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The schedule's time grammar and the forced-AEOD hold. Pure arithmetic; no clock is read. */
class ScheduleTimesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 22, 0, 0);

    @Test
    void aBareClockTimeAheadOfNowIsToday() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 23, 0, 0), ScheduleTimes.parseRunTime("23:00", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 24, 23, 0, 30), ScheduleTimes.parseRunTime("23:00:30", NOW));
    }

    @Test
    void aBareClockTimeAlreadyPassedIsTomorrow() {
        assertEquals(LocalDateTime.of(2026, 10, 25, 9, 15, 0), ScheduleTimes.parseRunTime("09:15", NOW));
    }

    @Test
    void aDatedTimeTakesBothSpacesAndTheT() {
        LocalDateTime expected = LocalDateTime.of(2026, 9, 6, 23, 0, 0);
        assertEquals(expected, ScheduleTimes.parseRunTime("2026-09-06 23:00", NOW));
        assertEquals(expected, ScheduleTimes.parseRunTime("2026-09-06T23:00", NOW));
    }

    @Test
    void aRelativeDelayCountsFromNowInTheRigDurationGrammar() {
        assertEquals(NOW.plusMinutes(30), ScheduleTimes.parseRunTime("+30m", NOW));
        assertEquals(NOW.plusHours(1), ScheduleTimes.parseRunTime("+1h", NOW));
        assertEquals(NOW.plusSeconds(90), ScheduleTimes.parseRunTime("+90s", NOW));
    }

    @Test
    void aBadTimeNamesWhatWasExpectedAndWhatWasFound() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ScheduleTimes.parseRunTime("tea time", NOW));
        assertTrue(e.getMessage().contains("expected a time like '23:00'"));
        assertTrue(e.getMessage().contains("'tea time'"));
    }

    @Test
    void aListIsSplitOnCommasAndSpacesAndADateKeepsItsTime() {
        List<LocalDateTime> times = ScheduleTimes.parseScheduleTimes("23:00, 2026-09-06 23:00  +30m", NOW);
        assertEquals(List.of(
                LocalDateTime.of(2026, 10, 24, 23, 0, 0),
                LocalDateTime.of(2026, 9, 6, 23, 0, 0),
                NOW.plusMinutes(30)), times);
    }

    @Test
    void emptyInputIsNoTimesAndOneBadEntryRejectsTheWholeList() {
        assertEquals(List.of(), ScheduleTimes.parseScheduleTimes("   ", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ScheduleTimes.parseScheduleTimes("23:00, nonsense", NOW));
    }

    @Test
    void theForcedAeodIsFifteenMinutesBeforeTheCutOff() {
        assertEquals(LocalDateTime.of(2026, 10, 24, 3, 15),
                ForcedAeod.forcedAeodAt(LocalDateTime.of(2026, 10, 24, 2, 58)));
        // Past its clear-after window, the next one is tomorrow's.
        assertEquals(LocalDateTime.of(2026, 10, 25, 3, 15),
                ForcedAeod.forcedAeodAt(LocalDateTime.of(2026, 10, 24, 3, 26)));
    }

    @Test
    void aPassThatWouldRunIntoTheForcedAeodWaitsUntilItHasGoneBy() {
        LocalDateTime hold = ForcedAeod.continuousHold(LocalDateTime.of(2026, 10, 24, 2, 58),
                ForcedAeod.FIRST_PASS_LENGTH);
        assertEquals(LocalDateTime.of(2026, 10, 24, 3, 25), hold);
    }

    @Test
    void aPassThatEndsWellBeforeTheForcedAeodStartsNow() {
        assertNull(ForcedAeod.continuousHold(LocalDateTime.of(2026, 10, 24, 10, 0), Duration.ofMinutes(105)));
    }
}
