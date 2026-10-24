package com.verifone.cat.preflight;

import java.time.Duration;

/**
 * A named preflight check. Ported from the Python CAT's {@code petrotest.preflight.base.Check}
 * Protocol (DESIGN.md 6.2):
 *
 * <pre>
 * verify(rig)  cheap, read-only, always safe; never mutates
 * repair(rig)  bounded, idempotent, may mutate
 * </pre>
 *
 * <p>The defaults are the Python {@code BaseCheck} defaults. {@link #repairBudget()} is
 * enforced by the runner from outside, because a bound a check applies to itself is not a
 * bound. A repair that declines by default is safer than one that silently passes.
 */
public interface Check {

    String name();

    /** Cheap, read-only, always safe. */
    Result verify(RigContext rig);

    /** Bounded, idempotent, may mutate. */
    Result repair(RigContext rig);

    /** How long {@link #repair} may run before the runner abandons it. */
    default Duration repairBudget() {
        return Duration.ofSeconds(30);
    }

    /** Whether this check applies to the rig at all. A check that does not is skipped, not passed. */
    default boolean applicable(RigContext rig) {
        return true;
    }

    /**
     * Why this check must not run mid-run on {@code rig}, or empty when it may. Mid-run
     * preflight is recovery's rung 4 (DESIGN.md 6.3); a standalone preflight runs everything.
     */
    default String operatorOnly(RigContext rig) {
        return "";
    }
}
