package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.pats.PatsCaseLoader;
import com.verifone.cat.pats.feature.Gherkin;
import com.verifone.cat.pats.feature.PatsFeature;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.plans.CaseRef;
import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.Selection;
import com.verifone.cat.plans.SelectionError;
import com.verifone.cat.plans.TagExpressionError;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.events.CatEvent;

/**
 * Run start: collect the plan's tests, resolve the plan, widen the run to the whole plan in plan order, and narrow it to
 * an opt-in partial run. The Python CAT's {@code cat.launch.plan} ({@code _collect_suite}, {@code _emit_plan_resolved},
 * {@code _run_whole_plan}, {@code _apply_partial_selection}, {@code _is_plan_end}, {@code _run_started},
 * {@code is_full_plan_run}), which {@link RunLauncher} calls before step 1, in the Python's order.
 *
 * <p>Every line goes to the System Checks log: the collection's report under {@code petrotest.collect}, a plan that did
 * not resolve under {@code cat.plans}, the Python's wording. Nothing here fails the run except a partial selection that
 * cannot be made, which refuses it before step 1 touches anything.
 *
 * <p>Deviations from the Python: the plan is resolved from {@link PlanCases} (or a test's {@link CaseSource}), which
 * gives each case's file as well as its node, so the tag filter, the {@code @plan_end} test and the FEP tags read the
 * hand-migrated suites in the Python CAT's tree and the pulled ones in this checkout alike ({@link #fileOf}). A case's
 * PATS actions for the prerequisite warnings are read as the Python's {@code file_actions} reads them: a
 * {@code -TestCase.xml}, or a {@code .feature} of PATS phrases; anything else is unreadable.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: nothing here touches a device; fake-backed tests only.
 */
final class PlanStart {

    /** The logger of the collection's lines, as the Python's {@code _collect_log}. */
    static final String COLLECT_LOGGER = "petrotest.collect";

    /** The logger of the plan-resolution warnings, as the Python's {@code _emit_plan_resolved}. */
    static final String PLANS_LOGGER = "cat.plans";

    private PlanStart() {
    }

    // -- run.started ---------------------------------------------------------------------------

    /**
     * The launcher's {@code run.started}, from the run's config as it stands now: sent once before collection, and once
     * more, {@code restated}, if run start changed the targets (the Python's {@code _run_started}).
     */
    static CatEvent.RunStarted runStarted(RunContext ctx, boolean restated) {
        LaunchConfig config = ctx.config();
        String rig = config.rig().isEmpty() ? "fake-pos" : config.rig();
        String suite = config.suiteSummary().isEmpty() ? "(no suites)" : config.suiteSummary();
        String cmd = String.join(" ", config.pytestArgv("python"));
        List<String> names;
        try {
            names = config.testPlans().isEmpty() ? List.of() : ctx.paths().catalogue().planNames(config.testPlans());
        } catch (RuntimeException e) {
            names = List.of();
        }
        return new CatEvent.RunStarted(ctx.runId(), rig, suite, cmd, 1, ctx.startedAt(), catVersion(),
                config.upgradeMode().wire(), config.testPlans(), names, config.fakePos(), "", restated);
    }

    private static String catVersion() {
        Package pkg = RunLauncher.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "unversioned" : version;
    }

    // -- collect and resolve ------------------------------------------------------------------

    /**
     * Collects the plan's tests and resolves the plan (the Python's {@code _collect_suite} then
     * {@code _emit_plan_resolved}): the run's entries and the fetch's outcome go on {@code ctx}, a plan that did not
     * resolve is a WARNING, and the plans' whole membership is a {@code plan.resolved} event. Nothing for a run with
     * no plan.
     *
     * @param source the run's case source: {@link PlanCases#forRun}'s, or a test's
     */
    static void resolve(RunContext ctx, CaseSource source) {
        LaunchConfig config = ctx.config();
        if (config.testPlans().isEmpty()) {
            return;
        }
        PlanCases.Resolved resolved;
        try {
            resolved = source instanceof PlanCases.Source plans
                    ? PlanCases.resolveRun(config, plans.paths(), (level, line) -> collectLog(ctx, line, level))
                    : fromSource(source, config);
        } catch (IOException | RuntimeException e) {
            warn(ctx, "could not resolve " + String.join(", ", config.testPlans()) + " to a case list ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); Not Run cannot be counted for this run");
            return;
        }
        ctx.caseEntries(resolved.entries());
        ctx.petroappsFetchError(resolved.fetchError());
        if (!resolved.reasons().isEmpty()) {
            warn(ctx, String.join("; ", resolved.reasons())
                    + " — Not Run and Percent Completed cannot be counted for those plans");
        }
        if (resolved.rows().isEmpty()) {
            return;
        }
        if (resolved.planCases() != null) {
            ctx.planCases(resolved.planCases());
        }
        List<CatEvent.PlanCaseDescriptor> cases = new ArrayList<>();
        for (PlanCase row : resolved.rows()) {
            cases.add(new CatEvent.PlanCaseDescriptor(row.key(), row.summary(), row.node()));
        }
        ctx.emit(new CatEvent.PlanResolved(cases));
    }

