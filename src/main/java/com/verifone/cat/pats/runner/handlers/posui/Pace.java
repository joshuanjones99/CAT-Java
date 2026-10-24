package com.verifone.cat.pats.runner.handlers.posui;

import com.verifone.cat.pats.runner.PatsStepError;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/**
 * The POS UI family's waits: a fixed pause (the Python's {@code bounded_wait}), a bounded poll
 * ({@code poll_until}) and the clock they measure with. One seam, so a test records and shortens the waits instead of
 * sitting through them. Every wait has a ceiling; a pause is a bounded park, never a sleep.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface Pace {

    /** The real pace: parks the thread against the deadline. */
    Pace REAL = new Real();

    /** Parks for {@code duration}. A zero or negative duration returns at once. */
    void pause(Duration duration);

    /**
     * Checks {@code check} at once, then every {@code interval}, until it holds or {@code timeout} has passed.
     *
     * @return whether it held; {@code false} is the Python's {@code TimeoutExpired}
     */
    boolean poll(BooleanSupplier check, Duration timeout, Duration interval);

    /** The monotonic clock the family measures its waits with, in nanoseconds. */
    long nanoTime();

    /** The real pace. */
    final class Real implements Pace {

        private static final long MIN_PARK_NANOS = 1_000_000L;

        @Override
        public void pause(Duration duration) {
            long deadline = System.nanoTime() + duration.toNanos();
            for (long left = duration.toNanos(); left > 0; left = deadline - System.nanoTime()) {
                java.util.concurrent.locks.LockSupport.parkNanos(left);
                interrupted();
            }
        }

        @Override
        public boolean poll(BooleanSupplier check, Duration timeout, Duration interval) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (check.getAsBoolean()) {
                    return true;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                LockSupport.parkNanos(Math.min(Math.max(interval.toNanos(), MIN_PARK_NANOS), left));
                interrupted();
            }
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        private static void interrupted() {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new PatsStepError("interrupted during a wait on the POS");
            }
        }
    }
}
