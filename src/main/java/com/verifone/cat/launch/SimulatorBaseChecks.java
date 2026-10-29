package com.verifone.cat.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.ArtifactoryErrors;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.Runner;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimFetch;
import com.verifone.cat.sims.SimSources;
import com.verifone.cat.sims.Supervisor;
import com.verifone.cat.sims.base.CommanderBase;
import com.verifone.cat.sims.base.JavaSets;
import com.verifone.cat.sims.base.SuiteBase;

/**
 * Step 1's simulator half on a rig run: the Commander's base ({@link #readCommanderBase}), the chosen Simulator
 * Build ({@link #fetchSimulatorBuild}), and the Java 17 set and simulators the base wants
 * ({@link #matchCommanderBase}). The Python CAT's {@code system_checks._read_commander_base},
 * {@code _fetch_simulator_build}, {@code _match_commander_base}, {@code cat.launch.upgrade.replace_simulator_build}
 * and {@code simulators.installing_branch}.
 *
 * <p>FAIL and WARN, as the Python's docstrings set them:
 * <ul>
 *   <li>a row this run starts that the chosen Simulator Build did not replace is a FAIL, as is no login or Artifactory
 *       unreachable (nothing replaced); a {@code staged_only} row (the Terminal Connector) not replaced is a WARN;</li>
 *   <li>an unreadable base is a WARN, and nothing is fetched for it;</li>
 *   <li>a {@code match_base} row built for another base, or not staged in the base's own form, is replaced by the
 *       newest build of the matching branch, after its running copy is stopped under both registry forms (only what
 *       the supervisor would adopt: a listener on its port or its match token, never by image name); one that cannot
 *       be replaced, or a Java 17 set that cannot be staged, is a FAIL;</li>
 *   <li>a mismatch on a copy the operator chose as the Simulator Build is a WARN naming both bases, and stays.</li>
 * </ul>
 *
 * <p>Deviations: the client is made only when something must be downloaded, so a controller with no Artifactory login
 * passes when both Java sets and the simulators are already right (the Python fails it); a chosen build already
 * installed (its {@link SuiteBase#MARKER} names that branch and build) is not downloaded again ({@link SimFetch}).
 * The rows a run uses are the Python's {@code all_simulators} ({@link #usedSimulators}): the picker's, plus each chosen
 * Secondary FEP's host simulator. The two registry forms of a stale row are stopped in two passes, since the
 * supervisor keys its answer by row name.
 *
 * <p>UNVERIFIED AGAINST HARDWARE AND A REAL ARTIFACTORY SERVER (Java port).
 */
final class SimulatorBaseChecks {

    /** A (label, detail) failure that ends step 1. */
    record Failure(String label, String detail) {
    }

    private SimulatorBaseChecks() {
    }

    /** The Commander Build branch this run installs in step 4, or "" when it installs none. */
    static String installingBranch(LaunchConfig config) {
        if (config.commanderBuildChosen() && config.upgradeMode() != UpgradeMode.SKIP) {
            return config.commanderBranch();
        }
        return "";
    }

    /**
     * The simulators this run uses: the Python's {@code LaunchConfig.all_simulators}, the picker's plus each chosen
     * Secondary FEP's host simulator ({@link com.verifone.cat.sims.FepHosts}); never a FEP's on a fake-POS run.
     */
    static Set<String> usedSimulators(RunContext ctx) {
        return new LinkedHashSet<>(com.verifone.cat.sims.FepHosts.allSimulators(ctx.config().simulators(),
                ctx.commanderOptions().secondaryFeps(), ctx.config().fakePos()));
    }

    /** The context the run's simulators resolve against: the tools root, the controller, and the base's Java set. */
    static SimContext simContext(Path toolsRoot, Rig rig, String base) {
        return SimContext.forRig(toolsRoot, rig).withJavaDir(JavaSets.javaForRun(base).target());
    }

