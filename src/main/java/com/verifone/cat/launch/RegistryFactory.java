package com.verifone.cat.launch;

import com.verifone.cat.pats.runner.ActionRegistry;

/**
 * Builds the action registry the Tests Run step executes a run's cases with, from what the run can
 * provide. The Tests Run step calls it once per run, after the services are connected.
 *
 * <p>The production implementation is {@code pats.runner.handlers.StandardHandlers}, adapted to this
 * interface when the handler families are wired into the launcher. A factory that registers nothing
 * still runs: every action then fails with "no handler", which is a failure, never a pass.
 */
@FunctionalInterface
public interface RegistryFactory {

    /**
     * The registry for one run.
     *
     * @param services what the run can provide; a handler that needs a surface the run lacks throws
     *                 {@code DeviceUnavailable}, which fails the action
     */
    ActionRegistry create(RunServices services);
}
