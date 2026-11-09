package com.verifone.cat.pats.runner.handlers.devices;

import com.verifone.cat.core.Waits;

import java.time.Duration;

/**
 * The fixed pauses PATS takes around device actions (the DCR's input and key pauses, the overlay pause before a
 * key). A seam so a test can record them rather than wait; {@link #REAL} is the production pause.
 *
 * <p>The real pause is a bounded park ({@link Waits#pause}), never {@code Thread.sleep}.
 */
@FunctionalInterface
public interface DevicePauses {

    /** The production pause: a bounded park against the duration. */
    DevicePauses REAL = DevicePauses::park;

    /** Waits for {@code duration}. */
    void pause(Duration duration);

    /** Parks the calling thread for {@code duration} at most. */
    static void park(Duration duration) {
        // A pending interrupt fails even a zero pause, as the latch this replaced did.
        if (!Waits.pause(duration) || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("interrupted during a device pause", new InterruptedException());
        }
    }
}
