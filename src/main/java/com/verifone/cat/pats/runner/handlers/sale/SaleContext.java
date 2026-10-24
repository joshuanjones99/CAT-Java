package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.pats.runner.handlers.devices.DevicePauses;
import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;

import java.util.Objects;

/**
 * What the sale family reaches: the POS screen, the register's RMI, the fuel pumps, the scanner-data store, the
 * case's last TLog, the pauses, and the touch pacer.
 *
 * <p>A piece with no Java implementation is its {@code UNAVAILABLE} default, which throws
 * {@link com.verifone.cat.devices.DeviceUnavailable}; the family never substitutes a silent success.
 *
 * @param screen      the POS screen reads ({@link SaleScreen#of} for the real POS)
 * @param rmi         the register's key and item calls, or {@link SaleRmi#UNAVAILABLE}
 * @param fuelPumps   the pumps, or {@link FuelPumps#UNAVAILABLE}
 * @param scannerData the scanner-data files, or {@link ScannerData#UNAVAILABLE}
 * @param lastTlog    the newest TLog's STAN, or {@link LastTlog#UNAVAILABLE}
 * @param pauses      the fixed pauses; {@link DevicePauses#REAL} in production
 * @param pacer       the touch pacer, built over the same pauses
 * @param timing      the waits; {@link SaleTiming#DEFAULT} in production
 */
public record SaleContext(
        SaleScreen screen,
        SaleRmi rmi,
        FuelPumps fuelPumps,
        ScannerData scannerData,
        LastTlog lastTlog,
        DevicePauses pauses,
        TouchPacer pacer,
        SaleTiming timing) {

    public SaleContext {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(rmi, "rmi");
        Objects.requireNonNull(fuelPumps, "fuelPumps");
        Objects.requireNonNull(scannerData, "scannerData");
        Objects.requireNonNull(lastTlog, "lastTlog");
        Objects.requireNonNull(pauses, "pauses");
        Objects.requireNonNull(pacer, "pacer");
        Objects.requireNonNull(timing, "timing");
    }

    /**
     * A context over the given screen and RMI, with the pumps, scanner data and TLog unwired, the real pauses, and
     * PATS's waits.
     */
    public static SaleContext of(SaleScreen screen, SaleRmi rmi) {
        return new SaleContext(screen, rmi, FuelPumps.UNAVAILABLE, ScannerData.UNAVAILABLE, LastTlog.UNAVAILABLE,
                DevicePauses.REAL, new TouchPacer(DevicePauses.REAL, SaleTiming.DEFAULT.touchSettle()),
                SaleTiming.DEFAULT);
    }
}
