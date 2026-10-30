package com.verifone.cat.runservice;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.verifone.cat.launch.LastConfig;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchDefaults;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runconfig.ConfigView;

/**
 * Turns the Config tab's choices into the {@link LaunchConfig} Start run launches, or says why they cannot start a
 * run, and reads the choices back for the Overview's Pre-Run card: the Python CAT's {@code CatService._startable_config}
 * over {@code ConfigForm.launch_config} and {@code ConfigForm.describe}.
 *
 * <p>The config is made the way {@code launch} makes it ({@link LaunchDefaults}): the ticked plans and Run scope and
 * the Xray switch from the form; the upgrade mode, dist, Downloads, execution mode, test-plan branch, coverage and
 * Commander Connect from the saved choices ({@link LastConfig}), or the Downloads from the page's own choice when one
 * is given; the simulators and the optional connections from the plans' requirements, else every launchable
 * simulator and every rig device but the pinpad. A partial run (Tags, Cases, From a case) is carried as the scope; the
 * launcher makes the selection.
 *
 * <p>Deviations from the Python's read-back: the Suites line says the cases are pulled at run start (CAT-Java runs
 * petroapps' cases, it has no {@code tests/suites} of its own to count); Secondary FEPs names only the ones the saved
 * config names (the cases' tags are read at run start); and "What this run will start" counts the config's simulators
 * and connections (three fixed plus the optional ones), where the Python counts the rows its state already holds.
 */
public final class RunStart {

    private RunStart() {}

    /** One line of the read-back; a warning line is drawn in the warning colour. */
    public record Line(String text, boolean warning) {}

    /**
     * The config to start, or {@code null} with the refusal in {@link #refusal()}, and the read-back lines.
     *
     * @param needsFetch whether the refusal is only that a ticked plan has no cached case list, which a fetch from
     *                   Jira at Start can fix
     */
    public record Plan(LaunchConfig config, String refusal, List<Line> readBack, boolean needsFetch) {

        public Plan {
            refusal = refusal == null ? "" : refusal;
            readBack = readBack == null ? List.of() : List.copyOf(readBack);
        }

        public Plan(LaunchConfig config, String refusal) {
            this(config, refusal, List.of(), false);
        }

        public boolean startable() {
            return config != null && refusal.isEmpty();
        }
    }

    /**
     * Where the defaults come from.
     *
     * @param rig       the rig the web UI was started with; empty refuses the start
     * @param saved     the saved choices, read fresh at each call
     * @param env       the environment (the Commander Connect password variable)
     * @param catalogue the plans' requirements, cache only; null for none (every default simulator and connection)
     * @param rigsDir   where the rig file is read from for the default connections; null reads none
     * @param downloads the page's Downloads choice, or null to take the saved one
     */
    public record Context(String rig, Supplier<LastConfig> saved, Map<String, String> env, PlanCatalogue catalogue,
            Path rigsDir, Supplier<LaunchDefaults.Downloads> downloads) {

        public Context {
            rig = rig == null ? "" : rig.strip();
            saved = saved == null ? LastConfig::none : saved;
            env = env == null ? Map.of() : env;
            downloads = downloads == null ? () -> null : downloads;
        }

        /** This context with the page's Downloads choice in place of the saved one. */
        public Context withDownloads(Supplier<LaunchDefaults.Downloads> choice) {
            return new Context(rig, saved, env, catalogue, rigsDir, choice);
        }

        /** The rig alone: no saved choices, no requirements, no rig file. */
        public static Context of(String rig) {
            return new Context(rig, LastConfig::none, Map.of(), null, null, null);
        }
    }

    /** {@link #from(ConfigForm, Context)} with the rig alone. */
    public static Plan from(ConfigForm form, String rig) {
        return from(form, Context.of(rig));
    }