    /** A test's case source as a resolution: every entry a row of one plan that resolved, with no summaries. */
    private static PlanCases.Resolved fromSource(CaseSource source, LaunchConfig config) throws IOException {
        if (source == null) {
            return PlanCases.Resolved.NONE;
        }
        List<CaseEntry> entries = source.cases(config);
        List<PlanCase> rows = new ArrayList<>();
        for (CaseEntry entry : entries) {
            rows.add(new PlanCase(entry.key(), "", entry.runnable() ? entry.nodeid() : null));
        }
        List<String> reasons = rows.isEmpty() ? List.of(String.join(", ", config.testPlans()) + ": no cases")
                : List.of();
        return new PlanCases.Resolved(entries, rows, rows.isEmpty() ? null : rows, reasons, null);
    }

    // -- the whole plan ------------------------------------------------------------------------

    /**
     * Points the run at every migrated case of the plan, in plan order, whatever it was given (the Python's
     * {@code _run_whole_plan}; rig owner, 2026-09-26: a plan run is always the whole plan, because cases change rig state
     * for the cases after them). With a plan that did not fully resolve, the run keeps what was on disk and says so.
     */
    static void runWholePlan(RunContext ctx) {
        LaunchConfig config = ctx.config();
        if (config.testPlans().isEmpty()) {
            return;
        }
        List<PlanCase> planCases = ctx.planCases();
        if (planCases == null) {
            List<CaseEntry> entries = ctx.caseEntries() == null ? List.of() : ctx.caseEntries();
            int pulled = 0;
            List<String> onDisk = new ArrayList<>();
            for (CaseEntry entry : entries) {
                if (!entry.runnable()) {
                    continue;
                }
                if (isPulled(entry.nodeid())) {
                    pulled++;
                } else {
                    onDisk.add(entry.nodeid());
                }
            }
            String what = pulled > 0 ? pulled + " pulled test(s) not run" : "the run may be partial";
            collectLog(ctx, what + ": the plan did not fully resolve", "WARNING");
            // The Python keeps the Config tab's targets, which were the tests on disk before collection.
            if (config.suites().isEmpty() && !onDisk.isEmpty()) {
                ctx.config(config.withSuites(List.copyOf(new LinkedHashSet<>(onDisk))));
            }
            return;
        }
        List<String> suites = distinctNodes(planCases);
        if (suites.isEmpty() || suites.equals(config.suites())) {
            return;
        }
        ctx.config(config.withSuites(suites));
        collectLog(ctx, "pytest targets, in plan order: " + String.join(" ", suites), "INFO");
    }

    /** Whether a node is one run start pulled from petroapps ({@code tests/suites/pulled/...}). */
    private static boolean isPulled(String node) {
        String pulled = Collect.PULLED_DIR.toString().replace('\\', '/') + "/";
        return node.replace('\\', '/').startsWith(pulled);
    }

    /** The cases' nodes, each once, first occurrence wins: the Python's {@code dict.fromkeys(case.node ...)}. */
    static List<String> distinctNodes(List<PlanCase> cases) {
        Set<String> nodes = new LinkedHashSet<>();
        for (PlanCase c : cases) {
            if (c.node() != null && !c.node().isEmpty()) {
                nodes.add(c.node());
            }
        }
        return List.copyOf(nodes);
    }

    /**
     * Whether this run is the whole plan, in plan order: the only shape in which a {@code @plan_end} case's verdict
     * means anything (rig owner, 2026-09-17). True only when every case of the chosen plan(s) is migrated, the targets
     * are exactly those files in plan order, and the run is on a rig rather than the fake POS. The Python's
     * {@code is_full_plan_run}, which sets {@code PETROTEST_FULL_PLAN_RUN} for the session.
     */
    static boolean isFullPlanRun(LaunchConfig config, List<PlanCase> planCases) {
        if (planCases == null || planCases.isEmpty() || config.fakePos()) {
            return false;
        }
        for (PlanCase c : planCases) {
            if (c.node() == null || c.node().isEmpty()) {
                return false;
            }
        }
        return config.suites().equals(distinctNodes(planCases));
    }

