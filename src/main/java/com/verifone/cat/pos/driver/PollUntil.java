package com.verifone.cat.pos.driver;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Wait for an observable condition, bounded. Port of {@code recovery.timeouts.poll_until}: the
 * shape every wait in the POS layer takes (DESIGN.md 4.2 rule 2, 6.3). The gap between probes is
 * a bounded latch wait, not a sleep.
 *
 * <p>A predicate that throws propagates at once: a broken probe is not a condition that is not
 * yet true (the Python's rule).
 */
public final class PollUntil {

    private PollUntil() {}

    /** Waits until {@code predicate} is true; throws {@link DriverTimeout} when the budget ends. */
    public static void until(BooleanSupplier predicate, Duration timeout, Duration interval, String what) {
        value(() -> predicate.getAsBoolean() ? Boolean.TRUE : null, timeout, interval, what);
    }

    /**
     * Waits until {@code predicate} returns a non-null value, and returns it, so a caller can poll
     * and fetch in one step.
     */
    public static <T> T value(Supplier<T> predicate, Duration timeout, Duration interval, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        CountDownLatch tick = new CountDownLatch(1);
        while (true) {
            T result = predicate.get();
            if (result != null) {
                return result;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new DriverTimeout(what + ": not true within " + timeout.toMillis() + " ms");
            }
            try {
                tick.await(Math.min(interval.toNanos(), remaining), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DriverTimeout(what + " (interrupted)");
            }
        }
    }
}
