package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;

import com.verifone.cat.collect.Freshness;
import com.verifone.cat.connections.Readiness;
import com.verifone.cat.connections.RigReadiness;
import com.verifone.cat.plans.BaseRule;
import com.verifone.cat.plans.Requirements;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigBusyException;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;
import com.verifone.cat.setup.SetupItems;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.base.JavaSets;

/**
 * Step 1, System Checks, as the Python CAT's {@code cat.launch.steps.system_checks}: the workstation audit, the rig
 * file, the run lock, petroapps' freshness, the rig's network lines, the plans' requirements, the tests to run, and
 * the simulator staging. Each line is a PASS, FAIL or WARN row in the step's log.
 *
 * <p>Fails the step, as the Python does, when a required audit item is not PASS, the rig will not load, the rig is
 * busy, petroapps cannot be shown current, a required rig network line is not ok, a plan requirement is not met, or
 * a plan has no case. A simulator staging line fails the step only when its check is required, as the Python's.
 *
 * <p>On a rig run: the rig's network lines and one printer line per register with a {@code printer_port} (listed by
 * Windows, and opens; required unless the plans name their devices and not the printer); then the Commander's base
 * ({@link SimulatorBaseChecks#readCommanderBase}, set on {@link RunContext#simsBase}), the chosen Simulator Build
 * ({@link SimulatorBaseChecks#fetchSimulatorBuild}), and the Java 17 set and {@code match_base} simulators the base
 * wants ({@link SimulatorBaseChecks#matchCommanderBase}); then each plan's Required Base against it. When the Petro
 * Simulator Suite is in a run that is not {@code --fake-pos}, Petro's pump-state ports 1072 and 9706 must be free
 * for the run ({@link #checkPetroPorts}), or the step fails naming their holder.
 *
 * <p>When the rig's lines and its printer lines pass, the register's printer port is held from here
 * ({@link PrinterHold#hold}, the Python's {@code _hold_printer_port} from step 1) until step 5's session takes it.
 *
 * <p>The plans' requirements are the ones run start read ({@link RunContext#requirements}, {@link RunRequirements}),
 * checked as the Python's {@code check_requirements} ({@link #checkRequirements}), with the Commercial Fleet license
 * line for a Secondary FEP the cases' tags name. The tests to run are run start's: a plan whose cases have no test on
 * disk or pulled from petroapps ends the run here ("tests to run"), as the Python's.
 *
 * <p>A {@code pinpad: vats} rig's lines (the terminal's VATS port, the SDK and BTT) are among the rig's network
 * lines ({@link VatsSystemChecks}). Deviations: with a required rig hardware line failed the step already fails, so the
 * Commander's base is not read and nothing is fetched (the Python goes on to read and fetch).
 * {@code --fake-pos} skips what the Python skips: the rig, the rig's hardware lines, the run lock, the Commander's
 * base and the plans' requirements (run start reads none for a fake-POS run).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the network lines reach the rig's addresses, which only a rig run does; the base is
 * read over SSH and the simulators fetched from Artifactory ({@link SystemCheckStages#real}). The tests pass fake
 * stages, a rig whose Commander name does not resolve, and the controller's loopback.
 */
final class SystemChecksStep implements LaunchStep {

    /** How long step 1 waits for the run lock: the Python CAT's {@code RUN_LOCK_WAIT}. */
    static final Duration RUN_LOCK_WAIT = Duration.ofSeconds(3);

    /** The rig device the requirements name for the receipt printer: its own readiness line checks it. */
    private static final String PRINTER = "printer";

    /** The Petro Simulator Suite's registry name, which the pump-state port guard is for. */
    private static final String PETRO_SIMULATOR = "Petro Simulator Suite";

    private final SystemCheckStages stages;

    /** The real stages, with a PowerShell process listing. */
    SystemChecksStep() {
        this(SystemCheckStages.real(new com.verifone.cat.sims.PowerShellProcessOps(), System.getenv()));
    }

    /** The stages to use; a test passes fakes. */
    SystemChecksStep(SystemCheckStages stages) {
        this.stages = stages;
    }

