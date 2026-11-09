package com.verifone.cat.core;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The one set of bounded waits (hard rule 3: no {@code Thread.sleep} in main code). Every wait parks against a
 * deadline and never runs past it. An interrupt ends a wait early with the thread's flag still set, so the caller
 * decides what an interrupt means.
 */
public final class Waits {

    /** The shortest park between checks, so a zero interval cannot spin. */
    private static final long MIN_INTERVAL_NANOS = 1_000_000L;

    private Waits() {}

    /**
     * Waits for {@code duration}; zero or negative returns at once.
     *
     * @return {@code true} when the whole duration passed, {@code false} when an interrupt cut it short (the
     *     flag stays set)
     */
    public static boolean pause(Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        for (long left = duration.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks at once, then every {@code interval}, until {@code check} gives a value or {@code timeout} passes.
     * An exception from {@code check} ends the wait and propagates.
     *
     * @return the first present value; empty when the deadline passed or the thread was interrupted first
     */
    public static <T> Optional<T> until(Supplier<Optional<T>> check, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long step = Math.max(interval.toNanos(), MIN_INTERVAL_NANOS);
        while (true) {
            Optional<T> value = check.get();
            if (value.isPresent()) {
                return value;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return Optional.empty();
            }
            LockSupport.parkNanos(Math.min(step, left));
            if (Thread.currentThread().isInterrupted()) {
                return Optional.empty();
            }
        }
    }

    /** @return whether {@code check} held before {@code timeout} passed; see {@link #until} */
    public static boolean poll(BooleanSupplier check, Duration timeout, Duration interval) {
        return until(() -> check.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty(), timeout, interval)
                .isPresent();
    }
}
