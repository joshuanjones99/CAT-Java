package com.verifone.cat.launch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.atlassian.AtlassianPlanSource;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.Requirements;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.runconfig.RunScope;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimEntry;

/**
 * The run-start defaults a run from the web UI takes, the way {@code launch} ({@link LaunchCommand}) takes them: the
 * Python CAT's {@code cat.launch.requirements.default_simulators} / {@code default_connections} and the web form's
 * {@code FormLaunchConfigMixin._launch_config}.
 *
 * <ul>
 *   <li>The simulators step 3 starts are the plans' Simulators column when it names any, otherwise every launchable
 *       registry row; the optional connections step 2 probes are the plans' Devices column less the printer when it
 *       names any, otherwise every extra register and rig device but the physical pinpad.</li>
 *   <li>The rest of the config is the Config tab's saved choices ({@link LastConfig}) over the Python
 *       {@code LaunchConfig}'s defaults, with no command-line flag over them; the Downloads section, when the page's
 *       Downloads choice is given, replaces the saved one. {@code provision_register} is on, as the Python form sets
 *       it.</li>
 *   <li>The plan catalogue reads Xray and Confluence ({@link #withNetwork}), and step 5 is wired with the production
 *       registry, the plan's cases and the browser connector ({@link #wiredLauncher}).</li>
 * </ul>
 *
 * <p>Extracted for the web without editing {@link LaunchCommand}, whose {@code defaultSimulators},
 * {@code defaultConnections}, {@code withNetwork}, its wiring and its {@code Options.toConfig} hold the same rules: the
 * two are to be deduplicated (LaunchCommand calling these).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the launcher {@link #wiredLauncher} makes drives a rig.
 */
public final class LaunchDefaults {

    /** The device {@code default_connections} leaves out: the physical pinpad. */
    public static final String DEFAULT_EXCLUDED_DEVICE = "pinpad";

    /** A bound on each Atlassian request the plan and requirements reads make (the Python's 30 s). */
    public static final Duration ATLASSIAN_TIMEOUT = Duration.ofSeconds(30);

    private LaunchDefaults() {}

    /** Every simulator CAT starts when the plans name none: the launchable registry rows. */
    public static List<String> defaultSimulators() {
        return Registry.launchable(false).stream().map(SimEntry::name).toList();
    }

    /** Every optional target step 2 probes when the plans name no devices: all but the physical pinpad. */
    public static List<String> defaultConnections(Rig rig) {
        if (rig == null) {
            return List.of();
        }
        return Endpoints.optionalTargetNames(rig).stream()
                .filter(name -> !name.toLowerCase(Locale.ROOT).contains(DEFAULT_EXCLUDED_DEVICE))
                .toList();
    }

    /**
     * What steps 2 and 3 will reach for these plans.
     *
     * @param simulators         the simulators step 3 starts
     * @param connections        the optional connections step 2 probes (the three fixed ones are always probed)
     * @param simulatorsFromPlan whether the plans' Simulators column named them (else every launchable row)
     * @param devicesFromPlan    whether the plans' Devices column named the connections
     */
    public record Picked(List<String> simulators, List<String> connections, boolean simulatorsFromPlan,
            boolean devicesFromPlan) {

        public Picked {
            simulators = List.copyOf(simulators);
            connections = List.copyOf(connections);
        }
    }

    /** The simulators and connections for {@code plans}, from their requirements rows and the rig. */
    public static Picked pick(Collection<String> plans, Collection<PlanRequirements> rows, Rig rig) {
        Requirements requirements = Requirements.forPlans(plans, plans.isEmpty() ? List.of() : rows);
        boolean namedSims = !requirements.simulators().isEmpty();
        boolean namedDevices = !requirements.devices().isEmpty();
        List<String> simulators = namedSims ? requirements.simulators() : defaultSimulators();
        List<String> connections = namedDevices
                ? requirements.devices().stream().filter(name -> !name.equalsIgnoreCase(Requirements.PRINTER)).toList()
                : defaultConnections(rig);
        return new Picked(simulators, connections, namedSims, namedDevices);
    }

    /**
     * The Config tab's Downloads choice as the web page holds it.
     *
     * @param mode            the upgrade mode
     * @param dist            the distribution folder
     * @param commanderBranch "" for none
     * @param commanderBuild  "" for none
     * @param localBuild      a build folder on this PC when no branch is chosen; "" is the newest
     * @param simBranch       "" for none
     * @param simBuild        "" for none
     */
    public record Downloads(UpgradeMode mode, String dist, String commanderBranch, String commanderBuild,
            String localBuild, String simBranch, String simBuild) {}

