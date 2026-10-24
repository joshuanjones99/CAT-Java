package com.verifone.cat.closeday;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Bounded waits for the day close: a check until it holds or a deadline passes, and a plain pause.
 * Parks the thread against the deadline; never {@code Thread.sleep}, and never past the deadline.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Waits {

    /** The shortest park between checks, so a zero interval cannot spin. */
    private static final long MIN_INTERVAL_NANOS = 1_000_000L;

    private Waits() {}

    /**
     * Checks at once, then every {@code interval}, until {@code check} gives a value or {@code timeout} passes.
     * An exception from {@code check} ends the wait at once and propagates.
     *
     * @return the first present value, or empty when the deadline passed first
     */
    static <T> Optional<T> until(Supplier<Optional<T>> check, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<T> value = check.get();
            if (value.isPresent()) {
                return value;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Optional.empty();
            }
            LockSupport.parkNanos(Math.min(Math.max(interval.toNanos(), MIN_INTERVAL_NANOS), left));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /** Waits for {@code duration}, bounded: a park against the deadline, not a sleep. */
    static void pause(Duration duration) {
        until(Optional::empty, duration, duration);
    }
}