    public static Plan from(ConfigForm form, Context ctx) {
        ConfigView view = form.view();
        if (ctx.rig().isEmpty()) {
            return refused("No rig is named, so a run has nowhere to go. Start CAT-Java with --rig.", false);
        }
        List<String> missing = form.missingRequirements();
        if (!missing.isEmpty()) {
            boolean fetch = !form.plans().isEmpty() && missing.size() == 1
                    && missing.get(0).startsWith("a test plan with at least one migrated case");
            return refused("Nothing to run yet: " + String.join("; ", missing) + ".", fetch);
        }
        if (form.plans().isEmpty()) {
            return refused("Nothing to run yet: tick a test plan on the Config tab.", false);
        }
        Rig rig = loadRig(ctx);
        String rigProblem = rig == null && ctx.rigsDir() != null ? rigProblem(ctx) : "";
        List<PlanRequirements> rows = ctx.catalogue() == null ? List.of()
                : ctx.catalogue().requirements(PlanCatalogue.Mode.CACHE_ONLY).rows();
        LaunchDefaults.Picked picked = LaunchDefaults.pick(form.plans(), rows, rig);
        LaunchConfig config;
        try {
            config = LaunchDefaults.fromSaved(ctx.rig(), form.plans(), form.scope(), form.xray(), ctx.saved().get(),
                    ctx.env(), picked, ctx.downloads().get());
        } catch (IllegalArgumentException e) {
            return refused("The saved run choices will not make a run: " + e.getMessage(), false);
        }
        return new Plan(config, "", readBack(config, view, picked, rigProblem), false);
    }

    /**
     * Every choice on the form but no plan: the base an Authoring replay runs on, the Python's
     * {@code ConfigForm.replay_config} ({@code _launch_config(suites, ())}). The saved choices, the page's Downloads,
     * the Xray switch, {@code provision_register} on, and the default simulators and connections (no plan names any).
     * It needs no ticked plan, so it is not refused for want of one.
     *
     * @throws IllegalArgumentException when no rig is named
     */
    public static LaunchConfig replayBase(ConfigForm form, Context ctx) {
        if (ctx.rig().isEmpty()) {
            throw new IllegalArgumentException("No rig is named, so a run has nowhere to go. Start CAT-Java with --rig.");
        }
        LaunchDefaults.Picked picked = LaunchDefaults.pick(List.of(), List.of(), loadRig(ctx));
        return LaunchDefaults.fromSaved(ctx.rig(), List.of(), null, form.xray(), ctx.saved().get(), ctx.env(), picked,
                ctx.downloads().get());
    }

    private static Plan refused(String why, boolean fetch) {
        return new Plan(null, why, List.of(new Line(why, true), new Line("Open the Config tab to set it.", false)),
                fetch);
    }

