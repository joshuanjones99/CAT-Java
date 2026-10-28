package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import com.verifone.cat.collect.Freshness;
import com.verifone.cat.connections.ConnectionProbe;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.connections.Readiness;
import com.verifone.cat.connections.RigReadiness;
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

/**
 * Step 1, System Checks, as the Python CAT's {@code cat.launch.steps.system_checks}: the workstation audit, the rig
 * file, the run lock, petroapps' freshness, the rig's network lines, the plans' requirements, the tests to run, and
 * the simulator staging. Each line is a PASS, FAIL or WARN row in the step's log.
 *
 * <p>Fails the step, as the Python does, when a required audit item is not PASS, the rig will not load, the rig is
 * busy, petroapps cannot be shown current, a required rig network line is not ok, a plan requirement is not met, or
 * a plan has no case. The simulator staging lines are advisory (WARN), as the Python's are.
 *
 * <p>Not ported, so a run that needs them ends this step as unavailable rather than passing them: on a rig run, the
 * Commander's base and the Java 17 set and simulators for it, and the receipt-printer and VATS lines. When the Petro
 * Simulator Suite is in a run that is not {@code --fake-pos}, Petro's pump-state ports 1072 and 9706 must be free for
 * the run ({@link #checkPetroPorts}), or the step fails naming their holder. Not ported on any run: the Commercial Fleet license check
 * (the cases' licence tags), and the on-disk check of the tests (step 5 pulls them; the tests line says so).
 * {@code --fake-pos} skips what the Python skips: the rig, the rig's hardware lines, the run lock and the Commander's
 * base. A requirement that needs a base is a WARN on a fake-POS run, not a pass.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the network lines reach the rig's addresses, which only a rig run does. The tests
 * use a rig whose Commander name does not resolve, and the controller's loopback.
 */
final class SystemChecksStep implements LaunchStep {

    /** How long step 1 waits for the run lock: the Python CAT's {@code RUN_LOCK_WAIT}. */
    static final Duration RUN_LOCK_WAIT = Duration.ofSeconds(3);

    /** The budget for each rig network probe: the Python CAT's {@code check_rig} default. */
    static final Duration READINESS_TIMEOUT = Duration.ofSeconds(3);

    /** The rig device the requirements name for the receipt printer: its own readiness line checks it. */
    private static final String PRINTER = "printer";

    /** The Petro Simulator Suite's registry name, which the pump-state port guard is for. */
    private static final String PETRO_SIMULATOR = "Petro Simulator Suite";

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

        checkPetroapps(paths, step, failures);

        List<String> hardwareFailures = new ArrayList<>();
        if (rig != null && !fakePos && busy.isEmpty()) {
            hardwareFailures.addAll(rigHardwareReady(step, rig));
            missing.addAll(rigOnlyGaps(rig));
            missing.add("the Commander's base (cat.sims.java.commander_base), which the Java 17 set and the "
                    + "plans' Required Base depend on");
        }

        Set<String> used = new LinkedHashSet<>(config.simulators());
        boolean nothingToRun = false;
        if (!config.testPlans().isEmpty()) {
            checkRequirements(ctx, step, rig, failures, used, fakePos);
            nothingToRun = !checkTestsToRun(ctx, step, failures);
        }
        if (!fakePos && used.contains(PETRO_SIMULATOR)) {
            failures.addAll(checkPetroPorts(ctx, step, PetroPorts::bindable, PetroPorts::portHolder));
        }

        checkStaging(paths, rig, step);

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
     * Petroapps must be current with {@code origin/master}: its working tree supplies the TLog rules and the
     * configurations the tests import. Behind is brought current (checkout, then fast-forward), as the Python does.
     * No checkout at all is no line here, as the Python's {@code freshness} returns none.
     */
    private static void checkPetroapps(LaunchPaths paths, StepEmitter step, List<String> failures) {
        try {
            Optional<Freshness> fresh = Freshness.check(paths.petroappsRoot(), PlanCases.REVISION, null, true);
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
     * The rig network lines as checklist rows. Returns the required lines that are not ok, each as {@code label — detail}.
     */
    private static List<String> rigHardwareReady(StepEmitter step, Rig rig) {
        ExecutorService pool = ConnectionProbe.newProbePool();
        List<Readiness> lines;
        try {
            lines = new RigReadiness(new ConnectionProbe(pool)).check(Endpoints.of(rig), READINESS_TIMEOUT);
        } finally {
            pool.shutdownNow();
        }
        List<String> failures = new ArrayList<>();
        for (Readiness line : lines) {
            step.check(line.ok(), line.label(), line.detail(), line.required());
            if (!line.ok() && line.required()) {
                failures.add(line.label() + " — " + line.detail());
            }
        }
        return failures;
    }

    /** The rig-only lines this port does not check yet: the receipt printer's and the VATS pinpad's. */
    private static List<String> rigOnlyGaps(Rig rig) {
        List<String> gaps = new ArrayList<>();
        boolean printer = rig.registers().stream().anyMatch(r -> r.printerPort() != null && !r.printerPort().isEmpty());
        if (printer) {
            gaps.add("the receipt-printer lines of rig hardware readiness (a serial listing)");
        }
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
    private static void checkRequirements(RunContext ctx, StepEmitter step, Rig rig, List<String> failures,
            Set<String> used, boolean fakePos) {
        Snapshot<PlanRequirements> snapshot = ctx.paths().catalogue().requirements(PlanCatalogue.Mode.CACHE_FIRST);
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
            }
            if (row.commanderConnect()) {
                String label = "Commander Connect onboarding";
                String detail = plan + " needs it, and CAT-Java has no Commander Connect switch yet, so it cannot be "
                        + "onboarded from here";
                step.check(false, label, detail, true);
                failures.add(label + " — " + detail);
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
    private static void checkStaging(LaunchPaths paths, Rig rig, StepEmitter step) {
        String host = rig == null ? "" : rig.controller().host();
        SimContext sims = SimContext.of(SetupItems.toolsRoot(paths.setupEnv()), host);
        List<SimEntry> rows = Registry.all();
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
