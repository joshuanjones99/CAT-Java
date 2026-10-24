package com.verifone.cat.devices.forecourt;

import java.time.Duration;

/**
 * The three bounds the forecourt client waits under: a pump reaching its state ({@code fuel_complete}), a DCR prompt
 * ({@code prompt_wait}), and one request or login ({@code http}). Ported from the Python {@code Timeouts} fields the
 * forecourt reads.
 *
 * <p>Every bound must be positive: a zero bound means "wait forever", which DESIGN.md 6.3 forbids.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record ForecourtTimeouts(Duration fuelComplete, Duration promptWait, Duration http) {

    /** The Python defaults: 90 s, 30 s and 15 s. */
    public static final ForecourtTimeouts DEFAULTS = new ForecourtTimeouts(
            Duration.ofSeconds(90), Duration.ofSeconds(30), Duration.ofSeconds(15));

    /** The forecourt's three bounds, from the rig's recovery budgets (the same fields, under the same names). */
    public static ForecourtTimeouts of(com.verifone.cat.recovery.Timeouts timeouts) {
        return new ForecourtTimeouts(timeouts.fuelComplete(), timeouts.promptWait(), timeouts.http());
    }

    public ForecourtTimeouts {
        requirePositive("fuel_complete", fuelComplete);
        requirePositive("prompt_wait", promptWait);
        requirePositive("http", http);
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("timeouts." + key + " must be positive: a zero or negative "
                    + "timeout means 'wait forever', which DESIGN.md 6.3 forbids");
        }
    }
}
