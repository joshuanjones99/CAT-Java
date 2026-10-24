package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

import com.verifone.cat.pos.driver.DriverError;
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
    void newerTransactionEndsAtOnceWhenTheCommanderGivesAReason() {
        assertThrows(DriverError.class, () -> Pos.newerTransaction(
                () -> null, "100", Duration.ofSeconds(5), () -> "the Commander is unreachable"));
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
