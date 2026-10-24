package com.verifone.cat.pats.runner;

/**
 * Where a case's fixtures come from: the test's own fixture machinery, in the Python the pytest
 * {@code request.getfixturevalue}. The runner asks for a fixture by name only, when an action needs it.
 */
@FunctionalInterface
public interface FixtureSource {

    /** The fixture's value, or throws if it cannot be built; the caller turns a throw into a step failure. */
    Object fixture(FixtureName name);
}
