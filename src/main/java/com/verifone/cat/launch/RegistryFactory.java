package com.verifone.cat.launch;

import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.FixtureSource;
import com.verifone.cat.pats.runner.handlers.HandlerFixtures;

/**
 * Builds the action registry the Tests Run step executes a run's cases with, from what the run can
 * provide. The Tests Run step calls {@link #build} once per run, after the services are connected.
 *
 * <p>The production implementation is {@link StandardRegistry}: one {@link HandlerFixtures} for the run, the
 * registry of {@code StandardHandlers.create} over it, and {@code StandardHandlers.fixtureSource} over the same
 * fixtures, so the handlers and the fixtures a case asks for are the same objects. A factory that registers nothing
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

    /**
     * The registry, the fixtures a case's actions ask for, and the handler fixtures both were built from. By default
     * {@link #create}'s registry with {@link TestsRunStep#fixtureSource(RunServices)}, and no handler fixtures.
     */
    default Built build(RunServices services) {
        return new Built(create(services), TestsRunStep.fixtureSource(services), null);
    }

    /**
     * One run's registry and fixtures.
     *
     * @param registry the action registry
     * @param fixtures the fixtures a case's actions ask for by name
     * @param handlers the handler fixtures the two were built from, or null when the factory built none (the POS page
     *                 objects for the pump teardown's due-fuel settle come from it)
     */
    record Built(ActionRegistry registry, FixtureSource fixtures, HandlerFixtures handlers) {
    }
}
