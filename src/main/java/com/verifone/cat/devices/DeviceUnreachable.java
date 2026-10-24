package com.verifone.cat.devices;

/**
 * The device, or the thing between us and it, is not answering.
 *
 * <p>For pos-shim: either the shim is not there (a transport failure), or it is there
 * and the POS is not talking to it (a 503 with a rig code). A restart may help.
 */
public final class DeviceUnreachable extends DeviceError {

    public DeviceUnreachable(String what, String detail) {
        super(what + " is unreachable: " + detail);
    }

    public DeviceUnreachable(String what, String detail, Throwable cause) {
        super(what + " is unreachable: " + detail, cause);
    }
}
