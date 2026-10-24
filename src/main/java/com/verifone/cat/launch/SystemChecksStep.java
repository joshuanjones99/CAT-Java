package com.verifone.cat.launch;

import java.time.Duration;

import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.RigBusyException;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 1, System Checks: the rig file, and the run lock that keeps one run per rig.
 *
 * <p>The Python step 1 is the workstation audit, the rig file, petroapps' freshness, rig hardware
 * readiness and the simulator staging check. It is not essentially the preflight, so this step does
 * not call {@code preflight.PreflightRunner}. Only the two checks that need nothing unported run here:
 * <ul>
 *   <li>the rig file loads ({@code RigLoader}); a file that will not load fails the step;</li>
 *   <li>the run lock ({@code cat.launch.run_lock}, {@code RigLease} on {@code <rig>.run.lock}) is free;
 *       a run that finds it held stops here and names the holder. It does not wait for it.</li>
 * </ul>
 * The rest is unavailable, and the step ends failed, so no run gets past step 1 on a partial check.
 * A fake-POS run takes no run lock, as the Python one does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the rig file and lock are local; nothing here reaches a device.
 */
final class SystemChecksStep implements LaunchStep {

    /** How long step 1 waits for the run lock: the Python CAT's {@code RUN_LOCK_WAIT}. */
    static final Duration RUN_LOCK_WAIT = Duration.ofSeconds(3);

    @Override
    public String name() {
        return LaunchStepNames.SYSTEM_CHECKS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        String rigName = ctx.config().rig();
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
            step.check(true, "rig file", loaded.source().toString(), true);
            if (!ctx.config().fakePos() && !takeRunLock(ctx, step, loaded.rig().name())) {
                return false;
            }
        }
        return step.unavailable("src/cat/launch/steps/system_checks.py",
                "the workstation audit (cat.setup.SetupAudit), petroapps' freshness (cat.collect), "
                        + "rig hardware readiness (connections.RigReadiness is ported, its wiring is not) "
                        + "and the simulator staging check (cat.sims.config.verify)");
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
}