    @Override
    public String name() {
        return LaunchStepNames.SYSTEM_CHECKS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        LaunchConfig config = ctx.config();
        LaunchPaths paths = ctx.paths();
        boolean fakePos = config.fakePos();
        List<String> failures = new ArrayList<>();

        auditItems(paths.setupEnv(), step, failures);

        Rig rig = null;
        boolean rigOk = true;
        String rigName = config.rig();
        if (rigName.isEmpty()) {
            if (fakePos) {
                step.skip("rig", "--fake-pos drives the fake POS, no rig needed");
            } else {
                step.check(false, "rig", "no rig selected and --fake-pos is off", true);
                rigOk = false;
            }
        } else {
            try {
                LoadedRig loaded = RigLoader.loadResolved(rigName, paths.rigsDir());
                ctx.rig(loaded);
                rig = loaded.rig();
                step.check(true, "rig file", loaded.source().toString(), true);
            } catch (RuntimeException e) {
                step.check(false, "rig file " + rigName, e.getMessage(), true);
                rigOk = false;
            }
        }

        // One CAT run per rig, before anything below touches it.
        String busy = rigOk ? takeRunLock(ctx, step, rig, fakePos) : "";

        checkPetroapps(paths, config.testPlanBranch(), ctx.petroappsFetchError(), step, failures);

        List<String> hardwareFailures = new ArrayList<>();
        // Read at run start (RunRequirements.apply), as the Python's ctx.requirements; null on a fake-POS run or with
        // no plan.
        Requirements requirements = ctx.requirements();
        Path toolsRoot = SetupItems.toolsRoot(paths.setupEnv());
        if (rig != null && !fakePos && busy.isEmpty()) {
            boolean[] printerOk = {true};
            hardwareFailures.addAll(rigHardwareReady(step, rig,
                    requirements == null || requirements.needsPrinter(), printerOk));
            if (hardwareFailures.isEmpty() && printerOk[0]) {
                // Answer the register's printer from here, not from step 4: it retests a printer that failed with a
                // growing back-off, and after a port left unanswered between launches the first cases' receipts never
                // printed (the Python's runs 38b931774e45, aaa86ab51eb2; HANDOFF §1.106). Step 4's hold is idempotent.
                PrinterHold.hold(ctx, step, stages.printerOpener());
            }
            if (hardwareFailures.isEmpty()) {
                // The Commander's base first: the registry rows the run uses depend on it. Then the chosen Simulator
                // Build, then the simulators and Java 17 set this base wants, so step 3 starts a matching pair.
                String why = SimulatorBaseChecks.readCommanderBase(ctx, step, stages, rig);
                for (SimulatorBaseChecks.Failure failure : concat(
                        SimulatorBaseChecks.fetchSimulatorBuild(ctx, step, stages, toolsRoot, rig),
                        SimulatorBaseChecks.matchCommanderBase(ctx, step, stages, toolsRoot, rig, why))) {
                    failures.add(failure.label() + (failure.detail().isEmpty() ? "" : " — " + failure.detail()));
                }
            }
        }

        // What the ticked plans need of this machine (Supported CAT Test Plans), now the Commander's base is known.
        failures.addAll(checkRequirements(ctx, step, requirements, rig));

        // Simulator staging, after the requirements as the Python's: a secondary FEP's own simulator only when one of
        // this run's Secondary FEPs needs it; each row's well_formed files are SimulatorStaging's lines.
        failures.addAll(checkStaging(ctx, toolsRoot, rig, step));

        // The Python's guard reads config.all_simulators(), which adds the Secondary FEPs'.
        Set<String> used = SimulatorBaseChecks.usedSimulators(ctx);
        if (!fakePos && used.contains(PETRO_SIMULATOR)) {
            failures.addAll(checkPetroPorts(ctx, step, PetroPorts::bindable, PetroPorts::portHolder));
        }

        // A plan may start with no test on disk when petroapps holds one to pull. If run start pulled none after all,
        // the run ends here, rather than step 5 running nothing.
        boolean nothingToRun = !config.testPlans().isEmpty() && config.suites().isEmpty();
        if (nothingToRun) {
            step.check(false, "tests to run", "no case of " + String.join(", ", config.testPlans())
                    + " has a test on disk or pulled from petroapps", true);
        }

        if (!rigOk || !busy.isEmpty() || !hardwareFailures.isEmpty() || !failures.isEmpty() || nothingToRun) {
            return step.fail(precedence(rigName, rigOk, busy, hardwareFailures, failures, config));
        }
        return true;
    }

