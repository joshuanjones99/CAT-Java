package com.verifone.cat.pats.runner.handlers.devices;

import java.time.Duration;

/**
 * The waits the devices handlers take, as the Python {@code core} sets them. {@link #DEFAULT} is PATS's; a test
 * shortens them.
 *
 * @param pinpadWait     how long a pinpad prompt, key or card may take ({@code PINPAD_WAIT}, 42 s)
 * @param dcrWait        how long a DCR prompt may take ({@code DCR_WAIT}, 117 s)
 * @param dcrInputPause  the pause before a DCR card or numeric key ({@code DCR_INPUT_PAUSE}, 1 s)
 * @param dcrKeyPause    the pause before a DCR function key ({@code DCR_KEY_PAUSE}, 3 s)
 */
public record DevicesTiming(Duration pinpadWait, Duration dcrWait, Duration dcrInputPause, Duration dcrKeyPause) {

    /** PATS's own waits. */
    public static final DevicesTiming DEFAULT = new DevicesTiming(
            Duration.ofSeconds(42), Duration.ofSeconds(117), Duration.ofSeconds(1), Duration.ofSeconds(3));

    public DevicesTiming {
        java.util.Objects.requireNonNull(pinpadWait, "pinpadWait");
        java.util.Objects.requireNonNull(dcrWait, "dcrWait");
        java.util.Objects.requireNonNull(dcrInputPause, "dcrInputPause");
        java.util.Objects.requireNonNull(dcrKeyPause, "dcrKeyPause");
    }
}
