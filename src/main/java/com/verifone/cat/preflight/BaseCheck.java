package com.verifone.cat.preflight;

import java.time.Duration;

/**
 * Shared base for the checks. Ported from the Python CAT's {@code BaseCheck}: a name, a repair
 * budget, and a {@link #repair} that declines. A check that has not thought about repair
 * says so (DESIGN.md 6.2's "none - fail loudly; this is a human decision").
 */
public abstract class BaseCheck implements Check {

    private final String name;
    private final Duration repairBudget;

    protected BaseCheck(String name, Duration repairBudget) {
        this.name = name;
        this.repairBudget = repairBudget;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final Duration repairBudget() {
        return repairBudget;
    }

    @Override
    public Result repair(RigContext rig) {
        return Result.unrepairable(name, "no automatic repair — this check is a human decision (DESIGN.md 6.2)");
    }
}
