package com.verifone.cat.launch;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectOptions;
import com.verifone.cat.collect.CollectResult;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The run's cases for {@code --plan}: each plan's case keys from the plan catalogue (cache, then the shipped seed), then
 * the petroapps tests of those keys collected by {@link Collect}, in plan order, as {@link CaseSource#collected} reads
 * them.
 *
 * <p>Suites ({@code --suite}) are not collected here yet, so a run with suites and no plan has no cases, and the step
 * fails saying so. The collect pulls into {@code <repoRoot>/tests/suites/pulled}, as the Python CAT does.
 */
final class PlanCases {

    /** The branch of petroapps the tests are read from (the Python's default). */
    static final String REVISION = "master";

    /** The dist folder that wins for tests (the Python's default). */
    static final String DIST = "buypass";

    private PlanCases() {
    }

    /** The case source for the run's plans, with {@code repoRoot} as this checkout. */
    static CaseSource forRun(Path repoRoot) {
        return config -> {
            if (config.testPlans().isEmpty()) {
                return List.of();
            }
            try {
                return collect(config.testPlans(), repoRoot).cases(config);
            } catch (RuntimeException e) {
                throw new IOException("the plan's cases could not be collected: " + e.getMessage(), e);
            }
        };
    }

    private static CaseSource collect(List<String> plans, Path repoRoot) {
        PlanCatalogue catalogue = PlanCatalogue.offline(repoRoot);
        List<String> keys = new ArrayList<>();
        for (String plan : plans) {
            for (PlanCase c : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_FIRST).rows()) {
                if (!keys.contains(c.key())) {
                    keys.add(c.key());
                }
            }
        }
        CollectOptions options = new CollectOptions(REVISION, DIST, repoRoot.resolve(Collect.PULLED_DIR), repoRoot,
                false);
        CollectResult collected = Collect.collect(keys, Path.of(Collect.defaultPetroappsRoot()), options);
        return CaseSource.collected(collected, repoRoot);
    }
}
