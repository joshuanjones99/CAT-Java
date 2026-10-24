package com.verifone.cat.commander.connect;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Bounded polling: checks at once, then every interval, until the check holds or the deadline passes. It parks
 * against the deadline; there is no {@code Thread.sleep} and no wait past the deadline.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Bounded {

    /** The shortest park between checks, so a zero interval cannot spin. */
    private static final long MIN_INTERVAL_NANOS = 1_000_000L;

    private Bounded() {}

    /** @return whether {@code check} held before {@code timeout} passed */
    static boolean poll(BooleanSupplier check, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (check.getAsBoolean()) {
                return true;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            LockSupport.parkNanos(Math.min(Math.max(interval.toNanos(), MIN_INTERVAL_NANOS), left));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Waits for {@code duration}, bounded: a park against the deadline. */
    static void pause(Duration duration) {
        poll(() -> false, duration, duration);
    }
}
