package com.verifone.cat.pats.runner.handlers.devices;

import com.verifone.cat.devices.DeviceUnavailable;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * The forecourt's pumps, as the sale and devices families drive them: lift a nozzle, fill it, put it back, and
 * read a pump's state. Ported from the Python {@code PumpManager} and {@code PetroPumps}.
 *
 * <p>No Java pump client exists yet, so {@link #UNAVAILABLE} throws {@link DeviceUnavailable} for every call.
 */
public interface FuelPumps {

    /** The pumps that are not wired: every call throws {@link DeviceUnavailable}. */
    FuelPumps UNAVAILABLE = new FuelPumps() {
        @Override
        public void fillAmount(int pump, String amount, int hose, boolean prepay, boolean preset) {
            throw unavailable("fill pump " + pump);
        }

        @Override
        public void handleUp(int pump, int hose, boolean prepay) {
            throw unavailable("lift pump " + pump);
        }

        @Override
        public void handleDown(int pump, boolean prepay) {
            throw unavailable("put back pump " + pump);
        }

        @Override
        public Optional<String> state(int pump) {
            throw unavailable("read pump " + pump);
        }

        @Override
        public void waitUntilIdle(int pump, Duration timeout) {
            throw unavailable("wait for pump " + pump + " to be idle");
        }
    };

    /**
     * Lifts the nozzle and fills {@code amount}. {@code preset} is set after a POS Fuel Preset: the lift then waits for
     * the Commander to authorise it.
     */
    void fillAmount(int pump, String amount, int hose, boolean prepay, boolean preset);

    /** Lifts the nozzle with no fill, once the pump can take it. */
    void handleUp(int pump, int hose, boolean prepay);

    /** Puts the nozzle back. */
    void handleDown(int pump, boolean prepay);

    /** The pump's state as the forecourt reports it (for example {@code IDLEON}), or empty when unknown. */
    Optional<String> state(int pump);

    /** Waits, bounded by {@code timeout}, until the pump is idle. Throws when it does not get there. */
    void waitUntilIdle(int pump, Duration timeout);

    /**
     * Waits, bounded by {@code timeout}, for a hang-up on {@code pump} to post its sale to the POS's fuel panel (the
     * Python's {@code wait_for_posted_sale}; the POS UI family's tile press waits on it).
     *
     * @return whether the sale was posted within {@code timeout}; false when it was not, which is not a failure here
     */
    default boolean waitForPostedSale(int pump, Duration timeout) {
        throw unavailable("wait for pump " + pump + "'s posted sale");
    }

    /** Whether {@code pump} is lifted: a fill or a lift has been sent and nothing has hung it up. */
    default boolean lifted(int pump) {
        throw unavailable("read whether pump " + pump + " is lifted");
    }

    /**
     * Puts a pump a failed case left mid-sale back to IDLEOFF (the Python's {@code PetroPumps.clear}).
     *
     * @return the state the pump was in, or null when it was idle or never reported
     */
    default String clear(int pump) {
        throw unavailable("clear pump " + pump);
    }

    /** Every pump a test lifted, hung up or waited on: what {@link #clear} may touch. */
    default Set<Integer> touched() {
        throw unavailable("list the pumps the run touched");
    }

    /** The step failure an unwired pump gives. */
    static DeviceUnavailable unavailable(String operation) {
        return new DeviceUnavailable("the fuel pumps", operation + ": no forecourt client is wired to this run");
    }
}
