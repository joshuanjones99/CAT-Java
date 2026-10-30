package com.verifone.cat.runconfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianPlanSource;
import com.verifone.cat.collect.Collect;
import com.verifone.cat.collect.CollectException;
import com.verifone.cat.collect.CollectOptions;
import com.verifone.cat.collect.CollectResult;
import com.verifone.cat.collect.CaseResult;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.Requirements;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.plans.Snapshot;
import com.verifone.cat.plans.TestPlan;
import com.verifone.cat.results.RunIndex;

/**
 * The Config tab's choices: the ticked plans, the Run scope, the test plan branch, Commander Connect, Execution, the
 * Downloads section's choice, and the saved file they all live in.
 *
 * <p>Ported from {@code cat.web.form.ConfigForm} and {@code cat.panels.config}. The rules are the Python CAT's: a
 * plan is ticked from the cached catalogue only (the picker never reaches the network on its own), a saved plan that
 * is no longer offered is dropped, a Tags edit resets the hand ticks, every change is saved to the file under the
 * Python's keys ({@code config-exec}, {@code config-tp-branch}, {@code config-cc-*}, the nine Downloads keys of
 * {@link DownloadsState#KEYS}, ...), and keys this form does not own are kept as they were read.
 *
 * <p>The blocking paths run on a background thread, as the Python's {@code ask}: Refresh from Confluence (the
 * catalogue and the ticked plans' cases, from the network catalogue), and a ticked plan's case list fetched from Jira
 * when none was, once per plan per session. Both need {@link #withNetwork}; with none (tests) Refresh says so and
 * nothing is fetched. A plan with no migrated case may still run when petroapps holds a translated test for one of
 * its cases ({@link #petroappsTests}, a dry-run {@code collect}, read-only, never network).
 *
 * <p>One form per process, shared by every browser tab, as in the TUI. All methods are synchronized.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the choice and the saved file; Refresh and the case fetch reach Confluence and
 * Jira only through the network catalogue, which tests never attach.
 */
public final class ConfigForm {

    /** The Run configuration's ticked plans. */
    public static final String KEY_PLANS = "config-plans";

    /** The Config tab's "Report to Xray" switch, the Python CAT's {@code config-xray}. */
    public static final String KEY_XRAY = "config-xray";

    public static final String KEY_EXEC = "config-exec";
    public static final String KEY_IFSF = "config-ifsf";
    public static final String KEY_CC_LAB = "config-cc-lab";
    public static final String KEY_TP_BRANCH = "config-tp-branch";
    public static final String KEY_CC_EMAIL = "config-cc-email";
    public static final String KEY_CC_PASSWORD = "config-cc-password";
    public static final String KEY_CC_SERVICE_ID = "config-cc-service-id";
    public static final String KEY_COVERAGE = "config-coverage";
    public static final String KEY_CC_ONBOARD = "config-cc-onboard";

    /** The plain choices {@link #set} takes, by name. */
    public static final List<String> CHOICES = List.of("tpBranch", "ccOnboard", "ccLab", "ccEmail", "ccPassword",
            "ccServiceId", "execMode", "ifsfMode", "coverage", "xray");

    /** Where the checkout's {@code tests/suites} node paths resolve, unless the env says otherwise. */
    public static final String ENV_REPO_ROOT = "CAT_REPO_ROOT";
    public static final Path DEFAULT_REPO_ROOT = Path.of("C:\\gitrepos\\CAT");

    /** How long an Atlassian request may take from Refresh or the case fetch. */
    static final Duration ATLASSIAN_TIMEOUT = Duration.ofSeconds(60);

    private final PlanCatalogue catalogue;
    private final Path savedPath;
    private final Path repoRoot;
    private final CaseReader reader;

    private PlanCatalogue network;
    private Executor background = Runnable::run;
    private Path petroappsRoot;
    private RecordedDurations durations = new RecordedDurations(null);
    private DownloadsState downloads;
    private Runnable onFetched = () -> { };

