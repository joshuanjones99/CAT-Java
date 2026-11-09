package com.verifone.cat.pos.driver;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.verifone.cat.core.Waits;

/**
 * Wait for an observable condition, bounded. Port of {@code recovery.timeouts.poll_until}: the
 * shape every wait in the POS layer takes (DESIGN.md 4.2 rule 2, 6.3). The gap between probes is
 * a bounded park ({@link Waits#until}), not a sleep.
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
        Optional<T> result = Waits.until(() -> Optional.ofNullable(predicate.get()), timeout, interval);
        if (result.isPresent()) {
            return result.get();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new DriverTimeout(what + " (interrupted)");
        }
        throw new DriverTimeout(what + ": not true within " + timeout.toMillis() + " ms");
    }
}