    /**
     * Required when Petro is in the run: its pump-state ports are this run's to take (the Python's
     * {@code _check_petro_ports} and {@code cat.launch.port_guard}). Run ad24510d66f5 lost 83 fuel cases to a CAT
     * window holding 1072, found an hour and a half in. A port this run already holds is its own. Returns the
     * failures, empty when clear.
     */
    static List<String> checkPetroPorts(RunContext ctx, StepEmitter step, java.util.function.IntPredicate bindable,
            java.util.function.IntFunction<String> holder) {
        List<Integer> ports = PetroPorts.HELD_PORTS.stream().sorted().toList();
        List<PetroPorts.Conflict> conflicts = PetroPorts.conflicts(ports, ctx.petroSinks(), bindable, holder);
        String label = "Petro's pump-state ports free";
        String names = ports.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
        if (conflicts.isEmpty()) {
            step.check(true, label, names + " are this run's to take", true);
            return List.of();
        }
        String detail = conflicts.stream().map(c -> c.port() + " is held by " + c.who())
                .collect(java.util.stream.Collectors.joining("; "))
                + " — the pumps cannot be served, and every fuel case would fail";
        step.check(false, label, detail, true);
        return List.of(label + " — " + detail);
    }

    /**
     * The workstation audit: every item in {@link SetupItems#standard()} detected once, one row each. A required item
     * that is not PASS is a failure; an optional one is a WARN row. Detects only; nothing is repaired here.
     */
    private static void auditItems(SetupEnv env, StepEmitter step, List<String> failures) {
        SetupAudit audit = new SetupAudit(SetupItems.standard(), env);
        Map<String, Result> results = audit.run();
        for (SetupItem item : audit.items()) {
            Result result = results.get(item.name());
            boolean ok = result.ok();
            step.check(ok, item.title(), result.detail(), item.required());
            if (!ok && item.required()) {
                failures.add(item.title() + " — " + result.detail());
            }
        }
    }

    /**
     * Takes the rig's run lock. Returns the holder's line when another run has it, else {@code ""}. Not taken on a
     * fake-POS run or with no rig, as the Python's {@code acquire_run_lock}.
     */
    private static String takeRunLock(RunContext ctx, StepEmitter step, Rig rig, boolean fakePos) {
        if (fakePos || rig == null) {
            return "";
        }
        String name = rig.name();
        RigLease lease = new RigLease(name + ".run", ctx.paths().leaseDir(),
                "cat run " + ctx.runId() + " (pid " + ProcessHandle.current().pid() + ")");
        try {
            lease.acquire(RUN_LOCK_WAIT);
        } catch (RigBusyException e) {
            String busy = "rig '" + name + "' is in use by another CAT run: " + lease.describeHolder();
            step.check(false, "rig free", busy, true);
            return busy;
        } catch (RuntimeException e) {
            String busy = "the run lock could not be taken: " + e.getMessage();
            step.check(false, "rig free", busy, true);
            return busy;
        }
        ctx.runLock(lease);
        step.check(true, "rig free", "no other CAT run holds it", true);
        return "";
    }

