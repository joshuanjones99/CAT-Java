package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.ScratchKey;
import com.verifone.cat.pats.runner.handlers.devices.DevicePauses;

import java.time.Duration;
import java.util.Objects;

/**
 * The touch marks PATS's runner keeps between actions: a case's first input to the POS comes no sooner than the touch
 * settle after the last touch, whichever case made it (Python {@code pos_ui.boundary_pause}, {@code _pressed}).
 *
 * <p>The per-case "has touched" flag is {@link #TOUCHED}, in the case's scratch; the time of the last touch is shared
 * across cases, as the Python's module global is. A touch-handling family that shares this pacer shares {@link #TOUCHED}
 * with it.
 */
public final class TouchPacer {

    /** Whether this case has touched the POS yet (the Python's {@code run.touched}). */
    public static final ScratchKey<Boolean> TOUCHED = ScratchKey.of("touched", Boolean.class);

    private final DevicePauses pauses;
    private final Duration touchSettle;
    private volatile long lastTouchNanos = -1L;

    public TouchPacer(DevicePauses pauses, Duration touchSettle) {
        this.pauses = Objects.requireNonNull(pauses, "pauses");
        this.touchSettle = Objects.requireNonNull(touchSettle, "touchSettle");
    }

    /** Marks a touch: the case has touched, and the touch time is now. */
    public void pressed(CaseRun run) {
        run.setScratch(TOUCHED, true);
        lastTouchNanos = System.nanoTime();
    }

    /**
     * A case's first touch waits out the settle since the last touch, if there was one. Nothing waits once the case has
     * touched.
     */
    public void boundaryPause(CaseRun run) {
        if (run.scratch(TOUCHED).orElse(false) || lastTouchNanos < 0) {
            return;
        }
        long elapsed = System.nanoTime() - lastTouchNanos;
        Duration remaining = touchSettle.minusNanos(elapsed);
        if (!remaining.isNegative() && !remaining.isZero()) {
            pauses.pause(remaining);
        }
    }
}