    private Map<String, Object> saved = new LinkedHashMap<>();
    private List<String> plans = List.of();
    private String filter = "";
    private RunScope scope = RunScope.whole();
    private boolean xray = true;
    private String tpBranch = "master";
    private boolean ccOnboard;
    private String ccLab = "Prod";
    private String ccEmail = "";
    private String ccPassword = "";
    private String ccServiceId = "";
    private String execMode = "dev";
    private String ifsfMode = "";
    private boolean coverage;
    private boolean loading;

    private boolean refreshing;
    private String refreshNote = "";
    private final Set<String> fetchingCases = new LinkedHashSet<>();
    private final Set<String> autoFetched = new HashSet<>();
    private boolean runWhenFetched;
    private final Map<String, Map.Entry<List<String>, String>> petroappsMemo = new HashMap<>();

    public ConfigForm(PlanCatalogue catalogue, Path savedPath, Path repoRoot) {
        this.catalogue = catalogue;
        this.savedPath = savedPath;
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.reader = new CaseReader(this.repoRoot);
    }

    /**
     * The form the CAT-Java web UI uses, offline: the cached catalogue, the Results index for the Tags estimate,
     * and the saved file, loaded. A real session adds the network ({@link #online()}); a page rendered in a test
     * never reaches Confluence, Jira or petroapps.
     */
    public static ConfigForm standard() {
        Map<String, String> env = System.getenv();
        Path root = repoRoot(env);
        ConfigForm form = new ConfigForm(PlanCatalogue.offline(root), SavedChoices.defaultPath(), root);
        form.withResultsIndex(RunIndex.indexPath(env));
        form.load();
        return form;
    }