    /**
     * The config a run of {@code plans} starts with: the saved choices over the defaults (no flags), the page's
     * Downloads choice over the saved one when given, and {@code picked}'s simulators and connections.
     *
     * @param downloads the page's Downloads choice, or null to take the saved one
     */
    public static LaunchConfig fromSaved(String rig, List<String> plans, RunScope scope, boolean xray,
            LastConfig saved, Map<String, String> env, Picked picked, Downloads downloads) {
        LaunchConfig.Builder builder = new LaunchConfig.Builder(rig == null ? "" : rig)
                .executionMode(execMode(first(saved.exec(), LaunchConfig.DEFAULT_EXECUTION_MODE)))
                .testPlanBranch(first(saved.testPlanBranch(), LaunchConfig.DEFAULT_TEST_PLAN_BRANCH))
                .xrayReport(xray)
                .coverage(saved.coverage())
                .commanderConnect(saved.ccOnboard())
                .commanderConnect(saved.commanderConnect(env))
                .provisionRegister(true)
                .scope(scope == null ? RunScope.whole() : scope);
        if (downloads == null) {
            builder.upgradeMode(UpgradeMode.of(first(saved.upgrade(), UpgradeMode.SKIP.wire())))
                    .dist(first(saved.dist(), LaunchConfig.DEFAULT_DIST));
            LastConfig.Downloads chosen = saved.downloads("", "", "", "");
            builder.commanderBranch(chosen.commanderBranch())
                    .commanderBuild(chosen.commanderBuild())
                    .foundationBranch(chosen.foundationBranch())
                    .foundationBuild(chosen.foundationBuild())
                    .simBranch(chosen.simBranch())
                    .simBuild(chosen.simBuild());
        } else {
            boolean commander = !downloads.commanderBranch().isEmpty() && !downloads.commanderBuild().isEmpty();
            boolean sims = !downloads.simBranch().isEmpty() && !downloads.simBuild().isEmpty();
            builder.upgradeMode(downloads.mode() == null ? UpgradeMode.SKIP : downloads.mode())
                    .dist(first(downloads.dist(), LaunchConfig.DEFAULT_DIST))
                    .commanderBranch(commander ? downloads.commanderBranch() : "")
                    .commanderBuild(commander ? downloads.commanderBuild() : "")
                    .localBuild(downloads.commanderBranch().isEmpty() ? downloads.localBuild() : "")
                    .simBranch(sims ? downloads.simBranch() : "")
                    .simBuild(sims ? downloads.simBuild() : "");
        }
        plans.forEach(builder::testPlan);
        picked.simulators().forEach(builder::simulator);
        picked.connections().forEach(builder::connection);
        return builder.build();
    }

    private static String first(String saved, String fallback) {
        return saved != null && !saved.isBlank() ? saved.strip() : fallback;
    }

    /** A saved execution mode the launcher does not know falls back to the default rather than failing the page. */
    private static String execMode(String value) {
        return LaunchConfig.EXECUTION_MODES.contains(value) ? value : LaunchConfig.DEFAULT_EXECUTION_MODE;
    }

    /** {@code paths} with a catalogue whose network source is Xray and Confluence, over the same caches and seed. */
    public static LaunchPaths withNetwork(LaunchPaths paths, Path home) {
        AtlassianClient client = AtlassianClient.forCredentialsFile(AtlassianCredentials.defaultPath(),
                ATLASSIAN_TIMEOUT);
        PlanCatalogue catalogue = new PlanCatalogue(home.resolve(".cat").resolve("java"), home.resolve(".cat"),
                new AtlassianPlanSource(client), new SeedPlanSource(), paths.repoRoot());
        return new LaunchPaths(paths.resultsRoot(), paths.rigsDir(), paths.leaseDir(), paths.repoRoot(),
                paths.petroappsRoot(), catalogue, paths.setupEnv(), paths.pythonRoot());
    }

    /** A launcher with step 5 wired as {@code launch} wires it: the production registry, the plan's cases, the browser. */
    public static RunLauncher wiredLauncher(LaunchPaths paths) {
        RunLauncher launcher = new RunLauncher(paths);
        launcher.wireTests(new StandardRegistry(), PlanCases.forRun(paths), new BrowserConnector());
        return launcher;
    }
}
