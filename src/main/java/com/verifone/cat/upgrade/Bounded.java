package com.verifone.cat.upgrade;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Bounded polling for the install path (DESIGN.md 6.3: every wait has a ceiling). A port of
 * {@code petrotest.recovery.timeouts.poll_until}.
 *
 * <p>The probe is tried at once, then every {@code interval} until it yields a value or the deadline
 * passes. A pause is a bounded park, never {@code Thread.sleep}, as in {@code RigLease}; it ends at the
 * deadline whatever wakes it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class Bounded {

    private Bounded() {}

    /** The probe did not answer before the deadline. */
    static final class TimedOut extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TimedOut(String message) {
            super(message);
        }
    }

    /**
     * Returns the first present value the probe gives, or throws {@link TimedOut} naming {@code what}.
     *
     * @param timeout the ceiling; zero or negative fails after one probe
     * @param interval the pause between probes; must be positive
     */
    static <T> T pollUntil(Supplier<Optional<T>> probe, Duration timeout, Duration interval, String what) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<T> value = probe.get();
            if (value.isPresent()) {
                return value.get();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimedOut("timed out after " + timeout.toMillis() + " ms waiting for " + what);
            }
            LockSupport.parkNanos(Math.min(interval.toNanos(), remaining));
        }
    }

    /** A bounded pause between attempts of a retry loop: a park, not {@code Thread.sleep}. */
    static void pause(Duration pause) {
        LockSupport.parkNanos(pause.toNanos());
    }
}
