package com.verifone.cat.pos.pinpad;

import java.math.BigDecimal;
import java.time.Duration;

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
