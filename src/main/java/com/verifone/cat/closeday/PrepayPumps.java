package com.verifone.cat.closeday;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

import com.verifone.cat.devices.forecourt.PetroPumps;

/**
 * The three Petro calls the register pass makes to refund a prepay nobody fuelled ({@link PosRegisterPass}): a
 * pump's reported state, a $0.00 prepay lift, and the hang-up. The production one is {@link #of} over
 * {@link PetroPumps}, which needs PATS's port 1072; whoever builds the pass decides when that port is free (the
 * launch lends it around the close). A test passes a fake.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface PrepayPumps extends AutoCloseable {

    /** Opens Petro, logged in. Throws when it cannot be driven; the pass reports that and leaves the prepays. */
    @FunctionalInterface
    interface Opener {
        PrepayPumps open();
    }

    /** {@code pumps.reported(pump, timeout=pumps.timeouts.http)}: the state Petro reports for the pump. */
    String reported(int pump);

    /** {@code pumps.fill_amount(pump, "0.00", prepay=True)}: lifts at $0.00, so the whole prepay is a refund. */
    void liftAtZero(int pump);

    /** {@code pumps.handle_down(pump, prepay=True)}: hangs up, then waits for IDLEOFF. */
    void hangUp(int pump);

    /** Releases Petro's ports. Never throws. */
    @Override
    void close();

    /** $0.00, as the Python's {@code "0.00"}. */
    BigDecimal ZERO = new BigDecimal("0.00");

    /** The production adapter: hose 1, no preset, as the Python's defaults; reads bounded by {@code http}. */
    static PrepayPumps of(PetroPumps pumps, Duration http) {
        Objects.requireNonNull(pumps, "pumps");
        return new PrepayPumps() {
            @Override
            public String reported(int pump) {
                return pumps.reported(pump, http);
            }

            @Override
            public void liftAtZero(int pump) {
                pumps.fillAmount(pump, ZERO, 1, true, false);
            }

            @Override
            public void hangUp(int pump) {
                pumps.handleDown(pump, true);
            }

            @Override
            public void close() {
                pumps.close();
            }
        };
    }
}
