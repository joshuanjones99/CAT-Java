package com.verifone.cat.pats.runner.handlers.inits;

import com.verifone.cat.commander.Pauser;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The bounded waits of the init and configuration handlers: a pause, and a poll of an observable
 * condition with a ceiling. Both go through one clock and one {@link Pauser}, so a test drives them
 * with a fake clock and a pauser that advances it, instead of waiting in real time.
 *
 * <p>Ported from the Python {@code petrotest.recovery.timeouts.poll_until} and {@code bounded_wait}.
 * A poll checks its condition at once, then every interval, until it holds or the timeout has passed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class Waits {

    private final LongSupplier nanoClock;
    private final Pauser pauser;

    /**
     * @param nanoClock a monotonic clock in nanoseconds ({@link System#nanoTime} in production)
     * @param pauser    what a pause does; {@link Pauser#REAL} in production
     */
    public Waits(LongSupplier nanoClock, Pauser pauser) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.pauser = Objects.requireNonNull(pauser, "pauser");
    }

    /** The production waits: the system's monotonic clock and a real pause. */
    public static Waits production() {
        return new Waits(System::nanoTime, Pauser.REAL);
    }

    /** The clock's reading, in nanoseconds. */
    public long nanos() {
        return nanoClock.getAsLong();
    }

    /** A fixed pause of {@code duration}; never a sleep call, always the pauser. */
    public void pause(Duration duration) {
        pauser.pause(duration);
    }

    /**
     * Polls {@code check} until it holds, for at most {@code timeout}.
     *
     * @return whether it held; {@code false} is the Python's {@code TimeoutExpired}
     */
    public boolean pollUntil(BooleanSupplier check, Duration timeout, Duration interval) {
        long deadline = nanoClock.getAsLong() + Math.max(0L, timeout.toNanos());
        long step = Math.max(1L, interval.toNanos());
        while (true) {
            if (check.getAsBoolean()) {
                return true;
            }
            long left = deadline - nanoClock.getAsLong();
            if (left <= 0) {
                return false;
            }
            pauser.pause(Duration.ofNanos(Math.min(step, left)));
        }
    }
}
