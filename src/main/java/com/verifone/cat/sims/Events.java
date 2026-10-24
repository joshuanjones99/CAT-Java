package com.verifone.cat.sims;

/**
 * What a supervisor pass tells its caller. Every method runs on the thread that drives the
 * pass, or on a log thread of its own for {@link #log}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface Events {

    /** Ignores everything. */
    Events NONE = new Events() {
    };

    /** A status change for one simulator (or the process probe row). */
    default void status(SimStatus status) {
    }

    /** One line of a simulator's output, or of its {@code log:} file. */
    default void log(String name, String line) {
    }

    /**
     * A process handle exists, before its readiness is settled. The caller owns every spawned
     * child from here, even if the pass never returns (its caller timed out).
     */
    default void started(SimProcess process) {
    }
}