    /**
     * Petroapps must be current with {@code origin/<test plan branch>} (the run's {@link LaunchConfig#testPlanBranch},
     * the Python's {@code ctx.config.test_plan_branch}): its working tree supplies the TLog rules and the configurations
     * the tests import. Behind is brought current (checkout, then {@code merge --ff-only}), as the Python does; git
     * carries uncommitted changes across or refuses, and nothing is stashed, reset or forced ({@link Freshness#check}).
     * No checkout at all is no line here, as the Python's {@code freshness} returns none.
     *
     * <p>{@code fetchError} is the outcome of the fetch run start's collection already made
     * ({@link RunContext#petroappsFetchError}, the Python's {@code petroapps_fetch_error}); null when it made none (every
     * case was a hand-migrated one), and this check then fetches itself.
     */
    private static void checkPetroapps(LaunchPaths paths, String branch, String fetchError, StepEmitter step,
            List<String> failures) {
        try {
            Optional<Freshness> fresh = Freshness.check(paths.petroappsRoot(), branch, fetchError, true);
            if (fresh.isPresent()) {
                step.check(fresh.get().ok(), "petroapps current", fresh.get().detail(), true);
                if (!fresh.get().ok()) {
                    failures.add("petroapps current — " + fresh.get().detail());
                }
            }
        } catch (RuntimeException e) {
            step.check(false, "petroapps current", e.getMessage(), true);
            failures.add("petroapps current — " + e.getMessage());
        }
    }

    /**
     * The rig network lines, then the printer lines, as checklist rows. A printer line is required only when the run
     * needs the printer (a plan whose Devices column leaves the printer out does not, 2026-10-21). Returns the required
     * lines that are not ok, each as {@code label — detail}.
     */
    private List<String> rigHardwareReady(StepEmitter step, Rig rig, boolean needsPrinter, boolean[] printerOk) {
        List<Readiness> lines = new ArrayList<>(stages.rigNetwork(rig));
        lines.addAll(RigReadiness.printerLines(rig, stages.serialPorts()));
        List<String> failures = new ArrayList<>();
        for (Readiness line : lines) {
            boolean printer = line.label().startsWith(PRINTER);
            boolean required = line.required() && (needsPrinter || !printer);
            step.check(line.ok(), line.label(), line.detail(), required);
            if (!line.ok() && required) {
                failures.add(line.label() + " — " + line.detail());
            }
            if (!line.ok() && printer) {
                printerOk[0] = false;
            }
        }
        return failures;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** The label of the Commercial Fleet license line: the Python's. */
    static final String LICENSE_LABEL = "Commander Connect for the Commercial Fleet license";

    /**
     * Does this machine meet the plans' requirements? The Python's {@code check_requirements}, after the Commander's base
     * is read ({@link RunContext#simsBase}). One row per requirement; one not met fails the step and stops the run (rig
     * owner, 2026-10-21). Returns the failures, each {@code label — detail}.
     *
     * <p>First, whatever the requirements: a Secondary FEP only the Commercial Fleet license enables (the cases' tags
     * name it now, {@link RunRequirements}) needs Commander Connect onboarding. Then, with requirements read at run start
     * ({@code null} on a fake-POS run or with no plan, and nothing more is checked): the page's own reason, a plan with no
     * row, each Required Base, the plans that need Commander Connect together, each simulator a plan names that CAT does
     * not know, and each device a plan names that the rig file lacks (the printer's own line checks it).
     */
    static List<String> checkRequirements(RunContext ctx, StepEmitter step, Requirements requirements, Rig rig) {
        LaunchConfig config = ctx.config();
        List<String> failures = new ArrayList<>();
        List<String> licensed = config.licenseFeps();
        if (!licensed.isEmpty() && !config.commanderConnect()) {
            fail(step, failures, LICENSE_LABEL, "the cases need " + String.join(", ", licensed)
                    + ": turn on Commander Connect > Onboard on the Config tab");
        }
        if (requirements == null) {
            return failures;
        }
        String note = ctx.requirementsNote();
        if (!note.isEmpty()) {
            step.warn("plan requirements", note);
        }
        for (String key : requirements.unlisted()) {
            step.warn("plan requirements", key + " has no row on Supported CAT Test Plans; none checked");
        }
        for (Map.Entry<String, BaseRule> rule : requirements.baseRules().entrySet()) {
            checkBase(ctx, step, rule.getKey(), rule.getValue(), failures);
        }
        if (!requirements.commanderConnect().isEmpty()) {
            String label = "Commander Connect onboarding";
            String needed = String.join(", ", requirements.commanderConnect());
            if (config.commanderConnect()) {
                step.check(true, label, needed + " need it, and Onboard is on", true);
            } else if (config.ccRequirementWaived()) {
                step.warn(label, needed + " need it; waived for this run, not onboarded");
            } else {
                fail(step, failures, label, needed + " need it: turn on Commander Connect > Onboard");
            }
        }
        for (String simulator : requirements.simulators()) {
            if (Registry.byName(simulator).isEmpty()) {
                fail(step, failures, "simulator " + simulator, "not one CAT knows (sims/Registry)");
            }
        }
        if (!requirements.devices().isEmpty() && rig != null) {
            Map<String, Rig.Device> devices = rig.devices() == null ? Map.of() : rig.devices();
            for (String device : requirements.devices()) {
                if (device.equalsIgnoreCase(PRINTER)) {
                    continue; // the printer's own readiness row checks its port
                }
                boolean present = devices.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(device));
                if (present) {
                    step.check(true, "device " + device, "in the rig file; step 2 probes it", true);
                } else {
                    fail(step, failures, "device " + device, "this PC's rig file has no device of that name");
                }
            }
        }
        return failures;
    }

