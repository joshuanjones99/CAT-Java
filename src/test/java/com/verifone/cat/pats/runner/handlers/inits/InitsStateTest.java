package com.verifone.cat.pats.runner.handlers.inits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The init waits, on a fake clock: each one ends on the signal Petro logs, or is capped at what PATS waited there.
 * The timings are shortened here; the Python's are in {@link InitsState.Timing#production()}.
 */
class InitsStateTest {

    static final String PUMPS = "Oct 11 12:00:00.000 PATS INFO Processing restart pump request\n";
    static final String DCR1 = "Oct 11 12:00:00.000 PATS INFO Sending Display command to DCR[1]\n";

    private static final InitsState.Timing TIMING = new InitsState.Timing(
            Duration.ofMillis(200), Duration.ofMillis(50), Duration.ofMillis(600),
            Duration.ofMillis(300), Duration.ofMillis(1500));

    @TempDir
    Path folder;

    private Path log;
    private TestClock clock;
    private InitsState state;

    @BeforeEach
    void setUp() throws IOException {
        log = folder.resolve("log4j-application.log");
        Files.writeString(log, "started\n", StandardCharsets.UTF_8);
        clock = new TestClock();
        state = new InitsState(() -> log, clock.waits(), TIMING);
    }

    @Test
    void aPumpRestartEndsTheWaitAfterItsQuiet() {
        state.sent("cfuelinit", state.petroMark());
        clock.writeAfter(Duration.ofMillis(600), log, PUMPS);
        long started = clock.nanos();

        state.afterInit("PETROCORE-1", 4.0);

        long took = clock.nanos() - started;
        assertTrue(took >= Duration.ofMillis(600).toNanos() && took < Duration.ofMillis(1600).toNanos(), "took " + took);
        assertEquals(List.of(), state.pending());
    }

    @Test
    void anInitWhoseSignalNeverComesHasItsWholeCapAndNothingWaitsForItAgain() {
        state.sent("cdcrinit", state.petroMark());
        long started = clock.nanos();

        state.afterInit("PETROCORE-2", 0.8);

        assertTrue(clock.nanos() - started >= Duration.ofMillis(800).toNanos());
        assertEquals(List.of(), state.pending());
    }

    @Test
    void aRefreshIsNotSettledBeforeItsFloor() {
        state.sent("crefreshcfg", state.petroMark());
        clock.writeAfter(Duration.ofMillis(50), log, DCR1);
        long started = clock.nanos();

        state.afterInit("PETROCORE-3", 4.0);

        long took = clock.nanos() - started;
        assertTrue(took >= Duration.ofMillis(600).toNanos() && took < Duration.ofMillis(2000).toNanos(), "took " + took);
    }

    @Test
    void theReplyPauseIsCutShortByTheSignalAndItsQuiet() {
        state.sent("cdcrinit", state.petroMark());
        clock.writeAfter(Duration.ofMillis(200), log, DCR1);
        long started = clock.nanos();

        state.replyPause("PETROCORE-4");

        long took = clock.nanos() - started;
        assertTrue(took >= Duration.ofMillis(400).toNanos() && took < Duration.ofMillis(1400).toNanos(), "took " + took);
        assertEquals(List.of(), state.pending());
    }

    @Test
    void theReplyPauseIsPaidWholeWhenNoSignalComes() {
        state.sent("cdcrinit", state.petroMark());
        long started = clock.nanos();

        state.replyPause("PETROCORE-5");

        assertTrue(clock.nanos() - started >= Duration.ofMillis(1500).toNanos());
    }

    @Test
    void aWaitAfterAnInitKeepsWhatTheSleepBeforeItDidNotWait() {
        // A 2 s SLEEP before an init waits only the 300 ms spacing; the 1.7 s it did not wait is owed after the init.
        state.beforeInit("PETROCORE-6", 2.0);
        state.sent("cdcrinit", state.petroMark());
        long started = clock.nanos();

        state.replyPause("PETROCORE-6");

        assertTrue(clock.nanos() - started >= Duration.ofMillis(3000).toNanos(),
                "took " + (clock.nanos() - started));
    }

    @Test
    void aSleepBeforeAnInitWaitsOnlyTheSpacingAfterAChange() {
        state.changed();
        clock.advance(Duration.ofSeconds(10));

        state.beforeInit("PETROCORE-7", 60.0);

        assertEquals(Duration.ZERO, clock.pauses().get(clock.pauses().size() - 1));
    }

    @Test
    void withNoChangeASleepBeforeAnInitWaitsTheSpacing() {
        state.beforeInit("PETROCORE-8", 60.0);

        assertEquals(Duration.ofMillis(300), clock.pauses().get(clock.pauses().size() - 1));
    }

    @Test
    void aSleepBeforeAnInitWaitsForThePendingInitFirst() {
        state.sent("cfuelinit", state.petroMark());
        clock.writeAfter(Duration.ofMillis(600), log, PUMPS);
        long started = clock.nanos();

        state.beforeInit("PETROCORE-9", 60.0);

        assertTrue(clock.nanos() - started >= Duration.ofMillis(600).toNanos());
        assertEquals(List.of(), state.pending());
    }

    @Test
    void aSignalWrittenBeforeTheReplyDoesNotCount() throws IOException {
        Files.writeString(log, PUMPS, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        state.sent("cfuelinit", state.petroMark());
        long started = clock.nanos();

        state.afterInit("PETROCORE-10", 0.8);

        assertTrue(clock.nanos() - started >= Duration.ofMillis(2300).toNanos(),
                "took " + (clock.nanos() - started));
    }

    @Test
    void anInitWithNoKnownSignalIsNotWatched() {
        state.sent("cposinit", state.petroMark());

        assertEquals(List.of(), state.pending());
    }

    @Test
    void anInitSentWithNoLogToReadIsNotWatched() {
        state.sent("cfuelinit", -1L);

        assertEquals(List.of(), state.pending());
    }

    @Test
    void theMarkIsMinusOneAndNothingIsWatchedWithoutPetrosLog() {
        InitsState without = new InitsState(() -> folder.resolve("absent.log"), clock.waits(), TIMING);

        assertEquals(-1L, without.petroMark());
        assertFalse(without.watching());
        assertTrue(state.watching());
    }

    @Test
    void knowsIsCaseInsensitiveAndOnlyForTheSignalledInits() {
        assertTrue(InitsState.knows("CFUELINIT"));
        assertTrue(InitsState.knows("crefreshcfg"));
        assertFalse(InitsState.knows("cposinit"));
        assertFalse(InitsState.knows(null));
    }

    @Test
    void resetForgetsEveryInitAndChange() {
        state.sent("cfuelinit", state.petroMark());
        state.changed();

        state.reset();

        assertEquals(List.of(), state.pending());
        state.beforeInit("PETROCORE-11", 60.0);
        assertEquals(Duration.ofMillis(300), clock.pauses().get(clock.pauses().size() - 1));
    }
}