    /**
     * Sets {@link RunContext#simsBase} (the build this run installs, else the Commander's {@code Base_Level}) and
     * returns where it came from. Nothing is read on a fake-POS run or with no rig.
     */
    static String readCommanderBase(RunContext ctx, StepEmitter step, SystemCheckStages stages, Rig rig) {
        if (ctx.config().fakePos() || rig == null) {
            return "";
        }
        CommanderBase.Base base = CommanderBase.of(installingBranch(ctx.config()), () -> stages.suiteinfo(rig));
        ctx.simsBase(base.base());
        if (base.known()) {
            step.check(true, "Commander base", base.base() + ": " + base.why(), true);
        } else {
            step.warn("Commander base", base.why() + "; base 57's simulators");
        }
        return base.why();
    }

    /**
     * Replaces the staged simulators with the chosen Simulator Build, if one is chosen: the rows this run starts plus
     * every {@code staged_only} row. Returns the failures that end the run.
     */
    static List<Failure> fetchSimulatorBuild(RunContext ctx, StepEmitter step, SystemCheckStages stages,
            Path toolsRoot, Rig rig) {
        LaunchConfig config = ctx.config();
        if (config.simBranch().isEmpty() || config.simBuild().isEmpty()) {
            return List.of();
        }
        String label = "Simulator Build " + config.simBranch() + " " + config.simBuild();
        if (config.fakePos()) {
            step.skip(label, "--fake-pos starts no simulator");
            return List.of();
        }
        Set<String> used = usedSimulators(ctx);
        String base = ctx.simsBase();
        boolean older = JavaSets.olderBase(base);
        List<SimEntry> rows = SimFetch.buildRows(Registry.forBase(older), used);
        SimContext sims = simContext(toolsRoot, rig, base);
        step.log(label + ": replacing " + (rows.isEmpty() ? "nothing"
                : String.join(", ", rows.stream().map(SimEntry::name).toList())) + " — config files and all", "INFO");
        List<SimFetch.FetchResult> results;
        try {
            results = new SimFetch(stages::artifactory, logTo(step)).fetchBuild(rows, sims, config.simBranch(),
                    config.simBuild(), older, stages::running);
        } catch (RuntimeException e) { // no login, or Artifactory unreachable: nothing replaced
            String error = problemText(e) + "; the staged simulators are unchanged";
            step.check(false, label, error, true);
            return List.of(new Failure(label, error));
        }
        List<Failure> failures = new ArrayList<>();
        for (SimFetch.FetchResult result : results) {
            boolean required = used.contains(result.name());
            step.check(result.ok(), "replace " + result.name(), result.detail(), required);
            if (!result.ok() && required) {
                failures.add(new Failure("replace " + result.name(), result.detail()));
            }
        }
        return failures;
    }

