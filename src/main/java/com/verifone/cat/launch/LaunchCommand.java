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
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.Requirements;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.plans.Snapshot;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.runconfig.RunScope;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;

/**
 * {@code launch --rig <name|file.yaml> --plan <key>... [options]}: one run, headless, through the seven steps.
 * The Python counterpart is {@code tools/rig/run_plan.py} over {@code launch_run}, and the flags are its flags.
 *
 * <p>Defaults, as {@code run_plan._config}: an explicit flag wins; then the Config tab's last saved choices
 * ({@link LastConfig}: {@code ~/.cat/java/last-config.json}, then the Python CAT's {@code ~/.cat/last-config.json},
 * read-only); then the Python {@code LaunchConfig}'s default. The Commander Connect password comes from the saved
 * file or {@code $COMMANDER_CONNECT_PASSWORD} and is never printed.
 *
 * <p>The plan's cases are read from Xray first ({@link PlanCatalogue.Mode#REFRESH}, which writes the cache) and from
 * the cache alone only with {@code --cache-only}, because a stale cache once lost a case (the Python's HANDOFF
 * §1.166: run 81bfc9fb1541). Step 5 then reads that fresh cache, as the Python's {@code launch_run} does.
 *
 * <p>On a rig run, the simulators step 3 starts are the plans' Simulators column when it names any, otherwise every
 * launchable registry row (the Python's {@code default_simulators}); {@code --simulator} replaces both. The optional
 * connections step 2 probes are the plans' Devices column less the printer when it names any, otherwise every extra
 * register and rig device but the physical pinpad ({@code default_connections}). The Python decides both at run start
 * ({@code cat.launch.requirements.apply_requirements}); this port decides them here, before the launch.
 *
 * <p>Deviations from the Python: {@code --rig} is required (the Python always runs on {@code LAB_RIG}); a
 * {@code --fake-pos} run reads no saved choices and takes no default simulators or connections, so it never starts a
 * real simulator; {@code --tags}, {@code --only} and {@code --from} are one scope at a time (the Java
 * {@link RunScope} holds one mode), and the launcher refuses a partial run until that port lands;
 * {@code --solutions-build} is not accepted; {@code --dist} is accepted (the Python reads the dist only from the
 * saved file); the saved {@code config-xray} is read (see {@link LastConfig}). The Downloads flags
 * ({@code --commander-branch/--commander-build/--sim-branch/--sim-build}) are another slice's.
 *
 * <p>Exit codes: 0 the run completed, 1 a step failed or was unavailable, 2 a usage error.
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
                          [--fep <name>]... [--simulator <name>]... [--no-xray] [--coverage | --no-coverage]
                          [--cc-onboard] [--waive-cc-requirement] [--capture-snapshot] [--snapshot-update]
                          [--buypass-host] [--cache-only] [--tags <expr> | --only <key>... | --from <key>]
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
     * The config the run starts with: the flags over the saved choices, the plans' cases read (Xray first unless
     * {@code --cache-only}), and the default simulators and connections from the plans' requirements.
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
            Requirements requirements = Requirements.forPlans(o.plans, o.plans.isEmpty() ? List.of()
                    : catalogue.requirements(PlanCatalogue.Mode.REFRESH).rows());
            if (simulators.isEmpty()) {
                simulators = requirements.simulators().isEmpty() ? defaultSimulators() : requirements.simulators();
            }
            connections = requirements.devices().isEmpty() ? defaultConnections(loadRig(o.rig, paths))
                    : requirements.devices().stream()
                            .filter(name -> !name.equalsIgnoreCase(Requirements.PRINTER)).toList();
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
                paths.petroappsRoot(), catalogue, paths.setupEnv());
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
                    default -> throw new IllegalArgumentException("Unknown option " + flag + ".");
                }
            }
            if (o.rig == null && !o.fakePos) {
                throw new IllegalArgumentException("--rig is required.");
            }
            int scopes = (o.tags.isBlank() ? 0 : 1) + (o.only.isEmpty() ? 0 : 1) + (o.fromCase.isBlank() ? 0 : 1);
            if (scopes > 1) {
                throw new IllegalArgumentException("--tags, --only and --from are one scope at a time in CAT-Java.");
            }
            return o;
        }

        /** The run's scope: the whole plan unless one of {@code --tags}, {@code --only}, {@code --from} was given. */
        RunScope scope() {
            if (!only.isEmpty()) {
                return new RunScope(RunScope.Mode.CASES, "", only, null, "", true);
            }
            if (!fromCase.isBlank()) {
                return new RunScope(RunScope.Mode.FROM, "", List.of(), null, fromCase.strip(), true);
            }
            if (!tags.isBlank()) {
                return new RunScope(RunScope.Mode.TAGS, tags.strip(), List.of(), null, "", true);
            }
            return RunScope.whole();
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
                    .xrayReport(!noXray && saved.xray())
                    .coverage(!noCoverage && (coverage || saved.coverage()))
                    .commanderConnect(ccOnboard || saved.ccOnboard())
                    .commanderConnect(saved.commanderConnect(env))
                    .ccRequirementWaived(waiveCcRequirement)
                    .scope(scope());
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
