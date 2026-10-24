package com.verifone.cat.pos.pinpad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The {@code --fake-pinpad} double, against the contract the suites rely on: bounded waits that
 * see a prompt raised from another thread, and the ordering rule (a prompt shown and replaced
 * during an action is still seen). Ported from the Python CAT's {@code tests/unit/test_pinpad_double.py}.
 *
 * <p>Evidence about this repository only: the double is the thing under test, and it stands in for
 * no device. Not ported: the Python's pytester cases and its LaunchConfig argv case, which need the
 * pytest plugins and the launch config (left for the launch and pytest-facing packages).
 */
class PinpadDoubleTest {

    private static final Duration WAIT = Duration.ofSeconds(2);

    private static PinpadDouble double1() {
        return new PinpadDouble("1", WAIT);
    }

    @Test
    void itIsAPinpadDeviceMarkedLoopback() {
        PinpadDouble pinpad = double1();
        assertInstanceOf(PinpadDevice.class, pinpad);
        PinpadDisplay shown = pinpad.display();
        assertEquals(PinpadDouble.IDLE_PROMPT, shown.prompt());
        assertTrue(shown.lines().stream().allMatch(line -> line.startsWith("LOOPBACK/")), shown.lines().toString());
    }

    @Test
    void itAnnouncesItselfAsADoubleInItsNameAndBanner() {
        assertEquals("<PinpadDouble 1 (LOOPBACK, not a pinpad)>", new PinpadDouble().toString());
        assertEquals("FAKE PINPAD", PinpadDouble.BANNER_TITLE);
        assertTrue(PinpadDouble.BANNER_WARNING.contains("NOT evidence about a pinpad"));
        assertTrue(PinpadDouble.BANNER_WARNING.contains("LOOPBACK/"));
    }

    @Test
    void actionsShowTheLoopbackDisplays() {
        PinpadDouble pinpad = double1();
        pinpad.insertCard("VISA");
        assertEquals(List.of("LOOPBACK/CARD INSERT", "LOOPBACK/VISA"), pinpad.display().lines());
        assertTrue(pinpad.display().cardPresent());

        pinpad.key("ENTER");
        assertEquals("LOOPBACK/KEY_ENTER", pinpad.display().prompt());

        pinpad.decimal(new BigDecimal("1.50"), null, null);
        assertEquals(new BigDecimal("1.50"), pinpad.display().lastEntry());

        pinpad.removeCard();
        assertEquals(PinpadDouble.IDLE_PROMPT, pinpad.display().prompt());
        assertFalse(pinpad.display().cardPresent());
    }

    @Test
    void atPromptReturnsAtOnceForAPromptAlreadyShowing() {
        PinpadDouble pinpad = double1();
        pinpad.setDisplay(List.of("LOOPBACK/DONATE", "Round up for"), "donate");
        assertEquals("donate", pinpad.atPrompt("DONATE"));
    }

    @Test
    void atPromptSeesAPromptRaisedFromAnotherThread() throws InterruptedException {
        PinpadDouble pinpad = double1();
        Thread raiser = new Thread(
                () -> pinpad.setDisplay(List.of("LOOPBACK/SWIPE", "CARD"), "SWIPE CARD"));
        raiser.start();
        try {
            assertTrue(pinpad.atPrompt("swipe card").toLowerCase().contains("swipe card"));
        } finally {
            raiser.join();
        }
    }

    @Test
    void aPromptThatNeverComesTimesOutOnTheBudget() {
        PinpadDouble pinpad = double1();
        PinpadDouble.WaitTimeout thrown = assertThrows(
                PinpadDouble.WaitTimeout.class,
                () -> pinpad.atPrompt("donate", Duration.ofMillis(50)));
        assertTrue(thrown.getMessage().contains("donate"), thrown.getMessage());
    }

    @Test
    void waitForPromptSeesAPromptTheActionShowedAndReplaced() {
        // The ordering rule: the action raises "donate" then "CASHBACK" at once; a wait for
        // "donate" begun before the action still matches.
        PinpadDouble pinpad = double1();
        pinpad.setAfterKey((key, answered) -> {
            pinpad.setDisplay(List.of("LOOPBACK/DONATE"), "donate");
            pinpad.setDisplay(List.of("LOOPBACK/CASHBACK"), "CASHBACK");
        });
        String seen = pinpad.key("1", "donate", null);
        assertTrue(seen.toLowerCase().contains("donate"), seen);
        assertEquals("CASHBACK", pinpad.display().prompt());
    }

    @Test
    void expectPromptClearedReturnsWhatReplacedIt() throws InterruptedException {
        PinpadDouble pinpad = double1();
        pinpad.setDisplay(List.of("LOOPBACK/DONATE"), "donate");
        Thread clearer = new Thread(
                () -> pinpad.setDisplay(List.of(PinpadDouble.IDLE_PROMPT), PinpadDouble.IDLE_PROMPT));
        clearer.start();
        try {
            assertEquals(PinpadDouble.IDLE_PROMPT, pinpad.expectPromptCleared("donate", null));
        } finally {
            clearer.join();
        }
    }

    @Test
    void resetDropsStateAndChainedCallbacks() {
        PinpadDouble pinpad = double1();
        AtomicBoolean called = new AtomicBoolean();
        pinpad.setAfterCard(action -> called.set(true));
        pinpad.insertCard("VISA");
        assertTrue(called.get());

        pinpad.reset();
        called.set(false);
        assertEquals(PinpadDouble.IDLE_PROMPT, pinpad.display().prompt());
        assertEquals(0, pinpad.display().seq());

        pinpad.insertCard("VISA");
        assertFalse(called.get(), "the card callback must be gone after reset");
    }

    @Test
    void aCardWithNoLabelShowsUnlabelled() {
        PinpadDouble pinpad = double1();
        pinpad.insertCard("");
        assertEquals(List.of("LOOPBACK/CARD INSERT", "LOOPBACK/UNLABELLED"), pinpad.display().lines());
    }

    @Test
    void theCardActionCallbackNamesTheAction() {
        PinpadDouble pinpad = double1();
        List<String> actions = new java.util.ArrayList<>();
        pinpad.setAfterCard(actions::add);
        pinpad.swipeCard("VISA", null, null);
        pinpad.tapCard("VISA", null, null);
        assertEquals(List.of("swipe", "tap"), actions);
        assertNull(pinpad.display().lastKey());
    }

    @ParameterizedTest(name = "fakePinpad={0}, fakePos={1} gives {2}")
    @CsvSource({"false,false,false", "true,false,true", "false,true,true", "true,true,true"})
    void fakePosImpliesFakePinpad(boolean fakePinpad, boolean fakePos, boolean selected) {
        assertEquals(selected, PinpadDouble.fakePinpadSelected(fakePinpad, fakePos));
    }
}
