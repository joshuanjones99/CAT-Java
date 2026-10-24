package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.devices.DeviceUnavailable;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What a period close that never ends is cleared by: a coresvcs restart, and only on a deadlock the thread dump
 * names (the Python's {@code close_day.recover_stuck_close(rig, only_if_deadlocked=True)} and its
 * {@code kept_dump}). The close-day family's {@code CloseDay.recoverStuckClose} is the production binding.
 */
public interface StuckCloseRecovery {

    /**
     * Restarts coresvcs when, and only when, its thread dump names a deadlock.
     *
     * @return whether coresvcs was restarted
     */
    boolean restartIfDeadlocked() throws Exception;

    /** The thread dump the last deadlocked restart kept, if there is one. */
    Optional<Path> keptDump();

    /** Refuses every call: the close-day recovery is not bound. */
    StuckCloseRecovery UNBOUND = new StuckCloseRecovery() {
        @Override
        public boolean restartIfDeadlocked() {
            throw new DeviceUnavailable("closeday", "the stuck-close recovery is not bound to the close-day family");
        }

        @Override
        public Optional<Path> keptDump() {
            return Optional.empty();
        }
    };
}
