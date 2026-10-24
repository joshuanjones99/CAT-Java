package com.verifone.cat.pats.runner.handlers.sale;

import java.time.Duration;
import java.util.Objects;

/**
 * The waits the sale handlers take, as the Python {@code core} and {@code pos_ui} set them. {@link #DEFAULT} is
 * PATS's; a test shortens them.
 *
 * @param touchSettle       the touch and scan settle cap, and the boundary pause ({@code TOUCH_SETTLE}, 2 s)
 * @param cancelSessionWait how long a scan waits for "Cancelling PinPad Session" to clear (10 s)
 * @param pinpadWait        a tender's or a fuel's wait for the pump or pinpad ({@code PINPAD_WAIT}, 42 s)
 * @param stateWait         a wait for the POS to reach a state ({@code STATE_WAIT}, 117 s)
 * @param overlayRead       a STAN prompt's wait ({@code OVERLAY_READ}, 2 s)
 * @param overlaySeenPause  the pause before a key when a message is up ({@code OVERLAY_SEEN_PAUSE}, 1 s)
 * @param pollInterval      how often a bounded wait re-reads the screen (0.1 s)
 */
public record SaleTiming(Duration touchSettle, Duration cancelSessionWait, Duration pinpadWait, Duration stateWait,
        Duration overlayRead, Duration overlaySeenPause, Duration pollInterval) {

    /** PATS's own waits. */
    public static final SaleTiming DEFAULT = new SaleTiming(
            Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(42), Duration.ofSeconds(117),
            Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMillis(100));

    public SaleTiming {
        Objects.requireNonNull(touchSettle, "touchSettle");
        Objects.requireNonNull(cancelSessionWait, "cancelSessionWait");
        Objects.requireNonNull(pinpadWait, "pinpadWait");
        Objects.requireNonNull(stateWait, "stateWait");
        Objects.requireNonNull(overlayRead, "overlayRead");
        Objects.requireNonNull(overlaySeenPause, "overlaySeenPause");
        Objects.requireNonNull(pollInterval, "pollInterval");
    }
}
