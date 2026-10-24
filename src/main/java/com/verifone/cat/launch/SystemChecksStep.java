package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import com.verifone.cat.connections.ConnectionProbe;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.connections.Readiness;
import com.verifone.cat.connections.RigReadiness;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigBusyException;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 1, System Checks: the rig file, the run lock that keeps one run per rig, and the rig's network
 * lines. The Python CAT's {@code cat.launch.steps.system_checks}.
 *
 * <p>Checked here, in order:
 * <ul>
 *   <li>the rig file loads ({@code RigLoader}); a file that will not load fails the step;</li>
 *   <li>the run lock ({@code cat.launch.run_lock}, {@code RigLease} on {@code <rig>.run.lock}) is free;
 *       a run that finds it held stops here and names the holder. It does not wait for it;</li>
 *   <li>the rig network lines ({@code connections.RigReadiness}): the controller's rig adapter holds
 *       {@code controller.host}, and the Commander answers on CGILink. A required line that fails
 *       stops the run, with the fix named. A fake-POS run has no rig hardware in play, so it skips
 *       these lines, as the Python CAT does.</li>
 * </ul>
 * Unavailable, so the step ends failed and no run gets past step 1 on a partial check: the workstation
 * audit ({@code cat.setup.SetupAudit}), petroapps' freshness ({@code cat.collect}), the simulator
 * staging check ({@code cat.sims.config.verify}), and, on a rig run, the receipt-printer lines (a
 * serial listing) and the VATS lines for {@code pinpad: vats} when the rig has them. A fake-POS run
 * takes no run lock, as the Python one does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the network lines reach the rig's addresses, which only a rig run
 * does. The tests use a rig whose Commander name does not resolve, and the controller's loopback.
 */
final class SystemChecksStep implements LaunchStep {

    /** How long step 1 waits for the run lock: the Python CAT's {@code RUN_LOCK_WAIT}. */
    static final Duration RUN_LOCK_WAIT = Duration.ofSeconds(3);

    /** The budget for each rig network probe: the Python CAT's {@code check_rig} default. */
    static final Duration READINESS_TIMEOUT = Duration.ofSeconds(3);

    @Override
    public String name() {
        return LaunchStepNames.SYSTEM_CHECKS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        String rigName = ctx.config().rig();
        boolean fakePos = ctx.config().fakePos();
        Rig rig = null;
        if (rigName.isEmpty()) {
            step.check(true, "rig file", "no rig named (a fake-POS run)", true);
        } else {
            LoadedRig loaded;
            try {
                loaded = RigLoader.loadResolved(rigName, ctx.paths().rigsDir());
            } catch (RuntimeException e) {
                step.check(false, "rig file " + rigName, e.getMessage(), true);
                return step.fail("the rig file did not load");
            }
            ctx.rig(loaded);
            rig = loaded.rig();
            step.check(true, "rig file", loaded.source().toString(), true);
            if (!fakePos && !takeRunLock(ctx, step, rig.name())) {
                return false;
            }
            if (fakePos) {
                step.skip("rig hardware", "--fake-pos drives the fake POS, no rig hardware in play");
            } else if (!rigHardwareReady(step, rig)) {
                return false;
            }
        }
        return step.unavailable("src/cat/launch/steps/system_checks.py", missing(rig, fakePos));
    }

    /** The parts of step 1 this port does not have yet, named for the run that stops at them. */
    static String missing(Rig rig, boolean fakePos) {
        StringBuilder out = new StringBuilder("the workstation audit (cat.setup.SetupAudit), petroapps' "
                + "freshness (cat.collect) and the simulator staging check (cat.sims.config.verify)");
        if (rig != null && !fakePos) {
            boolean printer = rig.registers().stream().anyMatch(r -> r.printerPort() != null && !r.printerPort().isEmpty());
            if (printer) {
                out.append(", the receipt-printer lines of rig hardware readiness (a serial listing)");
            }
            if (rig.pinpad() == Rig.Pinpad.VATS) {
                out.append(", the VATS lines for pinpad: vats (the terminal, the SDK and BTT)");
            }
        }
        return out.toString();
    }

    private static boolean takeRunLock(RunContext ctx, StepEmitter step, String rig) {
        RigLease lease = new RigLease(rig + ".run", ctx.paths().leaseDir(),
                "cat run " + ctx.runId() + " (pid " + ProcessHandle.current().pid() + ")");
        try {
            lease.acquire(RUN_LOCK_WAIT);
        } catch (RigBusyException e) {
            step.check(false, "run lock",
                    "rig '" + rig + "' is in use by another CAT run: " + lease.describeHolder(), true);
            return step.fail("the rig is in use by another CAT run");
        } catch (RuntimeException e) {
            step.check(false, "run lock", e.getMessage(), true);
            return step.fail("the run lock could not be taken");
        }
        ctx.runLock(lease);
        step.check(true, "run lock", "rig " + rig, true);
        return true;
    }

    /**
     * The rig network lines as checklist rows. A required line that is not ok fails the step, and the
     * failure names every such line. Returns whether the run may go on past them.
     */
    private static boolean rigHardwareReady(StepEmitter step, Rig rig) {
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
        if (!failures.isEmpty()) {
            step.fail("rig hardware not ready: " + String.join("; ", failures));
            return false;
        }
        return true;
    }
}