    private static void fail(StepEmitter step, List<String> failures, String label, String detail) {
        step.check(false, label, detail, true);
        failures.add(label + " — " + detail);
    }

    /**
     * A plan's Required Base against the run's Commander base ({@link RunContext#simsBase}), as the Python's
     * {@code check_requirements}: a run that installs a build whose branch names no base cannot know it yet (WARN), an
     * unread base is a WARN, a base the rule allows is a PASS, and any other is a failure.
     */
    private static void checkBase(RunContext ctx, StepEmitter step, String plan, BaseRule rule, List<String> failures) {
        LaunchConfig config = ctx.config();
        String label = plan + " needs " + rule;
        boolean installs = config.upgradeMode() == UpgradeMode.CLASSIC || config.upgradeMode() == UpgradeMode.NEW_INSTALL;
        Optional<JavaSets.Base> base = JavaSets.baseOf(ctx.simsBase());
        if (installs && JavaSets.baseOf(SimulatorBaseChecks.installingBranch(config)).isEmpty()) {
            step.warn(label, "the run installs a build whose base is not known yet");
        } else if (base.isEmpty()) {
            step.warn(label, "the Commander's base could not be read");
        } else if (rule.allows(base.get().major(), base.get().minor())) {
            step.check(true, label, "the Commander is on " + ctx.simsBase(), true);
        } else {
            fail(step, failures, label, "this run's Commander is on " + ctx.simsBase());
        }
    }

    /**
     * The simulator staging lines, as the Python's {@code verify}: the tools folder, the Java 17 set, and each registry
     * row this run uses ({@link SimulatorStaging#rowsFor}): its folder, placeholders and well-formed files. Returns the
     * required lines that are not ok, each {@code name — detail}; the rest are WARN rows, and step 3 names the
     * simulator that cannot start.
     */
    private static List<String> checkStaging(RunContext ctx, Path toolsRoot, Rig rig, StepEmitter step) {
        String base = ctx.simsBase();
        SimContext sims = SimulatorBaseChecks.simContext(toolsRoot, rig, base);
        List<SimEntry> rows = SimulatorStaging.rowsFor(Registry.forBase(JavaSets.olderBase(base)),
                SimulatorBaseChecks.usedSimulators(ctx));
        List<String> failures = new ArrayList<>();
        for (SimulatorStaging.Check check : SimulatorStaging.verify(sims, rows)) {
            step.check(check.ok(), check.name(), check.detail(), check.required());
            if (!check.ok() && check.required()) {
                failures.add(check.name() + " — " + check.detail());
            }
        }
        return failures;
    }

    /** The message the failed step reports: the first failure in the Python's order. */
    private static String precedence(String rigName, boolean rigOk, String busy, List<String> hardware,
            List<String> failures, LaunchConfig config) {
        if (!rigOk) {
            return "rig " + rigName + " did not load";
        }
        if (!busy.isEmpty()) {
            return busy;
        }
        if (!hardware.isEmpty()) {
            return "rig hardware not ready: " + String.join("; ", hardware);
        }
        if (!failures.isEmpty()) {
            return failures.size() + " required setup item(s) need attention: " + String.join("; ", failures);
        }
        return "nothing to run: no case of the plan has a test";
    }
}
