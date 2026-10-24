package com.verifone.cat.devices;

/**
 * The device is there, and this surface has no source behind it.
 *
 * <p>pos-shim answers 501 for these (printer port, drawer, cashier, build version):
 * nothing the POS pushes carries them. No repair will help, so this is never a FAIL.
 */
public final class DeviceUnavailable extends DeviceError {

    public DeviceUnavailable(String surface, String detail) {
        super(surface + " is not available: " + detail);
    }
}
