package com.verifone.cat.launch;

import com.verifone.cat.collect.CaseResult;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectOptions;
import com.verifone.cat.collect.CollectResult;
import com.verifone.cat.plans.LocalSuites;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.Snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

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
 * <p>{@link #resolveRun} is what the launcher calls at run start ({@link PlanStart}): it also says which plans did not
 * resolve and why, and logs the collection's report, as the Python's {@code _collect_suite} and
 * {@code _emit_plan_resolved} do. A collection that fails is a WARNING and the run goes on with the tests already on
 * disk, as the Python's does.
 *
 * <p>Deviations from the Python: it pulls every key and then lets the local file win; this pulls only the keys no local
 * suite holds (the same cases run, and no 55.02 copy of a 57.02 case lands in {@code pulled/}). Two plans in one run
 * keep one entry per distinct test file (the Python's {@code dict.fromkeys(node)}), so a key that resolves to a
 * different file in each plan runs once from each. With {@link LaunchPaths#pythonRoot} null there are no local suites
 * and every case is pulled, as before. A run with suites and no plan (the authoring replay gate) runs each suite as one
 * authored case file ({@link #suiteCases}); nothing is collected for it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): fake-tested only.
 */
final class PlanCases {

    /** The dist folder that wins for tests by default ({@link LaunchConfig#dist} is used). */
    static final String DIST = "buypass";

    private PlanCases() {
    }

    /**
     * What run start resolved: the entries step 5 runs (one per distinct test file, Not Run ones by key), every plan's
     * rows with the node each resolved to (the {@code plan.resolved} membership), the plan cases when every plan
     * resolved ({@code null} otherwise, as the Python's {@code ctx.plan_cases}), why any plan did not, and the
     * collection's fetch outcome ({@code null} when nothing was fetched).
     */
    record Resolved(List<CaseEntry> entries, List<PlanCase> rows, List<PlanCase> planCases, List<String> reasons,
            String fetchError) {

        Resolved {
            entries = List.copyOf(entries);
            rows = List.copyOf(rows);
            planCases = planCases == null ? null : List.copyOf(planCases);
            reasons = List.copyOf(reasons);
        }

        /** Nothing resolved: a run with no plan. */
        static final Resolved NONE = new Resolved(List.of(), List.of(), null, List.of(), null);
    }

    /** The case source for the run's plans, read from {@code paths}: its catalogue, local suites, checkout, petroapps. */
    static CaseSource forRun(LaunchPaths paths) {
        return new Source(paths);
    }

    /**
     * {@link #forRun}'s source. The launcher recognises it and resolves the plan at run start with
     * {@link #resolveRun}, logging the collection, rather than asking {@link #cases} for the entries alone.
     */
    record Source(LaunchPaths paths) implements CaseSource {

        @Override
        public List<CaseEntry> cases(LaunchConfig config) throws IOException {
            if (config.testPlans().isEmpty()) {
                return suiteCases(config.suites());
            }
            try {
                return resolve(config, paths);
            } catch (RuntimeException e) {
                throw new IOException("the plan's cases could not be collected: " + e.getMessage(), e);
            }
        }
    }

    /**
     * A run with suites and no plan (the authoring replay gate): each suite is one authored case file, its node id the
     * suite exactly as given and its file that path, its key the file's case ({@code PETROCORE-61338-TestCase.feature}
     * gives {@code PETROCORE-61338}; {@code collect.Selection.caseOf}).
     */
    static List<CaseEntry> suiteCases(List<String> suites) {
        List<CaseEntry> entries = new ArrayList<>();
        for (String suite : suites) {
            Path file = Path.of(suite);
            Path name = file.getFileName();
            entries.add(new CaseEntry(com.verifone.cat.collect.Selection.caseOf(
                    name == null ? suite : name.toString()), suite, file));
        }
        return List.copyOf(entries);
    }

    private record Planned(String key, String summary, LocalSuites.Resolution local) {}

    /** The run's entries in plan order: local first, then pulled, else Not Run. A failed collection throws. */
    static List<CaseEntry> resolve(LaunchConfig config, LaunchPaths paths) {
        return resolveRun(config, paths, null).entries();
    }

    /**
     * The run's cases, resolved as {@link #resolve} does, with the plans' rows and reasons. {@code log} gets the
     * collection's report and its warnings ({@code level, message}); with {@code log} null a failed collection throws
     * instead of being a WARNING.
     */
    static Resolved resolveRun(LaunchConfig config, LaunchPaths paths, BiConsumer<String, String> log) {
        if (config.testPlans().isEmpty()) {
            return Resolved.NONE;
        }
        PlanCatalogue catalogue = paths.catalogue();
        LocalSuites local = paths.pythonRoot() == null ? null : new LocalSuites(paths.pythonRoot());
        List<Planned> planned = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Set<String> toPull = new LinkedHashSet<>();
        for (String plan : config.testPlans()) {
            Snapshot<PlanCase> snapshot = catalogue.cases(plan, PlanCatalogue.Mode.CACHE_FIRST);
            if (snapshot.rows().isEmpty()) {
                reasons.add(plan + ": " + (snapshot.reason().isEmpty() ? "no cases" : snapshot.reason()));
            }
            for (PlanCase c : snapshot.rows()) {
                LocalSuites.Resolution found = local == null ? null : local.resolve(plan, c.key());
                planned.add(new Planned(c.key(), c.summary(), found));
                if (found == null || found.kind() == LocalSuites.Kind.ABSENT) {
                    toPull.add(c.key());
                }
            }
        }
        Map<String, String> pulled = Map.of();
        String fetchError = null;
        if (!toPull.isEmpty()) {
            try {
                CollectResult collected = collect(List.copyOf(toPull), config, paths);
                pulled = nodes(collected);
                if (collected.fetched != null) {
                    fetchError = collected.fetchError == null ? "" : collected.fetchError;
                }
                if (log != null) {
                    for (String line : collected.lines()) {
                        log.accept(line.startsWith("WARNING") ? "WARNING" : "INFO", line);
                    }
                }
            } catch (RuntimeException e) {
                if (log == null) {
                    throw e;
                }
                // The Python's _collect_suite: a petroapps that cannot be read leaves the run to what is on disk.
                log.accept("WARNING", "collecting from petroapps failed (" + e.getClass().getSimpleName() + ": "
                        + e.getMessage() + "); running only the tests already on disk");
            }
        }
        List<CaseEntry> entries = new ArrayList<>();
        List<PlanCase> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Planned p : planned) {
            CaseEntry entry = entry(p, pulled, paths.repoRoot());
            rows.add(new PlanCase(p.key(), p.summary(), entry.runnable() ? entry.nodeid() : null));
            if (seen.add(entry.runnable() ? "file:" + entry.file() : "notrun:" + entry.key())) {
                entries.add(entry);
            }
        }
        return new Resolved(entries, rows, reasons.isEmpty() ? rows : null, reasons, fetchError);
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

    /** Collects {@code keys} from petroapps into {@code <repoRoot>/tests/suites/pulled}. */
    private static CollectResult collect(List<String> keys, LaunchConfig config, LaunchPaths paths) {
        Path repoRoot = paths.repoRoot();
        // fetch=true, as the Python's _collect_suite (cat/launch/plan.py): the tests come from origin/<branch> as the
        // remote has it now; a failed fetch falls back to the local ref with a WARNING line.
        CollectOptions options = new CollectOptions(config.testPlanBranch(), config.dist(),
                repoRoot.resolve(Collect.PULLED_DIR), repoRoot,
                true);
        return Collect.collect(keys, paths.petroappsRoot(), options);
    }

    /** Case key to the pulled test's repository-relative node, for the cases that pulled one. */
    private static Map<String, String> nodes(CollectResult collected) {
        Map<String, String> nodes = new HashMap<>();
        for (CaseResult result : collected.cases) {
            if (result.node() != null) {
                nodes.putIfAbsent(result.key, result.node());
            }
        }
        return nodes;
    }
}
