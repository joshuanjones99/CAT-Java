package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.validate.PyRepr;

import java.time.Duration;
import java.util.List;

/**
 * {@code IS_ELEMENT_PRESENT} and {@code IS_ELEMENT_CLICKABLE}: whether an element is on the POS, and whether it is
 * clickable within a window. {@code YES} waits for the element; {@code NO} looks once, after whatever SLEEP the case
 * put before it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Presence {

    private final PosUi ui;

    Presence(PosUi ui) {
        this.ui = ui;
    }

    /** {@code isElementPresentAction}: YES waits up to the UI ceiling; NO must find it absent. */
    void present(CaseRun run, RunnableAction action) {
        String answer = action.get("YES/NO").toUpperCase(java.util.Locale.ROOT);
        Locator locator = PosUi.locator(action);
        PosDriver driver = ui.driver();
        if (answer.equals("YES")) {
            Duration timeout = PosUi.seconds(PosUi.budget(run, ui.timings().uiWait()));
            if (!ui.pace().poll(() -> !driver.elementStates(locator).isEmpty(), timeout, Validations.QUARTER_SECOND)) {
                throw new PatsStepError(locator + " to be present: timed out");
            }
        } else if (answer.equals("NO")) {
            Duration timeout = maxOf(PosUi.seconds(PosUi.budget(run, Duration.ZERO)), Duration.ofMillis(250));
            if (!ui.pace().poll(() -> driver.elementStates(locator).isEmpty(), timeout, Validations.QUARTER_SECOND)) {
                throw new PatsStepError(locator + " is present, and YES/NO says it must not be");
            }
        } else {
            throw new PatsStepError("YES/NO is " + quoted(answer) + "; PATS takes only YES or NO");
        }
    }

    /**
     * {@code isElementClickableAction}, branch for branch. The element is found (YES waits for it to be present; NO
     * looks at once), then given the window in {@code WAIT TIME[SEC]} to become clickable. YES fails if it never does,
     * NO if it does. An element that is not there fails either way. The carried SLEEP is time PATS slept before it
     * looked: a YES adds it to both waits as ceilings; a NO waits up to it for the element, then holds it to the window.
     */
    void clickable(CaseRun run, RunnableAction action) {
        String answer = action.get("YES/NO").toUpperCase(java.util.Locale.ROOT);
        String rawWait = PosUi.require(action, "WAIT TIME[SEC]").strip();
        if (rawWait.isEmpty() || !rawWait.chars().allMatch(Character::isDigit)) {
            throw new PatsStepError("WAIT TIME[SEC] is " + quoted(rawWait) + "; PATS reads it with Long.parseLong");
        }
        Duration window = Duration.ofSeconds(Long.parseLong(rawWait));
        if (!answer.equals("YES") && !answer.equals("NO")) {
            throw new PatsStepError("YES/NO is " + quoted(answer) + "; PATS takes only YES or NO");
        }
        Locator locator = PosUi.locator(action);
        PosDriver driver = ui.driver();
        Duration carried = PosUi.seconds(PosUi.budget(run, Duration.ZERO));
        Duration look = ui.timings().clickableLook();
        if (answer.equals("YES")) {
            if (!ui.pace().poll(() -> clickableOrNull(driver, locator) != null,
                    ui.timings().uiWait().plus(carried), look)) {
                throw new PatsStepError(locator + " to be present: timed out");
            }
            if (!ui.pace().poll(() -> Boolean.TRUE.equals(clickableOrNull(driver, locator)),
                    window.plus(carried), look)) {
                throw new PatsStepError(locator + " to be clickable: timed out");
            }
            return;
        }
        // NO: not present-and-clickable within the carried sleep, then clickable within the whole window.
        Duration noCeiling = carried.compareTo(look) > 0 ? carried : look;
        if (!ui.pace().poll(() -> Boolean.FALSE.equals(clickableOrNull(driver, locator)), noCeiling, look)) {
            if (clickableOrNull(driver, locator) == null) {
                throw new PatsStepError(locator + " is not present (PATS's findElement throws)");
            }
            throw new PatsStepError(locator + " is clickable, and YES/NO says it must not be");
        }
        if (ui.pace().poll(() -> Boolean.TRUE.equals(clickableOrNull(driver, locator)), window, look)) {
            throw new PatsStepError(locator + " became clickable within " + window.toSeconds()
                    + "s, and YES/NO says it must not");
        }
        // never clickable in the whole window: PATS's pass
    }

    /** Whether the element read is clickable; null when nothing matches. Where several match, the last displayed. */
    private static Boolean clickableOrNull(PosDriver driver, Locator locator) {
        List<PosDriver.Clickable> states = driver.clickableStates(locator);
        if (states.isEmpty()) {
            return null;
        }
        Boolean last = null;
        for (PosDriver.Clickable state : states) {
            if (state.displayed()) {
                last = state.enabled();
            }
        }
        return last != null && last;
    }

    private static Duration maxOf(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static String quoted(String text) {
        return PyRepr.reprString(text);
    }
}
