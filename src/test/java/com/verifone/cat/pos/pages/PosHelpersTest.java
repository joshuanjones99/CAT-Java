package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;

/** The pure helpers behind {@link Pos}: they need no page, so these run without Chromium. */
class PosHelpersTest {

    @Test
    void newerTransactionReturnsTheFirstNumberThatDiffersFromAfter() {
        Deque<String> newest = new ArrayDeque<>(java.util.List.of("100", "100", "101"));

        String found = Pos.newerTransaction(newest::poll, "100", Duration.ofSeconds(5), null);

        assertEquals("101", found);
    }

    @Test
    void newerTransactionTreatsAnEmptyAfterAsAnyNumber() {
        assertEquals("7", Pos.newerTransaction(() -> "7", "", Duration.ofSeconds(1), null));
        assertEquals("7", Pos.newerTransaction(() -> "7", null, Duration.ofSeconds(1), null));
    }

    @Test
    void newerTransactionIsNullWhenNoNewerNumberCameInTime() {
        assertNull(Pos.newerTransaction(() -> "100", "100", Duration.ofMillis(300), null));
    }

    @Test
    void newerTransactionEndsAtOnceAsNoneWhenTheAbortGivesAReason() {
        // The Python's DeadEnd is a TimeoutExpired, which newer_transaction suppresses: the newest is then read
        // blind (test_run_time_levers.py::test_a_tlog_wait_held_by_an_ok_only_message_ends_early).
        long started = System.nanoTime();
        assertNull(Pos.newerTransaction(
                () -> null, "100", Duration.ofSeconds(5), () -> "the POS has shown [OK] for 20s"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
    }

    @Test
    void aPasswordPromptIsRecognisedWhateverItsCase() {
        assertTrue(Pos.asksForPassword("Enter Password For ID: 1"));
        assertTrue(Pos.asksForPassword("ENTER PASSWORD"));
        assertFalse(Pos.asksForPassword("Enter ID"));
    }

    @Test
    void thePromptIdIsWhatFollowsItsLastColon() {
        assertEquals("1", Pos.promptId("Enter Password For ID: 1"));
        assertEquals("", Pos.promptId("Enter Password"));
        assertEquals("42", Pos.promptId("a: b: 42 "));
    }
}
