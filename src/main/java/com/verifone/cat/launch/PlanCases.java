package com.verifone.cat.launch;

import com.verifone.cat.collect.CaseResult;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectOptions;
import com.verifone.cat.collect.CollectResult;
import com.verifone.cat.plans.LocalSuites;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The run's cases for {@code --plan}: each plan's case keys from the plan catalogue (cache, then the shipped seed), each
 * resolved first against the hand-migrated suites ({@link LocalSuites}: the Python CAT's {@code tests/suites/**}), and
 * the rest collected from petroapps by {@link Collect}, in plan order.
 *
 * <p>Precedence, per (plan, case), as the Python's {@code load_cases} after {@code _collect_suite}
 * ({@code cat.plans.resolve_node}, {@code _in_plan_suite}, decision A4: a hand-migrated file wins over a pulled one):
 * <ol>
 *   <li>a {@code PLAN_SUITES} plan's own folder (PETROCORE-79667: {@code tests/suites/cadence_55_02/});</li>
 *   <li>the first file of the case's number under {@code tests/suites/**}, plan-owned folders left out (118517 and
 *       135127 take {@code tests/suites/cadence/}); an {@code @unmigrated} placeholder there is Not Run;</li>
 *   <li>the test pulled from petroapps into {@code <repoRoot>/tests/suites/pulled}; none is Not Run.</li>
 * </ol>
 * The entry's file is the local or pulled test, so the case's {@code -CATReceipt.txt}/{@code -CATTlog.xml} baselines
 * are the ones beside it ({@code TestsRunStep} gives the run that folder). Why it matters: the petroapps test-plan
 * branch can hold one file per case key, and on PETROCORE-141728 it holds the 55.02 Cadence translation, so pulling
 * every case ran 57.02's plans with 55.02 steps and baselines (run dec5794107fa).
 *
 * <p>Deviations from the Python: it pulls every key and then lets the local file win; this pulls only the keys no local
 * suite holds (the same cases run, and no 55.02 copy of a 57.02 case lands in {@code pulled/}). Two plans in one run
 * keep one entry per distinct test file (the Python's {@code dict.fromkeys(node)}), so a key that resolves to a
 * different file in each plan runs once from each. With {@link LaunchPaths#pythonRoot} null there are no local suites
 * and every case is pulled, as before. Suites ({@code --suite}) are not collected here yet, so a run with suites and no
 * plan has no cases, and the step fails saying so.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): fake-tested only.
 */
final class PlanCases {

    /** The dist folder that wins for tests by default ({@link LaunchConfig#dist} is used). */
    static final String DIST = "buypass";

    private PlanCases() {
    }

    /** The case source for the run's plans, read from {@code paths}: its catalogue, local suites, checkout, petroapps. */
    static CaseSource forRun(LaunchPaths paths) {
        return config -> {
            if (config.testPlans().isEmpty()) {
                return List.of();
            }
            try {
                return resolve(config, paths);
            } catch (RuntimeException e) {
                throw new IOException("the plan's cases could not be collected: " + e.getMessage(), e);
            }
        };
    }

    private record Planned(String key, LocalSuites.Resolution local) {}

    /** The run's entries in plan order: local first, then pulled, else Not Run. */
    static List<CaseEntry> resolve(LaunchConfig config, LaunchPaths paths) {
        PlanCatalogue catalogue = paths.catalogue();
        LocalSuites local = paths.pythonRoot() == null ? null : new LocalSuites(paths.pythonRoot());
        List<Planned> planned = new ArrayList<>();
        Set<String> toPull = new LinkedHashSet<>();
        for (String plan : config.testPlans()) {
            for (PlanCase c : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_FIRST).rows()) {
                LocalSuites.Resolution found = local == null ? null : local.resolve(plan, c.key());
                planned.add(new Planned(c.key(), found));
                if (found == null || found.kind() == LocalSuites.Kind.ABSENT) {
                    toPull.add(c.key());
                }
            }
        }
        Map<String, String> pulled = toPull.isEmpty() ? Map.of() : collect(List.copyOf(toPull), config, paths);
        List<CaseEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Planned p : planned) {
            CaseEntry entry = entry(p, pulled, paths.repoRoot());
            if (seen.add(entry.runnable() ? "file:" + entry.file() : "notrun:" + entry.key())) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static CaseEntry entry(Planned p, Map<String, String> pulled, Path repoRoot) {
        LocalSuites.Resolution found = p.local();
        if (found != null && found.kind() == LocalSuites.Kind.LOCAL) {
            return new CaseEntry(p.key(), found.node(), found.file());
        }
        if (found != null && found.kind() == LocalSuites.Kind.UNMIGRATED) {
            return new CaseEntry(p.key(), null, null);
        }
        String node = pulled.get(p.key());
        return node == null ? new CaseEntry(p.key(), null, null) : new CaseEntry(p.key(), node, repoRoot.resolve(node));
    }

    /** Case key to the pulled test's repository-relative node, for the cases that pulled one. */
    private static Map<String, String> collect(List<String> keys, LaunchConfig config, LaunchPaths paths) {
        Path repoRoot = paths.repoRoot();
        // fetch=true, as the Python's _collect_suite (cat/launch/plan.py): the tests come from origin/<branch> as the
        // remote has it now; a failed fetch falls back to the local ref with a WARNING line.
        CollectOptions options = new CollectOptions(config.testPlanBranch(), config.dist(),
                repoRoot.resolve(Collect.PULLED_DIR), repoRoot,
                true);
        CollectResult collected = Collect.collect(keys, paths.petroappsRoot(), options);
        Map<String, String> nodes = new HashMap<>();
        for (CaseResult result : collected.cases) {
            if (result.node() != null) {
                nodes.putIfAbsent(result.key, result.node());
            }
        }
        return nodes;
    }
}
