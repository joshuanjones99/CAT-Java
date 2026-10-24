package com.verifone.cat.devices.forecourt;

import com.verifone.cat.devices.DeviceError;

/**
 * A bounded forecourt wait ran out before the pump or DCR reached the state it was waiting for. The message names
 * what was waited for and the last state reported, as the Python's {@code TimeoutExpired} does.
 *
 * <p>A {@link DeviceError}, not a rig fault: the probe got no answer in time. Preflight maps it to TIMEOUT.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ForecourtWaitExpired extends DeviceError {

    private static final long serialVersionUID = 1L;

    public ForecourtWaitExpired(String message) {
        super(message);
    }
}