    /**
     * Stages the Java 17 set and the {@code match_base} simulators for the Commander's base. Returns the failures: a
     * set or simulator this run needs that could not be staged. An unreadable base is a WARN, and nothing is fetched.
     */
    static List<Failure> matchCommanderBase(RunContext ctx, StepEmitter step, SystemCheckStages stages, Path toolsRoot,
            Rig rig, String why) {
        LaunchConfig config = ctx.config();
        if (config.fakePos() || rig == null) {
            return List.of();
        }
        String base = ctx.simsBase();
        boolean older = JavaSets.olderBase(base);
        Set<String> used = usedSimulators(ctx);
        List<SimEntry> javaRows = Registry.forBase(older).stream()
                .filter(e -> used.contains(e.name()) && e.runner() == Runner.JAVAW).toList();
        if (javaRows.isEmpty()) {
            return List.of();
        }
        if (base.isEmpty()) {
            step.warn("simulators for the Commander's base", why);
            return List.of();
        }
        SimContext sims = simContext(toolsRoot, rig, base);
        JavaSets.JavaSet java = JavaSets.javaForRun(base);
        boolean chosen = !config.simBranch().isEmpty() && !config.simBuild().isEmpty();

        List<SimEntry> stale = new ArrayList<>();
        for (SimEntry entry : javaRows) {
            if (!SimSources.matchBase(entry.name())) {
                continue;
            }
            Path cwd = sims.cwdFor(entry);
            Optional<Path> jar = SimSources.jarFor(entry, sims);
            SuiteBase.Installed found = SuiteBase.installedBase(cwd, jar.orElse(null));
            String wrong;
            if (!Files.isDirectory(cwd) || (jar.isPresent() && !Files.isRegularFile(jar.get()))) {
                String missing = jar.isPresent() && Files.isDirectory(cwd) ? jar.get().getFileName().toString()
                        : cwd.getFileName().toString();
                wrong = "its build for " + base + " (" + missing + ") is not staged";
            } else {
                wrong = SuiteBase.mismatch(found, base);
            }
            String label = entry.name() + " base";
            if (wrong.isEmpty()) {
                step.check(true, label, found.how() + "; " + why, true);
            } else if (chosen) {
                step.warn(label, wrong + " (" + found.how() + "); the Simulator Build " + config.simBranch() + " "
                        + config.simBuild() + " was chosen, so it stays");
            } else {
                step.log("FETCH " + entry.name() + " — " + wrong + " (" + found.how() + ")", "INFO");
                step.substep(label, SubstepOutcome.WARNED, wrong + "; fetching " + base + "'s", 0.0);
                stale.add(entry);
            }
        }

        if (!stale.isEmpty()) {
            // The running copy may be either form of the row: stop it under both.
            Set<String> names = new LinkedHashSet<>(stale.stream().map(SimEntry::name).toList());
            List<SimEntry> otherForms = Registry.forBase(!older).stream()
                    .filter(e -> names.contains(e.name())).toList();
            for (List<SimEntry> forms : List.of(stale, otherForms)) {
                Map<String, Supervisor.StopRow> stopped = new TreeMap<>(stages.stopRunning(ctx, forms, sims));
                for (Map.Entry<String, Supervisor.StopRow> row : stopped.entrySet()) {
                    if (row.getValue() != null && row.getValue().outcome() != Supervisor.StopOutcome.ABSENT) {
                        step.log("STOP  " + row.getKey() + " — " + row.getValue().outcome().name().toLowerCase(
                                Locale.ROOT) + " " + row.getValue().detail() + " (the wrong build for "
                                + base + ")", "INFO");
                    }
                }
            }
        }

        Supplier<ArtifactoryClient> clients = memo(stages::artifactory);
        Consumer<String> log = logTo(step);
        List<Failure> failures = new ArrayList<>();
        String javaProblem = JavaSets.ensureJava(java, toolsRoot, clients, log);
        String javaLabel = "Java 17 " + java.version() + " (" + java.target() + ")";
        step.check(javaProblem.isEmpty(), javaLabel, javaProblem.isEmpty() ? "staged; " + why : javaProblem, true);
        if (!javaProblem.isEmpty()) {
            failures.add(new Failure(javaLabel, javaProblem));
        }
        if (stale.isEmpty()) {
            return failures;
        }
        List<SimFetch.FetchResult> results;
        try {
            SuiteBase.BranchBuild match = SuiteBase.matchingBuild(clients.get(), base);
            log.accept("simulators for " + base + ": " + match.branch() + " build " + match.build());
            results = new SimFetch(clients, log).replaceStale(stale, sims, match.branch(), match.build(), older,
                    stages::running);
        } catch (RuntimeException e) { // no login, unreachable, or no build for this base
            String label = "Java 17 " + java.version() + " and simulators for " + base;
            String detail = e instanceof com.verifone.cat.sims.base.NightlyBuilds.NotFound ? e.getMessage()
                    : problemText(e);
            step.check(false, label, detail, true);
            failures.add(new Failure(label, detail));
            return failures;
        }
        for (SimFetch.FetchResult result : results) {
            String label = result.name() + " for " + base;
            step.check(result.ok(), label, result.detail(), true);
            if (!result.ok()) {
                failures.add(new Failure(label, result.detail()));
            }
        }
        return failures;
    }

    /** A failure from Artifactory in the operator's words: the Python's {@code cat.downloads.problem_text}. */
    static String problemText(Throwable e) {
        if (e instanceof com.verifone.cat.build.ArtifactoryException
                || e instanceof com.verifone.cat.build.ArtifactoryAuthError) {
            return ArtifactoryErrors.describe(e, "");
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static Consumer<String> logTo(StepEmitter step) {
        return line -> step.log(line, "INFO");
    }

    /** {@code supplier}, asked at most once. */
    private static <T> Supplier<T> memo(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;

            @Override
            public synchronized T get() {
                if (value == null) {
                    value = supplier.get();
                }
                return value;
            }
        };
    }
}
