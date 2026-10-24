package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.devices.DeviceUnavailable;

import java.util.Optional;

/**
 * The Commander's newest TLog for the case, as REFUND_WITH_TRANS_MAP reads a STAN from it when the stored receipt has
 * none. Ported from the Python {@code run.last_tlog}.
 *
 * <p>No Java TLog source is wired to a case yet, so {@link #UNAVAILABLE} throws. The Python returns no STAN when it
 * has no TLog; this port refuses instead, so a refund never falls through to a STAN it could not read.
 */
@FunctionalInterface
public interface LastTlog {

    /**
     * The STAN the newest TLog's card info gives (its batch number and sequence number, joined), or empty when the case
     * has no TLog or the TLog has no card info.
     */
    Optional<String> stan();

    /** The TLog source that is not wired: every read throws {@link DeviceUnavailable}. */
    LastTlog UNAVAILABLE = () -> {
        throw new DeviceUnavailable("the last TLog", "reading the STAN from the Commander's newest TLog: "
                + "no TLog source is wired to this run");
    };
}
