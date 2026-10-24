package com.verifone.cat.pats.runner.handlers.devices;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The fixed pauses PATS takes around device actions (the DCR's input and key pauses, the overlay pause before a
 * key). A seam so a test can record them rather than wait; {@link #REAL} is the production pause.
 *
 * <p>The real pause is a bounded park on a latch, never {@code Thread.sleep}.
 */
@FunctionalInterface
public interface DevicePauses {

    /** The production pause: a bounded park against the duration. */
    DevicePauses REAL = DevicePauses::park;

    /** Waits for {@code duration}. */
    void pause(Duration duration);

    /** Parks the calling thread for {@code duration} at most. */
    static void park(Duration duration) {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            latch.await(Math.max(0L, duration.toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during a device pause", e);
        }
    }
}
