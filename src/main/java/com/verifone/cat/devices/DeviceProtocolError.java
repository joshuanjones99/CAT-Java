package com.verifone.cat.devices;

/** The two sides disagree about the contract: a defect here or in the device, not a rig fault. */
public final class DeviceProtocolError extends DeviceError {

    public DeviceProtocolError(String call, String detail) {
        super(call + ": " + detail);
    }

    public DeviceProtocolError(String call, String detail, Throwable cause) {
        super(call + ": " + detail, cause);
    }
}
