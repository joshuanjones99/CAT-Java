package com.verifone.cat.provision;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import com.verifone.cat.core.Waits;
import com.verifone.cat.preflight.Bounded;

/**
 * Checks a condition until it holds or a deadline passes. A port of {@code petrotest.recovery.timeouts.poll_until}.
 *
 * <p>The check runs at least once. Between checks the thread parks for {@code interval}, never past the deadline,
 * so the total wait is bounded by {@code timeout} (DESIGN.md 6.3): {@link Waits#until}, with a named timeout. A
 * check returns null while the condition is not yet true, and the value it returns otherwise.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Poll {

    private Poll() {}

    /**
     * @return the first non-null value {@code check} gives
     * @throws Bounded.TimeoutExpired when the deadline passes first; {@code what} names the wait
     */
    public static <T> T until(Supplier<T> check, Duration timeout, Duration interval, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<T> value = Waits.until(() -> Optional.ofNullable(check.get()), timeout, interval);
        if (value.isPresent()) {
            return value.get();
        }
        // Waits.until is empty for both endings. Its deadline is no earlier than this one, so a wait that ran out
        // is at or past it, and one an interrupt cut short is still before it.
        if (Thread.currentThread().isInterrupted() && System.nanoTime() - deadline < 0) {
            throw new Bounded.TimeoutExpired(what + " was interrupted");
        }
        throw new Bounded.TimeoutExpired(what + " did not happen within " + seconds(timeout) + " s");
    }

    /** A duration in seconds as the Python prints it with {@code :g}: "60", "0.3". */
    static String seconds(Duration duration) {
        double value = duration.toNanos() / 1e9;
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
