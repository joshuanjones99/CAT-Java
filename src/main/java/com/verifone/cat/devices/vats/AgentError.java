package com.verifone.cat.devices.vats;

import com.verifone.cat.devices.DeviceError;

/**
 * The agent ran the op and Verifone's SDK raised: the Python's {@code AgentError}. The message is
 * {@code "VATS <op>: <ExceptionType>: <message>"}, as the agent reported it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class AgentError extends DeviceError {

    public AgentError(String message) {
        super(message);
    }
}
