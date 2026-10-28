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
 * fails saying so. The collect pulls into {@code <repoRoot>/tests/suites/pulled}, as the Python CAT does. The catalogue,
 * the checkout and the petroapps folder all come from the run's {@link LaunchPaths}, so a test points them at fixtures.
 */
final class PlanCases {

    /** The branch of petroapps the tests are read from by default ({@link LaunchConfig#testPlanBranch} is used). */
    static final String REVISION = "master";

    /** The dist folder that wins for tests by default ({@link LaunchConfig#dist} is used). */
    static final String DIST = "buypass";

    private PlanCases() {
    }

    /** The case source for the run's plans, read from {@code paths}: its catalogue, checkout and petroapps folder. */
    static CaseSource forRun(LaunchPaths paths) {
        return config -> {
            if (config.testPlans().isEmpty()) {
                return List.of();
            }
            try {
                return collect(config, paths).cases(config);
            } catch (RuntimeException e) {
                throw new IOException("the plan's cases could not be collected: " + e.getMessage(), e);
            }
        };
    }

    private static CaseSource collect(LaunchConfig config, LaunchPaths paths) {
        Path repoRoot = paths.repoRoot();
        PlanCatalogue catalogue = paths.catalogue();
        List<String> keys = new ArrayList<>();
        for (String plan : config.testPlans()) {
            for (PlanCase c : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_FIRST).rows()) {
                if (!keys.contains(c.key())) {
                    keys.add(c.key());
                }
            }
        }
        CollectOptions options = new CollectOptions(config.testPlanBranch(), config.dist(),
                repoRoot.resolve(Collect.PULLED_DIR), repoRoot,
                false);
        CollectResult collected = Collect.collect(keys, paths.petroappsRoot(), options);
        return CaseSource.collected(collected, repoRoot);
    }
}
