package com.verifone.cat.runconfig;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.Requirements;
import com.verifone.cat.plans.Snapshot;
import com.verifone.cat.plans.TestPlan;

/**
 * The Config tab's choices: the ticked plans, the Run scope, and the saved file they live in.
 *
 * <p>Ported from {@code cat.web.form.ConfigForm} and the Run configuration section of
 * {@code cat.panels.config}. The rules are the Python CAT's: a plan is ticked from the cached
 * catalogue only (the picker never reaches the network), a saved plan that is no longer offered
 * is dropped, a Tags edit resets the hand ticks, and every change is saved to the same file under
 * the same keys. Keys this form does not own (the placeholder sections' choices, the Downloads
 * keys, anything a newer CAT wrote) are kept as they were read, so a save never drops them.
 *
 * <p>One form per process, shared by every browser tab, as in the TUI. All methods are
 * synchronized: the web server calls them from its request threads.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: this is the choice and the saved file only. Nothing here starts
 * a run, reads a rig or reaches Confluence.
 */
public final class ConfigForm {

    /** The Run configuration's ticked plans. */
    public static final String KEY_PLANS = "config-plans";

    /** Where the checkout's {@code tests/suites} node paths resolve, unless the env says otherwise. */
    public static final String ENV_REPO_ROOT = "CAT_REPO_ROOT";
    public static final Path DEFAULT_REPO_ROOT = Path.of("C:\\gitrepos\\CAT");

    private final PlanCatalogue catalogue;
    private final Path savedPath;
    private final Path repoRoot;
    private final CaseReader reader;

    private Map<String, Object> saved = new LinkedHashMap<>();
    private List<String> plans = List.of();
    private String filter = "";
    private RunScope scope = RunScope.whole();

    public ConfigForm(PlanCatalogue catalogue, Path savedPath, Path repoRoot) {
        this.catalogue = catalogue;
        this.savedPath = savedPath;
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.reader = new CaseReader(this.repoRoot);
    }

    /** The form the CAT-Java web UI uses: the offline catalogue and the TUI's saved file, loaded. */
    public static ConfigForm standard() {
        String fromEnv = System.getenv(ENV_REPO_ROOT);
        Path root = fromEnv == null || fromEnv.isBlank() ? DEFAULT_REPO_ROOT : Path.of(fromEnv);
        ConfigForm form = new ConfigForm(PlanCatalogue.offline(root), SavedChoices.defaultPath(), root);
        form.load();
        return form;
    }

    /**
     * Reads the cached catalogue and the saved choices. The single offered plan is ticked, as
     * the TUI does; a saved list of plans keeps only the ones still offered.
     */
    public synchronized void load() {
        List<TestPlan> offered = offered();
        plans = offered.size() == 1 ? List.of(offered.get(0).key()) : List.of();
        saved = SavedChoices.read(savedPath);
        if (saved.get(KEY_PLANS) instanceof List<?> wanted) {
            Set<String> keys = offered.stream().map(TestPlan::key).collect(Collectors.toSet());
            List<String> kept = new ArrayList<>();
            for (Object item : wanted) {
                if (item != null && keys.contains(String.valueOf(item)) && !kept.contains(String.valueOf(item))) {
                    kept.add(String.valueOf(item));
                }
            }
            plans = List.copyOf(kept);
        }
        scope = RunScope.fromSaved(saved);
        filter = "";
    }

    // ------------------------------------------------------------------ changes

    /** Ticks or unticks a plan. A key the catalogue does not offer is ignored. */
    public synchronized void togglePlan(String key) {
        if (offered().stream().noneMatch(plan -> plan.key().equals(key))) {
            return;
        }
        List<String> next = new ArrayList<>(plans);
        if (!next.remove(key)) {
            next.add(key);
        }
        plans = List.copyOf(next);
        save();
    }

    /** The plan filter, as typed. Not saved: the TUI's filter is not a choice either. */
    public synchronized void setFilter(String text) {
        filter = text == null ? "" : text.strip();
    }

    /**
     * Sets the Run scope from a form. {@code mode} is one of the scope's values and an unknown one
     * is ignored; {@code tags} is {@code null} when the form did not carry the field (any mode but
     * Tags). A Tags text that differs from the saved one resets the hand ticks, as the TUI does.
     */
    public synchronized void setScope(String mode, String tags, boolean prerequisites) {
        RunScope next = scope;
        next = RunScope.Mode.of(mode).map(next::withMode).orElse(next);
        if (tags != null && !tags.equals(next.tags())) {
            next = next.withTags(tags);
        }
        next = next.withPrerequisites(prerequisites);
        if (!next.equals(scope)) {
            scope = next;
            save();
        }
    }

