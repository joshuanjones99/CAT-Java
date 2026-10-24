package com.verifone.cat.runservice;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * When a continuous pass must wait for the Commander's forced automatic end of day. The Python CAT's
 * {@code cat.schedule.forced_aeod_at} and {@code continuous_hold} (HANDOFF.md §1.137, run
 * {@code 291a79a774cd}).
 *
 * <p>The BUYPASS Host sends the cut-off time {@code 0330} (the Python's {@code cat.sims.capk.CUT_TIME}),
 * so a Buypass FEP branded EXXONMOBIL forces an automatic end of day 15 minutes before it, at 03:15. A
 * pass still running then meets it, so a pass that would be running at that instant waits until the
 * forced end of day has gone by.
 *
 * <p>Pure: the instant is passed in. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ForcedAeod {

    /** The host's cut-off time, as the Python's {@code CUT_TIME} (b"0330"). */
    static final LocalTime CUT_TIME = LocalTime.of(3, 30);
    /** The forced end of day comes this long before the cut-off. */
    static final Duration BEFORE_CUT = Duration.ofMinutes(15);
    /** How long before the forced end of day a pass must be over (the register warns 5 minutes ahead). */
    static final Duration CLEAR_BEFORE = Duration.ofMinutes(10);
    /** How long after it the next pass may start. */
    static final Duration CLEAR_AFTER = Duration.ofMinutes(10);
    /** A first pass's length before one has been measured: Cadence's is about 90 minutes. */
    static final Duration FIRST_PASS_LENGTH = Duration.ofMinutes(105);

    private ForcedAeod() {}

    /** The next forced end of day whose clear-after window has not gone by, on {@code now}'s clock. */
    public static LocalDateTime forcedAeodAt(LocalDateTime now) {
        LocalDateTime at = LocalDateTime.of(now.toLocalDate(), CUT_TIME).minus(BEFORE_CUT);
        if (!now.isBefore(at.plus(CLEAR_AFTER))) {
            at = at.plusDays(1);
        }
        return at;
    }

    /**
     * When a continuous pass that would start at {@code now} should start instead, or {@code null} to
     * start it now.
     */
    public static LocalDateTime continuousHold(LocalDateTime now, Duration passLength) {
        LocalDateTime at = forcedAeodAt(now);
        if (now.plus(passLength).isAfter(at.minus(CLEAR_BEFORE))) {
            return at.plus(CLEAR_AFTER);
        }
        return null;
    }
}
