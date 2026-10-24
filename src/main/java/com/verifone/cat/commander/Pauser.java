package com.verifone.cat.commander;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import com.verifone.cat.devices.DeviceError;

/**
 * The bring-up's waits: a fixed pause ({@code bounded_wait}) and a bounded poll ({@code poll_until}),
 * both through one seam so a test records them instead of waiting.
 *
 * <p>Every wait has a ceiling (DESIGN.md 6.3). A pause is a bounded park, never a sleep.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
@FunctionalInterface
public interface Pauser {

    /** Parks until the deadline; an interrupt becomes a {@link DeviceError}. */
    Pauser REAL = duration -> {
        long deadline = System.nanoTime() + duration.toNanos();
        for (long left = duration.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new DeviceError("interrupted during a wait in the Commander bring-up");
            }
        }
    };

    /** Waits {@code duration}. */
    void pause(Duration duration);

    /**
     * {@code check} at once, then every {@code interval} until it holds or {@code timeout} has passed.
     *
     * @return whether it held; {@code false} is the Python's {@code TimeoutExpired}
     */
    default boolean until(BooleanSupplier check, Duration timeout, Duration interval) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        while (true) {
            if (check.getAsBoolean()) {
                return true;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            pause(Duration.ofNanos(Math.max(1, Math.min(interval.toNanos(), left))));
        }
    }
}