    /**
     * This form in a real session: the network catalogue behind Refresh and the case fetch (Confluence and Xray,
     * with CAT-Java's Atlassian file, else the shared one), and petroapps for the translated-test check. Only
     * {@code serve} calls it.
     */
    public ConfigForm online() {
        Map<String, String> env = System.getenv();
        Path home = Path.of(System.getProperty("user.home"));
        Path own = home.resolve(".cat").resolve("java").resolve("jira_api_token.properties");
        Path jira = Files.isRegularFile(own) ? own : home.resolve("jira_api_token.properties");
        PlanCatalogue networkCatalogue = new PlanCatalogue(home.resolve(".cat").resolve("java"), home.resolve(".cat"),
                new AtlassianPlanSource(AtlassianClient.forCredentialsFile(jira, ATLASSIAN_TIMEOUT)),
                new SeedPlanSource(), repoRoot);
        withNetwork(networkCatalogue, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cat-web-config");
            thread.setDaemon(true);
            return thread;
        }));
        String petroapps = env.getOrDefault("PETROAPPS_ROOT", "").strip();
        withPetroapps(Path.of(petroapps.isEmpty() ? Collect.DEFAULT_PETROAPPS_ROOT : petroapps));
        return this;
    }

    /** {@code $CAT_REPO_ROOT}, else the Python checkout: where the plans' node paths resolve. */
    public static Path repoRoot(Map<String, String> env) {
        String fromEnv = env.get(ENV_REPO_ROOT);
        return fromEnv == null || fromEnv.isBlank() ? DEFAULT_REPO_ROOT : Path.of(fromEnv);
    }

    // ------------------------------------------------------------------ wiring

    /** The catalogue Refresh and the case fetch read from (it writes the shared cache), run on {@code executor}. */
    public synchronized ConfigForm withNetwork(PlanCatalogue networkCatalogue, Executor executor) {
        this.network = networkCatalogue;
        this.background = executor;
        return this;
    }

    /** The petroapps checkout a plan's translated tests are looked for in. */
    public synchronized ConfigForm withPetroapps(Path root) {
        this.petroappsRoot = root;
        petroappsMemo.clear();
        return this;
    }

    /** The Results index the Tags count's time estimate reads. */
    public synchronized ConfigForm withResultsIndex(Path index) {
        this.durations = new RecordedDurations(index);
        return this;
    }

    /**
     * The Downloads section's choice, restored from the saved file now and saved with the form on every change.
     * The web shell attaches the one Downloads service the Config tab and Install Upgrade share.
     */
    public ConfigForm attachDownloads(DownloadsState state) {
        synchronized (this) {
            downloads = state;
            restoreDownloads();
        }
        state.onChange(this::saveNow);
        return this;
    }

    /** Runs when a case-list fetch Start run waited on lands (the web Run tab starts the run then). */
    public synchronized void onFetched(Runnable start) {
        onFetched = start == null ? () -> { } : start;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Reads the cached catalogue and the saved choices. The single offered plan is ticked, as the TUI does; a saved
     * list of plans keeps only the ones still offered; each saved choice is restored only if it is still offered.
     */
    public synchronized void load() {
        loading = true;
        try {
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
            xray = flag(KEY_XRAY, true);
            coverage = flag(KEY_COVERAGE, false);
            ccOnboard = flag(KEY_CC_ONBOARD, false);
            execMode = text(KEY_EXEC, "dev");
            if (ConfigHelp.EXEC_OPTIONS.stream().noneMatch(o -> o.getValue().equals(execMode))) {
                execMode = "dev";
            }
            ifsfMode = text(KEY_IFSF, "");
            if (ConfigHelp.IFSF_OPTIONS.stream().noneMatch(o -> o.getValue().equals(ifsfMode))) {
                ifsfMode = "";
            }
            ccLab = text(KEY_CC_LAB, "Prod");
            if (!CommanderConnect.LABS.contains(ccLab)) {
                ccLab = "Prod";
            }
            tpBranch = text(KEY_TP_BRANCH, "master");
            ccEmail = text(KEY_CC_EMAIL, "");
            ccPassword = text(KEY_CC_PASSWORD, "");
            ccServiceId = text(KEY_CC_SERVICE_ID, "");
            filter = "";
            restoreDownloads();
        } finally {
            loading = false;
        }
    }

    private String text(String key, String fallback) {
        return saved.get(key) instanceof String value ? value : fallback;
    }

    private boolean flag(String key, boolean fallback) {
        return saved.get(key) instanceof Boolean value ? value : fallback;
    }

    /** The Python's {@code _restore} of the nine Downloads keys: a build without its branch is dropped. */
    private void restoreDownloads() {
        if (downloads == null) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (String key : DownloadsState.KEYS) {
            values.add(saved.get(key) instanceof String value ? value.strip() : "");
        }
        String cmdBranch = values.get(0);
        String fndBranch = values.get(5);
        String mode = values.get(2);
        if (!mode.isEmpty()) {
            try {
                UpgradeMode.of(mode);
            } catch (IllegalArgumentException e) {
                mode = "";
            }
        }
        downloads.restore(new DownloadsState.Choice(
                cmdBranch,
                cmdBranch.isEmpty() ? "" : values.get(1),
                mode,
                values.get(3),
                values.get(3).isEmpty() ? "" : values.get(4),
                cmdBranch.isEmpty() ? "" : fndBranch,
                !cmdBranch.isEmpty() && !fndBranch.isEmpty() ? values.get(6) : "",
                values.get(7).isEmpty() ? LaunchConfigDefaults.DIST : values.get(7),
                cmdBranch.isEmpty() ? values.get(8) : ""));
    }

    /** The defaults the Python's form starts from. */
    static final class LaunchConfigDefaults {
        static final String DIST = "buypass";

        private LaunchConfigDefaults() {}
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
     * Sets one plain choice and saves: the Python's {@code ConfigForm.set}. {@code name} is one of {@link #CHOICES};
     * a switch takes {@code on}/{@code true}, anything else is off. A value the form does not offer (an unknown lab,
     * execution mode or IFSF flag) is refused.
     *
     * @return false when the name or value was refused (nothing changed)
     */
    public synchronized boolean set(String name, String value) {
        String v = value == null ? "" : value;
        boolean on = "on".equalsIgnoreCase(v.strip()) || "true".equalsIgnoreCase(v.strip());
        switch (name == null ? "" : name) {
            case "tpBranch" -> {
                String branch = v.strip();
                if (!branch.isEmpty() && !branch.matches("[A-Za-z0-9._/-]{1,200}")) {
                    return false;
                }
                tpBranch = branch;
                petroappsMemo.clear();
            }
            case "ccOnboard" -> ccOnboard = on;
            case "ccLab" -> {
                if (!CommanderConnect.LABS.contains(v.strip())) {
                    return false;
                }
                ccLab = v.strip();
            }
            case "ccEmail" -> ccEmail = v;
            case "ccPassword" -> ccPassword = v;
            case "ccServiceId" -> ccServiceId = v;
            case "execMode" -> {
                if (ConfigHelp.EXEC_OPTIONS.stream().noneMatch(o -> o.getValue().equals(v))) {
                    return false;
                }
                execMode = v;
            }
            case "ifsfMode" -> {
                if (ConfigHelp.IFSF_OPTIONS.stream().noneMatch(o -> o.getValue().equals(v))) {
                    return false;
                }
                ifsfMode = v;
            }
            case "coverage" -> coverage = on;
            case "xray" -> xray = on;
            default -> {
                return false;
            }
        }
        save();
        return true;
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

    // ------------------------------------------------------------------ the network paths

    /**
     * Refresh from Confluence, in the background: the catalogue and the ticked plans' cases, read afresh. A failure
     * keeps the cache and says why beside the picker. Returns "" when started, or why not.
     */
    public synchronized String refreshFromConfluence() {
        if (refreshing) {
            return "";
        }
        if (network == null) {
            refreshNote = "refresh failed: no Atlassian source in this session";
            return refreshNote;
        }
        refreshing = true;
        refreshNote = "";
        List<String> keys = List.copyOf(plans);
        PlanCatalogue source = network;
        background.execute(() -> {
            String note = "";
            try {
                Snapshot<TestPlan> snapshot = source.catalogue(PlanCatalogue.Mode.REFRESH);
                if (snapshot.source() != Snapshot.Source.NETWORK) {
                    note = snapshot.reason().isEmpty() ? "" : "refresh failed: " + snapshot.reason();
                }
                for (String key : keys) {
                    source.cases(key, PlanCatalogue.Mode.REFRESH);
                }
            } catch (RuntimeException e) {
                note = "refresh failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            synchronized (ConfigForm.this) {
                refreshing = false;
                refreshNote = note;
                Set<String> offered = offered().stream().map(TestPlan::key).collect(Collectors.toSet());
                plans = plans.stream().filter(offered::contains).toList();
                petroappsMemo.clear();
            }
        });
        return "";
    }

    /** Whether Refresh from Confluence is reading the page now. */
    public synchronized boolean refreshing() {
        return refreshing;
    }

    /**
     * A ticked plan with no fetched case list is fetched from Jira in the background, once per plan per session:
     * the Python's {@code _fetch_missing_cases}. Called from every view.
     */
    private void fetchMissingCases() {
        if (network == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        for (String plan : plans) {
            if (autoFetched.contains(plan)) {
                continue;
            }
            Snapshot<PlanCase> snapshot = catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY);
            if (snapshot.rows().isEmpty() || snapshot.source() == Snapshot.Source.SEED
                    || snapshot.source() == Snapshot.Source.EMPTY) {
                keys.add(plan);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        autoFetched.addAll(keys);
        fetchingCases.addAll(keys);
        PlanCatalogue source = network;
        background.execute(() -> {
            for (String key : keys) {
                try {
                    source.cases(key, PlanCatalogue.Mode.REFRESH);
                } catch (RuntimeException e) {
                    // Never a crash on a network path; the cache stands.
                }
            }
            Runnable start = null;
            synchronized (ConfigForm.this) {
                fetchingCases.removeAll(keys);
                petroappsMemo.clear();
                if (runWhenFetched && fetchingCases.isEmpty()) {
                    runWhenFetched = false;
                    start = onFetched;
                }
            }
            if (start != null) {
                start.run();
            }
        });
    }

    /** The ticked plans whose case list is being fetched from Jira now. */
    public synchronized List<String> fetchingCases() {
        return List.copyOf(fetchingCases);
    }

    /**
     * Start run pressed while a case list is still being fetched: start it when the lists land (the Python's
     * {@code run_when_fetched}). Returns the toast to show, or "" when nothing is being fetched.
     */
    public synchronized String runWhenFetched() {
        if (fetchingCases.isEmpty()) {
            return "";
        }
        runWhenFetched = true;
        return "Fetching the case list for " + String.join(", ", fetchingCases) + " from Jira; "
                + "the run starts when it arrives.";
    }

    // ------------------------------------------------------------------ the view

    /** Everything the page draws, from one read. */
    public synchronized ConfigView view() {
        fetchMissingCases();
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
        String tagCount = "";
        if (scope.mode() == RunScope.Mode.TAGS && !cases.isEmpty()) {
            Map<String, String> nodes = new HashMap<>();
            for (ScopeCase item : cases) {
                nodes.put(item.key(), item.node().split("::", 2)[0]);
            }
            tagCount = RecordedDurations.countText(scope, result, nodes, durations.read());
        }

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

        String note = picker.reason();
        if (!refreshNote.isEmpty()) {
            note = refreshNote + (picker.rows().isEmpty() ? "; " + picker.reason()
                    : " \u2014 showing " + sourceWords(picker.source()));
        }
        return new ConfigView(
                filter,
                sections(picker),
                picker.rows().size(),
                picker.source().label(),
                note,
                refreshing ? "refreshing\u2026" : ageWords(picker, Instant.now()),
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

    /** The sections after Run scope, as data. */
    public synchronized ConfigControls controls() {
        return new ConfigControls(tpBranch, ccOnboard, ccLab, CommanderConnect.LABS, ccEmail, ccPassword,
                ccServiceId, execMode, ConfigHelp.EXEC_OPTIONS, ifsfMode, ConfigHelp.IFSF_OPTIONS, coverage, xray,
                refreshing, List.copyOf(fetchingCases), ConfigHelp.HELP);
    }

    private static String sourceWords(Snapshot.Source source) {
        return switch (source) {
            case CACHE -> "the cached copy";
            case SEED -> "the shipped snapshot";
            default -> source.label();
        };
    }

    /** Why a run cannot start from these choices, in the words "Nothing to run yet" uses. */
    public synchronized List<String> missingRequirements() {
        return missing(cases());
    }

    /**
     * Every launch field the Config tab decides, or empty when it cannot start a run ({@link #missingRequirements()}
     * says why). The web Run tab builds its {@code LaunchConfig} from these ({@link LaunchFields#toConfig}).
     */
    public synchronized Optional<LaunchFields> launchFields() {
        if (!missing(cases()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fields(runnableNodes(), plans));
    }

    /** Every choice, but no plan and no suite: the Install Upgrade page's (the Python's {@code install_config}). */
    public synchronized LaunchFields installFields() {
        return fields(List.of(), List.of());
    }

    private LaunchFields fields(List<String> suites, List<String> planIds) {
        DownloadsState.Choice d = downloads == null
                ? new DownloadsState.Choice("", "", "skip", "", "", "", "", LaunchConfigDefaults.DIST, "")
                : downloads.current();
        UpgradeMode mode;
        try {
            mode = d.upgradeMode().isEmpty() ? UpgradeMode.SKIP : UpgradeMode.of(d.upgradeMode());
        } catch (IllegalArgumentException e) {
            mode = UpgradeMode.SKIP;
        }
        boolean foundation = !d.foundationBranch().isEmpty() && !d.foundationBuild().isEmpty();
        return new LaunchFields(planIds, suites, scope, mode, d.dist(),
                tpBranch.strip().isEmpty() ? "master" : tpBranch.strip(),
                execMode.isEmpty() ? "dev" : execMode, ifsfMode, coverage,
                d.commanderBuild().isEmpty() ? "" : d.commanderBranch(),
                d.commanderBranch().isEmpty() ? "" : d.commanderBuild(),
                d.commanderBranch().isEmpty() ? d.localBuild() : "",
                foundation ? d.foundationBranch() : "",
                foundation ? d.foundationBuild() : "",
                d.simBuild().isEmpty() ? "" : d.simBranch(),
                d.simBranch().isEmpty() ? "" : d.simBuild(),
                true, xray, ccOnboard,
                new CommanderConnect(ccLab.isEmpty() ? "Prod" : ccLab, ccEmail.strip(), ccServiceId.strip(),
                        ccPassword.strip()));
    }

    /** The saved keys this form does not own, plus the ones it does, as a save writes them. */
    public synchronized Map<String, Object> savedChoices() {
        Map<String, Object> choices = new LinkedHashMap<>(saved);
        choices.put(KEY_EXEC, execMode);
        choices.put(KEY_IFSF, ifsfMode);
        choices.put(KEY_CC_LAB, ccLab);
        if (downloads != null) {
            List<String> values = downloads.current().values();
            for (int i = 0; i < DownloadsState.KEYS.size(); i++) {
                choices.put(DownloadsState.KEYS.get(i), values.get(i));
            }
        }
        choices.put(KEY_TP_BRANCH, tpBranch);
        choices.put(KEY_CC_EMAIL, ccEmail);
        choices.put(KEY_CC_PASSWORD, ccPassword);
        choices.put(KEY_CC_SERVICE_ID, ccServiceId);
        choices.put(KEY_XRAY, xray);
        choices.put(KEY_COVERAGE, coverage);
        choices.put(KEY_CC_ONBOARD, ccOnboard);
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

    /** Whether the run reports to Xray: the saved {@code config-xray}, on when nothing is saved. */
    public synchronized boolean xray() {
        return xray;
    }

    /** The Commander Connect Onboard switch. */
    public synchronized boolean ccOnboard() {
        return ccOnboard;
    }

    public Path savedPath() {
        return savedPath;
    }

    // ------------------------------------------------------------------ internals

    private synchronized void saveNow() {
        save();
    }

    /** The Python's {@code save}: the same keys, the same file. Never throws; nothing while loading. */
    private void save() {
        if (loading) {
            return;
        }
        Map<String, Object> choices = savedChoices();
        SavedChoices.write(savedPath, choices);
        saved = new LinkedHashMap<>(choices);
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

    /** The ticked plans' migrated nodes, each once, in run order (the Python's {@code runnable_nodes}). */
    private List<String> runnableNodes() {
        Set<String> nodes = new LinkedHashSet<>();
        for (String plan : plans) {
            for (PlanCase row : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY).rows()) {
                if (row.node() != null && !row.node().isEmpty()) {
                    nodes.add(row.node());
                }
            }
        }
        return List.copyOf(nodes);
    }

    /**
     * The ticked plans' cases with no migrated test that petroapps holds a translated test for, and where that was
     * read: the Python's {@code petroapps_cases} and {@code petroapps_tests}. A dry-run collect of the checkout's
     * {@code origin/<branch>}, else {@code <branch>}: read-only, never network; memoised until the branch changes,
     * a case list lands, or Refresh. Empty with no checkout configured (tests).
     */
    public synchronized Map.Entry<List<String>, String> petroappsCases() {
        Set<String> keys = new LinkedHashSet<>();
        for (String plan : plans) {
            for (PlanCase row : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY).rows()) {
                if (row.node() == null || row.node().isEmpty()) {
                    keys.add(row.key());
                }
            }
        }
        if (keys.isEmpty() || petroappsRoot == null) {
            return Map.entry(List.of(), "");
        }
        String dist = downloads == null ? LaunchConfigDefaults.DIST : downloads.current().dist();
        return petroappsTests(List.copyOf(keys), tpBranch.isBlank() ? "master" : tpBranch.strip(), dist);
    }

    private Map.Entry<List<String>, String> petroappsTests(List<String> keys, String branch, String dist) {
        String memo = branch + "|" + dist + "|" + String.join(",", keys);
        Map.Entry<List<String>, String> known = petroappsMemo.get(memo);
        if (known != null) {
            return known;
        }
        Map.Entry<List<String>, String> found;
        if (!Files.exists(petroappsRoot.resolve(".git"))) {
            found = Map.entry(List.of(), "petroapps: no checkout at " + petroappsRoot);
        } else {
            try {
                CollectResult result = Collect.collect(keys, petroappsRoot,
                        new CollectOptions(branch, dist, null, repoRoot, false));
                List<String> withTest = result.cases.stream().filter(CaseResult::hasTest).map(c -> c.key).toList();
                String commit = result.commit == null ? "" : result.commit;
                found = Map.entry(withTest, "petroapps " + result.ref + " ("
                        + commit.substring(0, Math.min(11, commit.length())) + ")");
            } catch (CollectException e) {
                found = Map.entry(List.of(), "petroapps: unreadable (" + e.getMessage() + ")");
            }
        }
        petroappsMemo.put(memo, found);
        return found;
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
     * The Python CAT's {@code missing_requirements}, word for word: a test plan; a plan with a migrated case, or one
     * petroapps holds a translated test for; a Run scope that selects something; then the Downloads blockers.
     */
    private List<String> missing(List<ScopeCase> cases) {
        List<String> missing = new ArrayList<>();
        if (plans.isEmpty()) {
            missing.add("a test plan");
        } else if (cases.isEmpty()) {
            Map.Entry<List<String>, String> pulled = petroappsCases();
            List<String> reasons = new ArrayList<>();
            for (String plan : plans) {
                Snapshot<PlanCase> snapshot = catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY);
                reasons.add(snapshot.reason().isEmpty() ? "no migrated cases" : snapshot.reason());
            }
            if (!pulled.getValue().isEmpty()) {
                reasons.add("none has a translated test in " + pulled.getValue());
            }
            if (pulled.getKey().isEmpty()) {
                missing.add("a test plan with at least one migrated case (" + String.join("; ", reasons) + ")");
            }
        }
        if (!plans.isEmpty() && missing.isEmpty() && !cases.isEmpty()) {
            ScopeResolver.Result result = resolve(cases);
            if (!result.problem().isEmpty()) {
                missing.add(result.problem());
            }
        }
        if (downloads != null) {
            missing.addAll(downloads.problems());
        }
        return missing;
    }

    /** The picker's sections in page order, Deprecated last, filtered by the plan filter. */
    private List<ConfigView.Section> sections(Snapshot<TestPlan> picker) {
        String needle = filter.toLowerCase(Locale.ROOT);
        Map<String, List<ConfigView.PlanRow>> bySection = new LinkedHashMap<>();
        for (TestPlan plan : picker.rows()) {
            String label = planRowLabel(plan);
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

    /** The width {@code TestPlan.label} pads the feature to; a longer one is cut with {@code …}. */
    static final int FEATURE_WIDTH = 34;

    /**
     * One picker row, counting cases as Xray does wherever the case list is known: the Python's
     * {@code plan_row_label}. A fetched plan shows Xray's count, and names the page's when the two differ; a plan
     * never fetched keeps the page's number. Then the {@link #runnableHint}.
     */
    String planRowLabel(TestPlan plan) {
        TestPlan fitted = plan.feature().length() <= FEATURE_WIDTH ? plan
                : new TestPlan(plan.key(), plan.feature().substring(0, FEATURE_WIDTH - 1) + "\u2026", plan.section(),
                        plan.status(), plan.caseCount(), plan.testSet(), plan.kind());
        List<PlanCase> rows = localCases(plan.key());
        String hint = runnableHint(rows);
        if (rows.isEmpty()) {
            return fitted.label() + "  " + hint;
        }
        String xray = String.valueOf(rows.size());
        TestPlan counted = new TestPlan(fitted.key(), fitted.feature(), fitted.section(), fitted.status(), xray,
                fitted.testSet(), fitted.kind());
        String page = plan.caseCount().strip();
        String note = !page.isEmpty() && !page.equals(xray) ? "  (Confluence page says " + page + ")" : "";
        return counted.label() + "  " + hint + note;
    }

    /** One plan's local case list (cache, then the Python's cache, then the seed); empty when none reads. */
    private List<PlanCase> localCases(String key) {
        try {
            Snapshot<PlanCase> snapshot = catalogue.cases(key, PlanCatalogue.Mode.CACHE_ONLY);
            return snapshot.source() == Snapshot.Source.EMPTY ? List.of() : snapshot.rows();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** {@code 13 runnable} / {@code none runnable} / {@code cases not fetched}: the Python's {@code runnable_hint}. */
    static String runnableHint(List<PlanCase> rows) {
        if (rows.isEmpty()) {
            return "cases not fetched";
        }
        long runnable = rows.stream().filter(c -> c.node() != null && !c.node().isEmpty()).count();
        return runnable > 0 ? runnable + " runnable" : "none runnable";
    }

    private List<String> caseLines() {
        List<String> lines = new ArrayList<>();
        for (String plan : plans) {
            Snapshot<PlanCase> snapshot = catalogue.cases(plan, PlanCatalogue.Mode.CACHE_ONLY);
            String line = plan + ": " + (snapshot.rows().isEmpty() ? snapshot.reason() : caseSummary(snapshot.rows()));
            if (fetchingCases.contains(plan)) {
                line += " (fetching the case list from Jira\u2026)";
            }
            lines.add(line);
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
        LaunchFields f = fields(runnableNodes(), plans);
        List<String> lines = new ArrayList<>();
        lines.add("Test plan(s):  " + String.join(", ", plans));
        if (cases.isEmpty()) {
            Map.Entry<List<String>, String> pulled = petroappsCases();
            lines.add("Suites:        none on disk; " + pulled.getKey().size() + " case(s) to pull from "
                    + pulled.getValue() + " at run start");
        }
        lines.add("Cases:         " + cases.size() + " in the scope's list");
        if (scope.mode() != RunScope.Mode.WHOLE) {
            lines.add("Run scope:     " + ScopeText.summaryLine(scope, resolve(cases)));
        }
        lines.add("Upgrade:       " + f.upgradeMode().wire());
        lines.add("Commander:     " + (f.commanderBranch().isEmpty()
                ? (f.localBuild().isEmpty() ? "the newest " + f.dist() + " build on this PC" : f.localBuild())
                : f.commanderBranch() + " " + f.commanderBuild() + " (" + f.dist() + ")"));
        lines.add("Sim build:     " + (f.simBranch().isEmpty() ? "the staged simulators"
                : f.simBranch() + " " + f.simBuild()));
        lines.add("Test branch:   " + f.testPlanBranch());
        lines.add("Xray:          " + (f.xrayReport() ? "a test execution per plan, updated live" : "off"));
        lines.add("Coverage:      " + (f.coverage()
                ? "JaCoCo, attached to Jira (reboots the Commander in step 6)" : "off"));
        lines.add("Cmdr Connect:  " + (f.commanderConnect()
                ? "onboard to " + f.commanderConnectSettings().lab() + " as "
                        + (f.commanderConnectSettings().email().isEmpty() ? "(no email)"
                                : f.commanderConnectSettings().email())
                : "off"));
        // The run's own list (LaunchConfig.unwiredOptions, the Python's unwired_options); the rig is not read.
        List<String> unwired = f.toConfig("config", List.of(), List.of()).unwiredOptions();
        if (!unwired.isEmpty()) {
            lines.add("Recorded, not wired: " + String.join(", ", unwired));
        }
        return lines;
    }

    /** {@code shipped snapshot - never fetched here}, {@code cached 5m ago}, ... (the Python's {@code cache_age}) */
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
            return "cached " + seconds / 60 + "m ago";
        }
        if (seconds < 86400) {
            return "cached " + seconds / 3600 + "h ago";
        }
        return "cached " + seconds / 86400 + "d ago";
    }
}