    /** A tick by hand on the Run scope's case list, as the TUI's list does it. */
    public synchronized void tickCase(String key) {
        List<ScopeCase> cases = cases();
        List<String> keys = cases.stream().map(ScopeCase::key).toList();
        switch (scope.mode()) {
            case CASES -> {
                Set<String> ticked = new LinkedHashSet<>(scope.cases());
                if (!ticked.remove(key)) {
                    ticked.add(key);
                }
                List<String> ordered = new ArrayList<>();
                for (String k : keys) {
                    if (ticked.contains(k)) {
                        ordered.add(k);
                    }
                }
                for (String k : ticked) {
                    if (!keys.contains(k)) {
                        ordered.add(k);
                    }
                }
                scope = scope.withCases(ordered);
            }
            case TAGS -> {
                Set<String> chosen = new LinkedHashSet<>(resolve(cases).chosen());
                if (!chosen.remove(key)) {
                    chosen.add(key);
                }
                scope = scope.withTagCases(keys.stream().filter(chosen::contains).toList());
            }
            case FROM -> scope = scope.withFromCase(key);
            default -> {
                return; // Whole plan has no list to tick
            }
        }
        save();
    }

    // ------------------------------------------------------------------ the view

    /** Everything the page draws, from one read. */
    public synchronized ConfigView view() {
        Snapshot<TestPlan> picker = catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY);
        List<ScopeCase> cases = cases();
        ScopeResolver.Result result = resolve(cases);
        boolean partial = scope.mode() != RunScope.Mode.WHOLE;
        List<String> missing = missing(cases);

        boolean problem = !result.problem().isEmpty();
        String summaryLine = partial && !problem ? ScopeText.summaryLine(scope, result) : "";
        List<String> warnings = new ArrayList<>();
        if (partial) {
            warnings.addAll(problem
                    ? List.of("Nothing to run yet: pick " + result.problem() + ".")
                    : ScopeText.warningLines(scope, result));
        }
        String tagCount = scope.mode() == RunScope.Mode.TAGS && result.tagError().isEmpty() && !cases.isEmpty()
                ? result.matched().size() + " of " + cases.size() + " cases match"
                : "";

        Set<String> chosen = new LinkedHashSet<>(result.chosen());
        Map<String, String> added = new LinkedHashMap<>();
        for (PartialRun.Prerequisite prerequisite : result.added()) {
            added.put(prerequisite.key(), prerequisite.neededBy());
        }
        Map<String, CaseFacts> facts = facts(cases);
        List<ConfigView.CaseRow> rows = new ArrayList<>();
        for (ScopeCase item : cases) {
            CaseFacts own = facts.get(item.node());
            rows.add(new ConfigView.CaseRow(
                    item.key(),
                    item.title(),
                    own == null ? "" : own.tagWords(item.key()),
                    chosen.contains(item.key()) || added.containsKey(item.key()),
                    added.containsKey(item.key()),
                    added.getOrDefault(item.key(), "")));
        }

