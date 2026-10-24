package com.verifone.cat.plans;

import java.util.List;

/**
 * Where plan rows come from: the Confluence page and Xray in the Python CAT.
 *
 * <p>The network implementation is not written yet (the Atlassian client is a later slice).
 * It implements this interface, and {@link SeedPlanSource} is the offline implementation that
 * is always there. A method that cannot produce its rows throws {@link PlanSourceUnavailable};
 * a method that can produce none, because it has no row for that plan, returns an empty list.
 */
public interface PlanSource {

    /** The Automated-Features catalogue, in page order. */
    List<TestPlan> catalogue();

    /** A plan's Xray cases, in the plan's own order. */
    List<PlanCase> cases(String planKey);

    /** The Supported CAT Test Plans rows: what each plan needs of the machine. */
    List<PlanRequirements> requirements();

    /** A source that always fails with {@code reason}, for a build with no network source. */
    static PlanSource unavailable(String reason) {
        return new PlanSource() {
            @Override
            public List<TestPlan> catalogue() {
                throw new PlanSourceUnavailable(reason);
            }

            @Override
            public List<PlanCase> cases(String planKey) {
                throw new PlanSourceUnavailable(reason);
            }

            @Override
            public List<PlanRequirements> requirements() {
                throw new PlanSourceUnavailable(reason);
            }
        };
    }
}
