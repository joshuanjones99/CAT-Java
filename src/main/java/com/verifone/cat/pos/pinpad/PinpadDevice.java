package com.verifone.cat.pos.pinpad;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.regex.Pattern;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * The pinpad contract the suites rely on. Ported from the Python {@code PinpadDevice} protocol.
 *
 * <p>A {@code null} timeout means the implementation's own prompt-wait budget. A {@code null}
 * {@code waitForPrompt} means the action returns at once, with {@code null}.
 *
 * <p>TODO(devices): the Python protocol lives in {@code petrotest.devices.pinpad}, which is not
 * ported. This interface moves there when it is.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface PinpadDevice {

    /** The text {@link #decimalText} accepts: an optionally negative decimal, digits and an optional fraction. */
    Pattern ENTRY_TEXT = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

    /** The pinpad's id. */
    String id();

    PinpadDisplay display();

    /** Waits until the display shows {@code prompt} (case-insensitive substring). */
    String atPrompt(String prompt, Duration timeout);

    /** Waits until the display stops showing {@code prompt}; returns what replaced it. */
    String expectPromptCleared(String prompt, Duration timeout);

    String insertCard(String label, String waitForPrompt, Duration timeout);

    String swipeCard(String label, String waitForPrompt, Duration timeout);

    String tapCard(String label, String waitForPrompt, Duration timeout);

    void removeCard();

    String key(String key, String waitForPrompt, Duration timeout);

    String decimal(BigDecimal value, String waitForPrompt, Duration timeout);

    /**
     * Enters {@code text} exactly as written: no number parsing, so leading zeros survive (a
     * loyalty number "0001234", say) and the entry is what PATS's data string was. The text is an
     * optionally negative decimal, digits with an optional fraction: {@code "123456"},
     * {@code "20.72"}, {@code "-5"}. Anything else is refused with {@link IllegalArgumentException}.
     *
     * <p>Added after the first pinpad implementations, so the default refuses rather than guess:
     * an implementation that cannot enter text digit for digit says so here.
     */
    default String decimalText(String text, String waitForPrompt, Duration timeout) {
        throw new DeviceUnavailable(
                "pinpad digit-for-digit entry", "this pinpad cannot enter text exactly as written");
    }

    default String atPrompt(String prompt) {
        return atPrompt(prompt, null);
    }

    default String insertCard(String label) {
        return insertCard(label, null, null);
    }

    default String key(String key) {
        return key(key, null, null);
    }
}