    private static Rig loadRig(Context ctx) {
        if (ctx.rigsDir() == null) {
            return null;
        }
        try {
            return RigLoader.load(ctx.rig(), ctx.rigsDir());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String rigProblem(Context ctx) {
        try {
            RigLoader.load(ctx.rig(), ctx.rigsDir());
            return "";
        } catch (RuntimeException e) {
            return "rig '" + ctx.rig() + "' will not load: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** The Pre-Run card's lines ({@code FormLaunchConfigMixin.describe}), then what the run will start. */
    static List<Line> readBack(LaunchConfig config, ConfigView view, LaunchDefaults.Picked picked, String rigProblem) {
        List<Line> lines = new ArrayList<>();
        lines.add(plain("Test plan(s):  " + String.join(", ", config.testPlans())));
        lines.add(plain("Suites:        " + String.join(", ", config.testPlans()) + " - pulled from petroapps ("
                + config.testPlanBranch() + ") at run start"));
        lines.add(plain("Cases:         " + (view.caseLines().isEmpty() ? "-" : String.join("; ", view.caseLines()))));
        if (config.partial()) {
            lines.add(new Line("Run scope:     " + view.scopeSummary(), true));
        }
        lines.add(plain("Secondary FEPs: " + (config.secondaryFeps().isEmpty()
                ? "none named (the cases' tags are read at run start)"
                : String.join(", ", config.secondaryFeps()))));
        lines.add(plain("Upgrade:       " + config.upgradeMode().wire()));
        lines.add(plain("Commander:     " + commanderLine(config)));
        lines.add(plain("Sim build:     " + simulatorLine(config)));
        lines.add(plain("Connections:   " + connectionsLine(picked, rigProblem)));
        lines.add(plain("Xray:          " + (config.xrayReport() ? "a test execution per plan, updated live" : "off")));
        lines.add(plain("Coverage:      " + (config.coverage()
                ? "JaCoCo, attached to Jira (reboots the Commander in step 6)" : "off")));
        lines.add(plain("Simulators:    " + simulatorsLine(picked)));
        lines.add(plain("Requirements:  " + (view.needs().isEmpty() ? "-" : view.needs())));
        String email = config.commanderConnectSettings().email();
        lines.add(plain("Cmdr Connect:  " + (config.commanderConnect()
                ? "onboard to " + config.commanderConnectSettings().lab() + " as "
                        + (email == null || email.isEmpty() ? "(no email)" : email)
                : "off")));
        lines.add(plain("What this run will start: Simulators: " + picked.simulators().size() + " / Connections: "
                + (3 + picked.connections().size())));
        return lines;
    }

    private static Line plain(String text) {
        return new Line(text, false);
    }

    /** What the Commander half of the choice will do ({@code cat.downloads.commander_line}). */
    static String commanderLine(LaunchConfig config) {
        UpgradeMode mode = config.upgradeMode();
        boolean chosen = !config.commanderBranch().isEmpty() && !config.commanderBuild().isEmpty();
        if (mode == UpgradeMode.SKIP) {
            return chosen ? "no upgrade (Upgrade mode is Skip), so " + config.commanderBranch() + " "
                    + config.commanderBuild() + " is not downloaded" : "no upgrade (Upgrade mode is Skip)";
        }
        if (mode == UpgradeMode.VHQ) {
            return "VHQ, which is not implemented: `petrotest upgrade` reports it failed; nothing is downloaded";
        }
        String how = mode == UpgradeMode.CLASSIC ? "AU (Auto_Upgrade.jar push)" : "PSI (new install)";
        String tail = mode == UpgradeMode.NEW_INSTALL ? ", then run psi-init --no-pairing (the password reset)" : "";
        String dist = config.dist();
        if (chosen) {
            return "download " + config.commanderBranch() + " " + config.commanderBuild() + " for " + dist
                    + ", install it with " + how + tail;
        }
        if (!config.localBuild().isEmpty()) {
            return "download nothing; install " + config.localBuild() + " (" + dist + "), already on this PC, with "
                    + how + tail;
        }
        return "download nothing; install the newest " + dist + " build already under the builds root with " + how
                + tail;
    }

    /** What the Simulator half of the choice will do ({@code cat.downloads.simulator_line}). */
    static String simulatorLine(LaunchConfig config) {
        if (config.simBranch().isEmpty()) {
            return "keep the staged ones";
        }
        if ("Solutions".equals(config.simBranch().replace("/", ""))) {
            return "cannot use " + config.simBranch() + " " + config.simBuild()
                    + " has no simulators/ (Solutions builds carry none); pick another build";
        }
        return "replace the staged ones with " + config.simBranch() + " " + config.simBuild()
                + "'s, config files and all (a running simulator is refused)";
    }

    private static String connectionsLine(LaunchDefaults.Picked picked, String rigProblem) {
        if (!rigProblem.isEmpty()) {
            return "none - " + rigProblem + "; step 1 will fail the run";
        }
        String fixed = "3 fixed";
        if (picked.devicesFromPlan()) {
            return fixed + " + the plan's: " + String.join(", ", picked.connections());
        }
        return picked.connections().isEmpty() ? fixed + " only"
                : fixed + " + " + String.join(", ", picked.connections());
    }

    private static String simulatorsLine(LaunchDefaults.Picked picked) {
        if (picked.simulators().isEmpty()) {
            return "none - step 3 will be skipped";
        }
        return (picked.simulatorsFromPlan() ? "the plan's: " : "all: ") + String.join(", ", picked.simulators());
    }
}
