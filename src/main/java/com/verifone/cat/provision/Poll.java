package com.verifone.cat.provision;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import com.verifone.cat.preflight.Bounded;

/**
 * Checks a condition until it holds or a deadline passes. A port of {@code petrotest.recovery.timeouts.poll_until}.
 *
 * <p>The check runs at least once. Between checks the thread parks for {@code interval}, never past the deadline,
 * so the total wait is bounded by {@code timeout} (DESIGN.md 6.3). A check returns null while the condition is
 * not yet true, and the value it returns otherwise.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Poll {

    /** The shortest park between checks, so a zero interval cannot spin. */
    private static final long MIN_INTERVAL_NANOS = 1_000_000L;

    private Poll() {}

    /**
     * @return the first non-null value {@code check} gives
     * @throws Bounded.TimeoutExpired when the deadline passes first; {@code what} names the wait
     */
    public static <T> T until(Supplier<T> check, Duration timeout, Duration interval, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            T value = check.get();
            if (value != null) {
                return value;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new Bounded.TimeoutExpired(what + " did not happen within " + seconds(timeout) + " s");
            }
            LockSupport.parkNanos(Math.min(Math.max(interval.toNanos(), MIN_INTERVAL_NANOS), left));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new Bounded.TimeoutExpired(what + " was interrupted");
            }
        }
    }

    /** A duration in seconds as the Python prints it with {@code :g}: "60", "0.3". */
    static String seconds(Duration duration) {
        double value = duration.toNanos() / 1e9;
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