    // -- the partial run -----------------------------------------------------------------------

    /**
     * Narrows the whole plan to an opt-in selection ({@code --tags}, {@code --only}, {@code --from}): the Python's
     * {@code _apply_partial_selection}. After {@link #runWholePlan}, so the selection is always taken from the whole plan
     * in plan order. Nothing for a run that asked for none. Returns why the run is refused ("" when it is not): a key
     * not in the plan, a tag expression that does not parse, a selection that keeps nothing, or a plan that did not
     * resolve. What the selection leaves out ahead of itself that later cases depend on is logged as WARNINGs.
     */
    static String applyPartialSelection(RunContext ctx) {
        LaunchConfig config = ctx.config();
        if (!config.partial()) {
            return "";
        }
        List<CaseRef> pairs = new ArrayList<>();
        if (!config.testPlans().isEmpty()) {
            if (ctx.planCases() == null) {
                return "a partial run needs its plan's cases, and the plan did not resolve";
            }
            for (PlanCase c : ctx.planCases()) {
                if (c.node() != null && !c.node().isEmpty()) {
                    pairs.add(new CaseRef(c.key(), c.node()));
                }
            }
        } else {
            for (String node : config.suites()) {
                String key = ReportPaths.caseKey(node);
                pairs.add(new CaseRef(key.isEmpty() ? node : key, node));
            }
        }
        Selection selection;
        try {
            selection = PartialRun.select(pairs, config.selectionTags(), config.selectionOnly(),
                    config.selectionFrom(), node -> fileOf(ctx, node));
        } catch (SelectionError | TagExpressionError e) {
            return e.getMessage();
        }
        for (String line : PartialRun.prerequisiteWarnings(selection, node -> fileActions(fileOf(ctx, node)),
                node -> isPlanEnd(fileOf(ctx, node)))) {
            collectLog(ctx, line, "WARNING");
        }
        ctx.config(config.withSuites(selection.nodes()));
        collectLog(ctx, "partial run, pytest targets in plan order: " + String.join(" ", selection.nodes()), "INFO");
        return "";
    }

    /** Whether a case file is a {@code @plan_end} report case: its tag (the Python's {@code _is_plan_end}). */
    static boolean isPlanEnd(Path file) {
        if (file == null) {
            return false;
        }
        try {
            return PartialRun.fileTags(file).stream().anyMatch(tags -> tags.contains("@plan_end"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The file a node names: the run-start entry's file when one has that node (a hand-migrated suite lives in the
     * Python CAT's tree), else the node under the repository root.
     */
    static Path fileOf(RunContext ctx, String node) {
        List<CaseEntry> entries = ctx.caseEntries();
        if (entries != null) {
            for (CaseEntry entry : entries) {
                if (entry.runnable() && entry.nodeid().equals(node)) {
                    return entry.file();
                }
            }
        }
        int at = node.indexOf("::");
        return ctx.paths().repoRoot().resolve(at < 0 ? node : node.substring(0, at));
    }

    /**
     * The PATS action names a case file says, upper-cased, as the Python's {@code selection.file_actions} reads them: a
     * {@code -TestCase.xml}, or a {@code .feature} written one PATS phrase per step. Empty for anything else, or a file
     * that cannot be read.
     */
    static Optional<Set<String>> fileActions(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".xml")) {
                Set<String> names = new HashSet<>();
                for (RunnableAction action : PatsCaseLoader.load(file).actions()) {
                    names.add(action.name().toUpperCase(Locale.ROOT));
                }
                return Optional.of(names);
            }
            if (name.endsWith(".feature")) {
                List<String> lines = new ArrayList<>();
                for (Gherkin.Scenario scenario : Gherkin.read(file).scenarios()) {
                    lines.addAll(scenario.steps());
                }
                return PatsFeature.scenarioActions(lines, false).map(actions -> {
                    Set<String> names = new HashSet<>();
                    actions.forEach(a -> names.add(a.name().toUpperCase(Locale.ROOT)));
                    return names;
                });
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    // -- log lines -----------------------------------------------------------------------------

    /** One line of collection output, in the System Checks log beside the plan warnings. */
    static void collectLog(RunContext ctx, String message, String level) {
        ctx.emit(new CatEvent.LogLine(level, COLLECT_LOGGER, message, LaunchStepNames.SYSTEM_CHECKS));
    }

    private static void warn(RunContext ctx, String message) {
        ctx.emit(new CatEvent.LogLine("WARNING", PLANS_LOGGER, message, LaunchStepNames.SYSTEM_CHECKS));
    }
}
