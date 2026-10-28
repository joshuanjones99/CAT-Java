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
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanRequirements;
import com.verifone.cat.plans.Snapshot;
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
 * a plan has no case. The simulator staging lines are advisory (WARN), as the Python's are.
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
 * <p>Not ported: the VATS lines for a {@code pinpad: vats} rig (that rig ends this step as unavailable), the
 * Commercial Fleet license check (the cases' licence tags), and the on-disk check of the tests (step 5 pulls them; the
 * tests line says so). Deviations: with a required rig hardware line failed the step already fails, so the
 * Commander's base is not read and nothing is fetched (the Python goes on to read and fetch). A rig run that asks for
 * what step 4 cannot do in CAT-Java ({@link CommanderConfigurationStep#unported}: JaCoCo coverage, the cloud tunnel
 * of a Commander Connect onboarding) fails here, one FAIL line each, so it stops before step 3 starts simulators and
 * step 4 installs a build; the Python has no such line (it can do both).
 * {@code --fake-pos} skips what the Python skips: the rig, the rig's hardware lines, the run lock and the Commander's
 * base. A requirement that needs a base is a WARN on a fake-POS run, not a pass.
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
        List<String> missing = new ArrayList<>();

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

        checkPetroapps(paths, config.testPlanBranch(), step, failures);

        // What step 4 cannot do in CAT-Java (coverage, the cloud tunnel), here so a mistyped launch stops before step 3
        // starts simulators and step 4 installs a build.
        for (CommanderConfigurationStep.Gap gap : CommanderConfigurationStep.unported(config,
                stages.cloudTunnelPorted())) {
            step.check(false, gap.row(), gap.detail(), true);
            failures.add(gap.row() + " — " + gap.detail());
        }

        List<String> hardwareFailures = new ArrayList<>();
        Snapshot<PlanRequirements> requirements = config.testPlans().isEmpty() ? null
                : ctx.paths().catalogue().requirements(PlanCatalogue.Mode.CACHE_FIRST);
        Path toolsRoot = SetupItems.toolsRoot(paths.setupEnv());
        if (rig != null && !fakePos && busy.isEmpty()) {
            boolean[] printerOk = {true};
            hardwareFailures.addAll(rigHardwareReady(step, rig, needsPrinter(config, requirements), printerOk));
            missing.addAll(rigOnlyGaps(rig));
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

        Set<String> used = new LinkedHashSet<>(config.simulators());
        boolean nothingToRun = false;
        if (!config.testPlans().isEmpty()) {
            checkRequirements(ctx, step, requirements, rig, failures, used, fakePos);
            nothingToRun = !checkTestsToRun(ctx, step, failures);
        }
        if (!fakePos && used.contains(PETRO_SIMULATOR)) {
            failures.addAll(checkPetroPorts(ctx, step, PetroPorts::bindable, PetroPorts::portHolder));
        }

        checkStaging(toolsRoot, rig, ctx.simsBase(), step);

        if (!rigOk || !busy.isEmpty() || !hardwareFailures.isEmpty() || !failures.isEmpty() || nothingToRun) {
            return step.fail(precedence(rigName, rigOk, busy, hardwareFailures, failures, config));
        }
        if (!missing.isEmpty()) {
            return step.unavailable("src/cat/launch/steps/system_checks.py", String.join("; ", missing));
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
     * <p>Deviation: the Python passes the outcome of the fetch its run-start collection already made
     * ({@code petroapps_fetch_error}); CAT-Java collects in step 5, so this check fetches itself.
     */
    private static void checkPetroapps(LaunchPaths paths, String branch, StepEmitter step, List<String> failures) {
        try {
            Optional<Freshness> fresh = Freshness.check(paths.petroappsRoot(), branch, null, true);
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

    /**
     * Whether the run needs the receipt printer: unless the plans name their devices and not it (the Python's
     * {@code Requirements.needs_printer}). With no plan, or no requirements read, it does.
     */
    static boolean needsPrinter(LaunchConfig config, Snapshot<PlanRequirements> requirements) {
        if (requirements == null || requirements.rows().isEmpty()) {
            return true;
        }
        List<String> named = new ArrayList<>();
        for (PlanRequirements row : requirements.rows()) {
            if (config.testPlans().contains(row.key())) {
                named.addAll(row.devices());
            }
        }
        return named.isEmpty() || named.stream().anyMatch(PRINTER::equalsIgnoreCase);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** The rig-only lines this port does not check yet: the VATS pinpad's. */
    private static List<String> rigOnlyGaps(Rig rig) {
        List<String> gaps = new ArrayList<>();
        if (rig.pinpad() == Rig.Pinpad.VATS) {
            gaps.add("the VATS lines for pinpad: vats (the terminal, the SDK and BTT)");
        }
        return gaps;
    }

    /**
     * The plans' requirements, one row per requirement, as the Python's {@code check_requirements}: a requirement not
     * met is a failure, and a plan with no row is a WARN. The simulators a plan names join {@code used}, for the port
     * guard.
     */
    private static void checkRequirements(RunContext ctx, StepEmitter step, Snapshot<PlanRequirements> snapshot,
            Rig rig, List<String> failures, Set<String> used, boolean fakePos) {
        if (snapshot.rows().isEmpty()) {
            String reason = snapshot.reason();
            step.warn("plan requirements", reason == null || reason.isEmpty()
                    ? "no requirements were read from Supported CAT Test Plans" : reason);
            return;
        }
        for (String plan : ctx.config().testPlans()) {
            PlanRequirements row = snapshot.rows().stream().filter(r -> r.key().equals(plan)).findFirst().orElse(null);
            if (row == null) {
                step.warn("plan requirements", plan + " has no row on Supported CAT Test Plans; none checked");
                continue;
            }
            if (fakePos && row.baseRule().isPresent()) {
                step.warn(row.key() + " needs " + row.base(), "--fake-pos reads no Commander base, so it is not checked");
            } else if (row.baseRule().isPresent()) {
                checkBase(ctx, step, row.key(), row.baseRule().get(), failures);
            }
            if (row.commanderConnect()) {
                // The Python's cat.launch.requirements, per plan (the Python joins every plan that needs it).
                String label = "Commander Connect onboarding";
                if (ctx.config().commanderConnect()) {
                    step.check(true, label, plan + " need it, and Onboard is on", true);
                } else if (ctx.config().ccRequirementWaived()) {
                    step.warn(label, plan + " need it; waived for this run, not onboarded");
                } else {
                    String detail = plan + " need it: turn on Commander Connect > Onboard";
                    step.check(false, label, detail, true);
                    failures.add(label + " — " + detail);
                }
            }
            used.addAll(row.simulators());
            for (String simulator : row.simulators()) {
                if (Registry.byName(simulator).isEmpty()) {
                    String detail = "not one CAT knows (sims/Registry)";
                    step.check(false, "simulator " + simulator, detail, true);
                    failures.add("simulator " + simulator + " — " + detail);
                }
            }
            checkDevices(step, row, rig, failures);
        }
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
            String detail = "this run's Commander is on " + ctx.simsBase();
            step.check(false, label, detail, true);
            failures.add(label + " — " + detail);
        }
    }

    /** The plan's devices: each named device must be in the rig file. The printer's own line checks it. */
    private static void checkDevices(StepEmitter step, PlanRequirements row, Rig rig, List<String> failures) {
        if (rig == null) {
            return;
        }
        Map<String, Rig.Device> devices = rig.devices() == null ? Map.of() : rig.devices();
        for (String device : row.devices()) {
            if (device.equalsIgnoreCase(PRINTER)) {
                continue;
            }
            boolean present = devices.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(device));
            if (present) {
                step.check(true, "device " + device, "in the rig file; step 2 probes it", true);
            } else {
                String detail = "this PC's rig file has no device of that name";
                step.check(false, "device " + device, detail, true);
                failures.add("device " + device + " — " + detail);
            }
        }
    }

    /**
     * The plans have tests to run: each plan lists at least one case in the catalogue. The Python also checks the
     * tests are on disk or pulled; here they are pulled in step 5, so this line says what it checked and no more.
     * Returns false when the plans have no case at all.
     */
    private static boolean checkTestsToRun(RunContext ctx, StepEmitter step, List<String> failures) {
        if (!ctx.config().suites().isEmpty()) {
            return true;
        }
        int cases = 0;
        try {
            for (String plan : ctx.config().testPlans()) {
                List<PlanCase> rows = ctx.paths().catalogue().cases(plan, PlanCatalogue.Mode.CACHE_FIRST).rows();
                cases += rows.size();
            }
        } catch (RuntimeException e) {
            step.check(false, "tests to run", e.getMessage(), true);
            failures.add("tests to run — " + e.getMessage());
            return true;
        }
        String plans = String.join(", ", ctx.config().testPlans());
        if (cases == 0) {
            step.check(false, "tests to run", "no case of " + plans + " is in the catalogue", true);
            return false;
        }
        step.check(true, "tests to run", cases + " case(s) of " + plans
                + " listed; step 5 pulls their tests from petroapps (the on-disk check is not ported)", true);
        return true;
    }

    /**
     * The simulator staging lines, advisory, as the Python's {@code verify}: the tools folder, the Java 17 set, and each
     * registry row's folder and placeholders. Never ends the step; step 3 names the simulator that cannot start.
     */
    private static void checkStaging(Path toolsRoot, Rig rig, String base, StepEmitter step) {
        SimContext sims = SimulatorBaseChecks.simContext(toolsRoot, rig, base);
        List<SimEntry> rows = Registry.forBase(JavaSets.olderBase(base));
        for (SimulatorStaging.Check check : SimulatorStaging.verify(sims, rows)) {
            step.check(check.ok(), check.name(), check.detail(), false);
        }
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
            return failures.size() + " required check(s) failed: " + String.join("; ", failures);
        }
        return "nothing to run: no case of " + String.join(", ", config.testPlans()) + " has a test";
    }
}
