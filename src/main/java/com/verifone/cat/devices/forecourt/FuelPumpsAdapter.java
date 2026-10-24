package com.verifone.cat.devices.forecourt;

import com.verifone.cat.pats.runner.handlers.devices.FuelPumps;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link FuelPumps} over a {@link PetroPumps}: the seam the sale and devices families drive the pumps through.
 *
 * <p>Every call is bounded: a wait that runs out throws {@link ForecourtWaitExpired}, and a socket failure throws
 * {@link com.verifone.cat.devices.DeviceUnreachable}, both {@code DeviceError}s, never a {@code false}. The one
 * exception is {@link #waitForPostedSale}, whose Python caller treats a sale that was not posted as a warning, so an
 * expired wait there is {@code false}, as the seam documents.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class FuelPumpsAdapter implements FuelPumps {

    private final PetroPumps pumps;

    public FuelPumpsAdapter(PetroPumps pumps) {
        this.pumps = Objects.requireNonNull(pumps, "pumps");
    }

    @Override
    public void fillAmount(int pump, String amount, int hose, boolean prepay, boolean preset) {
        pumps.fillAmount(pump, new BigDecimal(amount), hose, prepay, preset);
    }

    @Override
    public void handleUp(int pump, int hose, boolean prepay) {
        pumps.handleUp(pump, hose, prepay);
    }

    @Override
    public void handleDown(int pump, boolean prepay) {
        pumps.handleDown(pump, prepay);
    }

    @Override
    public Optional<String> state(int pump) {
        return Optional.ofNullable(pumps.state(pump));
    }

    @Override
    public void waitUntilIdle(int pump, Duration timeout) {
        pumps.waitUntilIdle(pump, timeout);
    }

    @Override
    public boolean waitForPostedSale(int pump, Duration timeout) {
        try {
            pumps.waitForPostedSale(pump, timeout);
            return true;
        } catch (ForecourtWaitExpired notPosted) {
            return false;
        }
    }

    @Override
    public boolean lifted(int pump) {
        return pumps.lifted(pump);
    }

    @Override
    public String clear(int pump) {
        return pumps.clear(pump);
    }

    @Override
    public Set<Integer> touched() {
        return pumps.touched();
    }
}