        return new ConfigView(
                filter,
                sections(picker),
                picker.rows().size(),
                picker.source().label(),
                picker.reason(),
                ageWords(picker, Instant.now()),
                caseLines(),
                needs(),
                scope,
                partial,
                summaryLine,
                warnings,
                tagCount,
                rows,
                missing,
                summaryCard(missing, cases));
    }

    /** Why a run cannot start from these choices, in the words "Nothing to run yet" uses. */
    public synchronized List<String> missingRequirements() {
        return missing(cases());
    }

    /** The saved keys this form does not own, plus the ones it does, as a save writes them. */
    public synchronized Map<String, Object> savedChoices() {
        Map<String, Object> choices = new LinkedHashMap<>(saved);
        if (!catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY).rows().isEmpty()) {
            choices.put(KEY_PLANS, new ArrayList<>(plans));
        }
        choices.putAll(scope.saved());
        return choices;
    }

    /** The ticked plans, in tick order. */
    public synchronized List<String> plans() {
        return plans;
    }

    public synchronized RunScope scope() {
        return scope;
    }

    public Path savedPath() {
        return savedPath;
    }

    // ------------------------------------------------------------------ internals

    private void save() {
        SavedChoices.write(savedPath, savedChoices());
        saved = new LinkedHashMap<>(savedChoices());
    }

    private List<TestPlan> offered() {
        return catalogue.catalogue(PlanCatalogue.Mode.CACHE_ONLY).rows();
    }

    /** The ticked plans' migrated cases in run order, each key once (Python {@code scope_cases}). */
    private List<ScopeCase> cases() {
        Set<String> seen = new LinkedHashSet<>();
        List<ScopeCase> out = new ArrayList<>();
        for (String plan : plans) {
            for (PlanCase row : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY).rows()) {
                if (row.node() != null && !row.node().isEmpty() && seen.add(row.key())) {
                    out.add(new ScopeCase(row.key(), row.node(), row.summary()));
                }
            }
        }
        return out;
    }

    private Map<String, CaseFacts> facts(List<ScopeCase> cases) {
        Map<String, CaseFacts> out = new LinkedHashMap<>();
        for (ScopeCase item : cases) {
            out.computeIfAbsent(item.node(), reader::facts);
        }
        return out;
    }

    private ScopeResolver.Result resolve(List<ScopeCase> cases) {
        return ScopeResolver.resolve(scope, cases, facts(cases), repoRoot);
    }

    /**
     * The Python CAT's {@code missing_requirements}: a test plan, a plan with a migrated case, and
     * a Run scope that selects something. Downloads and Commander Connect are not built yet.
     */
    private List<String> missing(List<ScopeCase> cases) {
        List<String> missing = new ArrayList<>();
        if (plans.isEmpty()) {
            missing.add("a test plan");
            return missing;
        }
        if (cases.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            for (String plan : plans) {
                Snapshot<PlanCase> snapshot = catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY);
                reasons.add(snapshot.reason().isEmpty() ? "no migrated cases" : snapshot.reason());
            }
            missing.add("a test plan with at least one migrated case (" + String.join("; ", reasons) + ")");
            return missing;
        }
        ScopeResolver.Result result = resolve(cases);
        if (!result.problem().isEmpty()) {
            missing.add(result.problem());
        }
        return missing;
    }

    /** The picker's sections in page order, Deprecated last, filtered by the plan filter. */
    private List<ConfigView.Section> sections(Snapshot<TestPlan> picker) {
        String needle = filter.toLowerCase(Locale.ROOT);
        Map<String, List<ConfigView.PlanRow>> bySection = new LinkedHashMap<>();
        for (TestPlan plan : picker.rows()) {
            String label = plan.label();
            if (!needle.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            String name = plan.section().isEmpty() ? "Test plans" : plan.section();
            bySection.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new ConfigView.PlanRow(plan.key(), label, plans.contains(plan.key())));
        }
        List<ConfigView.Section> sections = new ArrayList<>();
        bySection.forEach((name, rows) -> sections.add(new ConfigView.Section(name, rows)));
        // Stable: only the Deprecated group moves, to the bottom.
        sections.sort((a, b) -> Boolean.compare(
                a.name().toLowerCase(Locale.ROOT).contains("deprecated"),
                b.name().toLowerCase(Locale.ROOT).contains("deprecated")));
        return sections;
    }

    private List<String> caseLines() {
        List<String> lines = new ArrayList<>();
        for (String plan : plans) {
            Snapshot<PlanCase> snapshot = catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY);
            lines.add(plan + ": " + (snapshot.rows().isEmpty() ? snapshot.reason() : caseSummary(snapshot.rows())));
        }
        return lines;
    }

    /** {@code 39 cases · 13 runnable · 26 not migrated}, as the Python CAT counts them. */
    static String caseSummary(List<PlanCase> cases) {
        int runnable = (int) cases.stream().filter(c -> c.node() != null && !c.node().isEmpty()).count();
        return cases.size() + " cases \u00b7 " + runnable + " runnable \u00b7 "
                + (cases.size() - runnable) + " not migrated";
    }

    /** {@code Needs: ...} for the ticked plans (Python {@code describe}). */
    private String needs() {
        if (plans.isEmpty()) {
            return "";
        }
        Requirements requirements = Requirements.forPlans(plans,
                catalogue.requirements(PlanCatalogue.Mode.CACHE_ONLY).rows());
        List<String> parts = new ArrayList<>();
        requirements.baseRules().forEach((key, rule) -> parts.add(key + " " + rule));
        if (!requirements.commanderConnect().isEmpty()) {
            parts.add("Commander Connect (" + String.join(", ", requirements.commanderConnect()) + ")");
        }
        if (!requirements.simulators().isEmpty()) {
            parts.add("simulators " + String.join(", ", requirements.simulators()));
        }
        if (!requirements.devices().isEmpty()) {
            parts.add("devices " + String.join(", ", requirements.devices()));
        }
        if (!requirements.unlisted().isEmpty()) {
            parts.add("not on the page: " + String.join(", ", requirements.unlisted()));
        }
        return "Needs: " + (parts.isEmpty() ? "nothing beyond the defaults" : String.join("; ", parts));
    }

    private List<String> summaryCard(List<String> missing, List<ScopeCase> cases) {
        if (!missing.isEmpty()) {
            return List.of("Nothing to run yet: pick " + String.join(" and ", missing) + ".",
                    "Open the Config tab to set it.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Test plan(s):  " + String.join(", ", plans));
        lines.add("Cases:         " + cases.size() + " in the scope's list");
        if (scope.mode() != RunScope.Mode.WHOLE) {
            lines.add("Run scope:     " + ScopeText.summaryLine(scope, resolve(cases)));
        }
        return lines;
    }

    /** {@code shipped snapshot - never fetched here}, {@code cached 5 min ago}, ... */
    static String ageWords(Snapshot<?> snapshot, Instant now) {
        if (snapshot.source() == Snapshot.Source.SEED) {
            return "shipped snapshot - never fetched here";
        }
        Optional<Duration> age = snapshot.age(now);
        if (age.isEmpty()) {
            return "never fetched";
        }
        long seconds = age.get().getSeconds();
        if (seconds < 60) {
            return "cached just now";
        }
        if (seconds < 3600) {
            return "cached " + seconds / 60 + " min ago";
        }
        if (seconds < 86400) {
            return "cached " + seconds / 3600 + " h ago";
        }
        return "cached " + seconds / 86400 + " days ago";
    }
}
