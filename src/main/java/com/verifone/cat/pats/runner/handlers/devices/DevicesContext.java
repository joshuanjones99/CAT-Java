package com.verifone.cat.pats.runner.handlers.devices;

import com.verifone.cat.pos.pinpad.PinpadDevice;

import java.util.Objects;

/**
 * What the devices family reaches: the pinpad, the DCR, the fuel pumps, the pauses and the waits.
 *
 * <p>A device with no Java implementation is passed as its {@code UNAVAILABLE} default, which throws
 * {@link com.verifone.cat.devices.DeviceUnavailable}; the family never substitutes a silent success.
 *
 * @param pinpad   the pinpad (the ViperPay-style {@code PinpadDouble}, or a physical one)
 * @param dcr      the DCR, or {@link DcrDevice#UNAVAILABLE}
 * @param fuelPumps the pumps, or {@link FuelPumps#UNAVAILABLE}
 * @param pauses   the fixed pauses; {@link DevicePauses#REAL} in production
 * @param timing   the waits; {@link DevicesTiming#DEFAULT} in production
 */
public record DevicesContext(
        PinpadDevice pinpad,
        DcrDevice dcr,
        FuelPumps fuelPumps,
        DevicePauses pauses,
        DevicesTiming timing) {

    public DevicesContext {
        Objects.requireNonNull(pinpad, "pinpad");
        Objects.requireNonNull(dcr, "dcr");
        Objects.requireNonNull(fuelPumps, "fuelPumps");
        Objects.requireNonNull(pauses, "pauses");
        Objects.requireNonNull(timing, "timing");
    }

    /** A context with the pinpad given and the DCR and pumps unwired, at PATS's pauses and waits. */
    public static DevicesContext withPinpad(PinpadDevice pinpad) {
        return new DevicesContext(pinpad, DcrDevice.UNAVAILABLE, FuelPumps.UNAVAILABLE,
                DevicePauses.REAL, DevicesTiming.DEFAULT);
    }
}
