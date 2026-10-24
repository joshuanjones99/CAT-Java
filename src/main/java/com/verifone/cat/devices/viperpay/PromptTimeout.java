package com.verifone.cat.devices.viperpay;

import java.time.Duration;

/**
 * A wait on the ViperPay pinpad ran out before the wanted display arrived. Stands in for the Python
 * CAT's {@code TimeoutExpired}; the message names the wait, so a failed run says what it waited for.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PromptTimeout extends RuntimeException {

    private final Duration budget;

    public PromptTimeout(String what, Duration budget) {
        super("timed out after " + budget + " waiting for " + what);
        this.budget = budget;
    }

    /** The budget the wait was given. */
    public Duration budget() {
        return budget;
    }
}
