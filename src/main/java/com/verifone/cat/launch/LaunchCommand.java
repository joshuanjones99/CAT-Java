package com.verifone.cat.launch;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.atlassian.AtlassianPlanSource;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.plans.CaseRef;
import com.verifone.cat.plans.PartialRun;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.plans.SelectionError;
import com.verifone.cat.plans.Snapshot;
import com.verifone.cat.plans.TagExpressionError;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;

/**
 * {@code launch --rig <name|file.yaml> --plan <key>... [options]}: one run, headless, through the seven steps.
 * The Python counterpart is {@code tools/rig/run_plan.py} over {@code launch_run}, and the flags are its flags.
 *
 * <p>Defaults, as {@code run_plan._config}: an explicit flag wins; then the Config tab's last saved choices
 * ({@link LastConfig}: {@code ~/.cat/java/last-config.json}, then the Python CAT's {@code ~/.cat/last-config.json},
 * read-only); then the Python {@code LaunchConfig}'s default. The Commander Connect password comes from the saved
 * file or {@code $COMMANDER_CONNECT_PASSWORD} and is never printed. {@code provision_register} is on, as
 * {@code run_plan} sets it.
 *
 * <p>The plan's cases are read from Xray first ({@link PlanCatalogue.Mode#REFRESH}, which writes the cache) and from
 * the cache alone only with {@code --cache-only}, because a stale cache once lost a case (the Python's HANDOFF
 * §1.166: run 81bfc9fb1541). Run start then reads that fresh cache, as the Python's {@code launch_run} does.
 *
 * <p>On a rig run, the simulators step 3 starts default to every launchable registry row (the Python's
 * {@code default_simulators}) and the optional connections step 2 probes to every extra register and rig device but the
 * physical pinpad ({@code default_connections}), as {@code run_plan} hands them over; run start replaces either with
 * the plans' Simulators or Devices column when it names any ({@link RunRequirements}). {@code --simulator} replaces
 * the default.
 *
 * <p>A partial run ({@code --tags}, {@code --only}, {@code --from}, which combine as the Python's do) is checked before
 * anything starts, as {@code run_plan._refusal} checks it: a tag expression that does not parse, or a key not among the
 * plans' migrated cases, prints {@code [run] refused: <why>} and exits 2. The tags are matched at run start, once
 * collection has pulled the files.
 *
 * <p>Deviations from the Python: {@code --rig} is required (the Python always runs on {@code LAB_RIG}); a
 * {@code --fake-pos} run reads no saved choices and takes no default simulators or connections, so it never starts a
 * real simulator; {@code --dist} is accepted (the Python reads the dist only from the saved file); the saved
 * {@code config-xray} is read (see {@link LastConfig}). The Downloads flags
 * ({@code --commander-branch/--commander-build/--sim-branch/--sim-build}, {@code --solutions-build N} meaning
 * {@code --commander-branch Solutions --commander-build N}) default to the saved Config's Downloads section, a flag's
 * branch taking no saved foundation, as {@code run_plan._config} does. The saved IFSF mode and local build are the
 * Config tab's ({@link LastConfig#ifsfMode}, {@link LastConfig#localBuild}); {@code run_plan} reads neither, nor does
 * this.
 *
 * <p>Exit codes: 0 the run completed, 1 a step failed or was unavailable, 2 a usage error or a refused partial run.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LaunchCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = """
            usage: launch --rig <name|file.yaml> [--plan <key>...]... [--suite <node>]...
                          [--upgrade skip|classic|vhq|new_install] [--exec dev|BDD] [--dist <name>] [--branch <name>]
                          [--commander-branch <branch> --commander-build <n> | --solutions-build <n>]
                          [--sim-branch <branch> --sim-build <n>]
                          [--fep <name>]... [--simulator <name>]... [--no-xray] [--coverage | --no-coverage]
                          [--cc-onboard] [--waive-cc-requirement] [--capture-snapshot] [--snapshot-update]
                          [--buypass-host] [--cache-only] [--tags <expr>] [--only <key>...] [--from <key>]
                          [--fake-pos] [--fake-pinpad]""";

    /** The fake-POS banner, the same words the Python CAT's run log uses. */
    static final String FAKE_POS_BANNER = "[FAKE POS: evidence about CAT, not about a rig]";

    /** The device {@code default_connections} leaves out: the physical pinpad (the Python's DEFAULT_EXCLUDED_DEVICE). */
    static final String DEFAULT_EXCLUDED_DEVICE = "pinpad";

    /** A bound on each Atlassian request the plan and requirements reads make (the Python's 30 s). */
    static final Duration ATLASSIAN_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Runs the command with the environment's paths, a catalogue that reads Xray and Confluence, the saved choices
     * in the home directory and the process streams.
     */
    public int run(String[] argv) {
        Map<String, String> env = System.getenv();
        String home = System.getProperty("user.home");
        return run(argv, withNetwork(LaunchPaths.fromEnv(env), Path.of(home)), LastConfig.fromHome(env, home), env,
                System.out, System.err);
    }

    /** The test seam with no saved choices and no environment. */
    int run(String[] argv, LaunchPaths paths, PrintStream out, PrintStream err) {
        return run(argv, paths, LastConfig.none(), Map.of(), out, err);
    }

    /** The testable seam: the paths, the saved choices, the environment and both streams are passed in. */
    int run(String[] argv, LaunchPaths paths, LastConfig saved, Map<String, String> env, PrintStream out,
            PrintStream err) {
        LaunchConfig config;
        try {
            Options options = Options.parse(argv);
            config = resolve(options, paths, saved, env, out);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        }
        String refused = refusal(config, paths.catalogue());
        if (!refused.isEmpty()) {
            out.println("[run] refused: " + refused);
            return EXIT_USAGE;
        }
        List<String> unwired = config.unwiredOptions();
        if (!unwired.isEmpty()) {
            out.println("[run] Recorded, not wired: " + String.join(", ", unwired));
        }

        RunLauncher launcher = new RunLauncher(paths);
        // Step 5 runs the plan's collected cases through the production handler set, on the fake POS or the rig.
        launcher.wireTests(new StandardRegistry(), PlanCases.forRun(paths), new BrowserConnector());
        LaunchResult result = launcher.launch(config);
        if (config.fakePos()) {
            out.println(FAKE_POS_BANNER);
        }
        out.println("run " + result.runId() + " — " + result.runFolder());
        for (StepResult step : result.steps()) {
            out.println(String.format("  %-24s %-9s%s", step.name(), step.state(),
                    step.detail().isEmpty() ? "" : " — " + step.detail()));
        }
        return result.complete() ? EXIT_OK : EXIT_FAILED;
    }

    /**
     * Why {@code config} cannot start, or "": a partial run whose tag expression does not parse, or whose
     * {@code --only}/{@code --from} keys are not migrated cases of the plans as the cache has them. The Python's
     * {@code run_plan._refusal}, checked here so a refused run touches nothing.
     */
    static String refusal(LaunchConfig config, PlanCatalogue catalogue) {
        if (!config.partial()) {
            return "";
        }
        try {
            PartialRun.parseTags(config.selectionTags());
            if (!config.selectionOnly().isEmpty() || !config.selectionFrom().isEmpty()) {
                List<CaseRef> rows = new ArrayList<>();
                for (String plan : config.testPlans()) {
                    for (PlanCase row : catalogue.cases(plan, PlanCatalogue.Mode.CACHE_FIRST).rows()) {
                        rows.add(new CaseRef(row.key(), row.node()));
                    }
                }
                PartialRun.select(rows, "", config.selectionOnly(), config.selectionFrom(), Path.of("."));
            }
        } catch (SelectionError | TagExpressionError e) {
            return e.getMessage();
        }
        return "";
    }

    /**
     * The config the run starts with: the flags over the saved choices, the plans' cases read (Xray first unless
     * {@code --cache-only}), and the default simulators and connections run start may replace.
     */
    static LaunchConfig resolve(Options o, LaunchPaths paths, LastConfig saved, Map<String, String> env,
            PrintStream out) {
        PlanCatalogue catalogue = paths.catalogue();
        PlanCatalogue.Mode casesMode = casesMode(o);
        for (String plan : o.plans) {
            Snapshot<PlanCase> cases = catalogue.cases(plan, casesMode);
            String reason = cases.reason() == null || cases.reason().isEmpty() ? "" : " — " + cases.reason();
            out.println("[run] " + plan + ": " + cases.rows().size() + " case(s) from "
                    + cases.source().name().toLowerCase(Locale.ROOT) + reason);
        }

        List<String> simulators = o.simulators;
        List<String> connections = List.of();
        if (!o.fakePos) {
            if (simulators.isEmpty()) {
                simulators = defaultSimulators();
            }
            connections = defaultConnections(loadRig(o.rig, paths));
        }
        return o.toConfig(o.fakePos ? LastConfig.none() : saved, env, simulators, connections);
    }

    /** Xray first, which writes the cache; the cache (then the seed) alone only with {@code --cache-only}. */
    static PlanCatalogue.Mode casesMode(Options o) {
        return o.cacheOnly ? PlanCatalogue.Mode.CACHE_ONLY : PlanCatalogue.Mode.REFRESH;
    }

    /** Every simulator CAT starts when the plans name none: the launchable registry rows (the Python's). */
    static List<String> defaultSimulators() {
        return Registry.launchable(false).stream().map(SimEntry::name).toList();
    }

    /** Every optional target step 2 probes when the plans name no devices: all but the physical pinpad. */
    static List<String> defaultConnections(Rig rig) {
        if (rig == null) {
            return List.of();
        }
        return Endpoints.optionalTargetNames(rig).stream()
                .filter(name -> !name.toLowerCase(Locale.ROOT).contains(DEFAULT_EXCLUDED_DEVICE))
                .toList();
    }

    private static Rig loadRig(String rig, LaunchPaths paths) {
        if (rig == null || rig.isBlank()) {
            return null;
        }
        try {
            return RigLoader.load(rig, paths.rigsDir());
        } catch (RuntimeException e) {
            return null; // the launcher's own rig load reports why
        }
    }

    /** {@code paths} with a catalogue whose network source is Xray and Confluence, over the same caches and seed. */
    static LaunchPaths withNetwork(LaunchPaths paths, Path home) {
        AtlassianClient client = AtlassianClient.forCredentialsFile(AtlassianCredentials.defaultPath(),
                ATLASSIAN_TIMEOUT);
        PlanCatalogue catalogue = new PlanCatalogue(home.resolve(".cat").resolve("java"), home.resolve(".cat"),
                new AtlassianPlanSource(client), new SeedPlanSource(), paths.repoRoot());
        return new LaunchPaths(paths.resultsRoot(), paths.rigsDir(), paths.leaseDir(), paths.repoRoot(),
                paths.petroappsRoot(), catalogue, paths.setupEnv(), paths.pythonRoot());
    }

    /** The parsed command line: only what was typed; {@link #toConfig} applies the saved choices and defaults. */
    static final class Options {

        String rig;
        final List<String> suites = new ArrayList<>();
        final List<String> plans = new ArrayList<>();
        final List<String> simulators = new ArrayList<>();
        final List<String> feps = new ArrayList<>();
        final List<String> only = new ArrayList<>();
        String upgrade = "";
        String exec = "";
        String dist = "";
        String branch = "";
        String tags = "";
        String fromCase = "";
        boolean fakePos;
        boolean fakePinpad;
        boolean noXray;
        boolean coverage;
        boolean noCoverage;
        boolean ccOnboard;
        boolean waiveCcRequirement;
        boolean captureSnapshot;
        boolean snapshotUpdate;
        boolean cacheOnly;
        boolean buypassHost;
        String commanderBranch = "";
        String commanderBuild = "";
        String solutionsBuild = "";
        String simBranch = "";
        String simBuild = "";

        static Options parse(String[] argv) {
            Options o = new Options();
            Deque<String> args = new ArrayDeque<>(Arrays.asList(argv));
            while (!args.isEmpty()) {
                String flag = args.pop();
                switch (flag) {
                    case "--rig" -> o.rig = value(flag, args);
                    case "--suite" -> o.suites.add(value(flag, args));
                    case "--plan" -> values(flag, args).forEach(key -> addOnce(o.plans, key));
                    case "--simulator" -> o.simulators.add(value(flag, args));
                    case "--upgrade" -> o.upgrade = UpgradeMode.of(value(flag, args)).wire();
                    case "--exec" -> o.exec = execMode(value(flag, args));
                    case "--dist" -> o.dist = value(flag, args);
                    case "--branch" -> o.branch = value(flag, args);
                    case "--fep" -> addOnce(o.feps, value(flag, args));
                    case "--tags" -> o.tags = value(flag, args);
                    case "--only" -> {
                        for (String part : values(flag, args)) {
                            for (String key : part.split(",")) {
                                if (!key.isBlank()) {
                                    o.only.add(key.strip());
                                }
                            }
                        }
                    }
                    case "--from" -> o.fromCase = value(flag, args);
                    case "--no-xray" -> o.noXray = true;
                    case "--coverage" -> o.coverage = true;
                    case "--no-coverage" -> o.noCoverage = true;
                    case "--cc-onboard" -> o.ccOnboard = true;
                    case "--waive-cc-requirement" -> o.waiveCcRequirement = true;
                    case "--capture-snapshot" -> o.captureSnapshot = true;
                    case "--snapshot-update" -> o.snapshotUpdate = true;
                    case "--cache-only" -> o.cacheOnly = true;
                    case "--buypass-host" -> o.buypassHost = true;
                    case "--fake-pos" -> o.fakePos = true;
                    case "--fake-pinpad" -> o.fakePinpad = true;
                    case "--commander-branch" -> o.commanderBranch = value(flag, args);
                    case "--commander-build" -> o.commanderBuild = value(flag, args);
                    case "--solutions-build" -> o.solutionsBuild = value(flag, args);
                    case "--sim-branch" -> o.simBranch = value(flag, args);
                    case "--sim-build" -> o.simBuild = value(flag, args);
                    default -> throw new IllegalArgumentException("Unknown option " + flag + ".");
                }
            }
            if (o.rig == null && !o.fakePos) {
                throw new IllegalArgumentException("--rig is required.");
            }
            return o;
        }

        /**
         * The config, flags over {@code saved} over the Python's defaults.
         *
         * @param simulators  the simulators step 3 starts, already defaulted by the caller
         * @param connections the optional devices step 2 probes, already defaulted by the caller
         */
        LaunchConfig toConfig(LastConfig saved, Map<String, String> env, List<String> simulators,
                List<String> connections) {
            String upgradeText = first(upgrade, saved.upgrade(), UpgradeMode.SKIP.wire());
            LaunchConfig.Builder builder = new LaunchConfig.Builder(rig == null ? "" : rig)
                    .upgradeMode(UpgradeMode.of(upgradeText))
                    .executionMode(execMode(first(exec, saved.exec(), LaunchConfig.DEFAULT_EXECUTION_MODE)))
                    .dist(first(dist, saved.dist(), LaunchConfig.DEFAULT_DIST))
                    .testPlanBranch(first(branch, saved.testPlanBranch(), LaunchConfig.DEFAULT_TEST_PLAN_BRANCH))
                    .fakePos(fakePos)
                    .fakePinpad(fakePinpad)
                    .snapshotUpdate(snapshotUpdate)
                    .captureSnapshot(captureSnapshot)
                    .buypassHost(buypassHost)
                    // run_plan: a run against the rig always provisions the register in step 4 (never under fake POS).
                    .provisionRegister(true)
                    .xrayReport(!noXray && saved.xray())
                    .coverage(!noCoverage && (coverage || saved.coverage()))
                    .commanderConnect(ccOnboard || saved.ccOnboard())
                    .commanderConnect(saved.commanderConnect(env))
                    .ccRequirementWaived(waiveCcRequirement)
                    .tags(tags.strip())
                    .only(only)
                    .fromCase(fromCase.strip());
            // The Downloads section: a flag's branch takes no saved foundation (run_plan._config); --solutions-build N
            // is --commander-branch Solutions --commander-build N.
            String flagBranch = !commanderBranch.isBlank() ? commanderBranch
                    : solutionsBuild.isBlank() ? "" : "Solutions";
            String flagBuild = !commanderBuild.isBlank() ? commanderBuild : solutionsBuild;
            LastConfig.Downloads downloads = saved.downloads(flagBranch, flagBuild, simBranch, simBuild);
            builder.commanderBranch(downloads.commanderBranch())
                    .commanderBuild(downloads.commanderBuild())
                    .foundationBranch(downloads.foundationBranch())
                    .foundationBuild(downloads.foundationBuild())
                    .simBranch(downloads.simBranch())
                    .simBuild(downloads.simBuild());
            suites.forEach(builder::suite);
            plans.forEach(builder::testPlan);
            simulators.forEach(builder::simulator);
            connections.forEach(builder::connection);
            feps.forEach(builder::secondaryFep);
            return builder.build();
        }

        private static String first(String flag, String saved, String fallback) {
            if (flag != null && !flag.isBlank()) {
                return flag.strip();
            }
            return saved != null && !saved.isBlank() ? saved.strip() : fallback;
        }

        private static String execMode(String value) {
            if (!LaunchConfig.EXECUTION_MODES.contains(value)) {
                throw new IllegalArgumentException("--exec must be one of " + LaunchConfig.EXECUTION_MODES
                        + ", not '" + value + "'.");
            }
            return value;
        }

        private static void addOnce(List<String> list, String value) {
            String v = value.strip();
            if (!v.isEmpty() && !list.contains(v)) {
                list.add(v);
            }
        }

        private static String value(String flag, Deque<String> args) {
            if (args.isEmpty()) {
                throw new IllegalArgumentException(flag + " needs a value.");
            }
            return args.pop();
        }

        /** One or more values, up to the next {@code --flag}, as argparse's {@code nargs="+"}. */
        private static List<String> values(String flag, Deque<String> args) {
            List<String> out = new ArrayList<>();
            out.add(value(flag, args));
            while (!args.isEmpty() && !args.peek().startsWith("--")) {
                out.add(args.pop());
            }
            return out;
        }
    }
}
