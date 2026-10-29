package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.rig.RigBusyException;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.Supervisor;

/**
 * Step 7, Cleanup: close the day, end the connection watch, stop the simulators CAT started, check the rig lease.
 * The Python CAT's {@code cat.launch.steps.cleanup._step_cleanup}, in its order.
 *
 * <ol>
 *   <li>The day close, {@link DayClose} with {@code forced_aeod_warns} and {@code after_tests}: first, while the
 *       simulators are still up (PATS's {@code cclosepdnow} names the fuel and DCR devices). A day that did not close
 *       fails the step, as the next run's period reports would be wrong; the rest still runs.</li>
 *   <li>The connection watch step 2 started, when one runs: stopped, a PASS row.</li>
 *   <li>The simulators this run spawned, and no others (D9): stopped, a PASS row naming them; none, a skipped row.</li>
 *   <li>Petro's pump-state ports the run holds ({@link PetroPorts#releaseAll}): released after the simulators, so
 *       Petro never pushes to a closed port while it runs.</li>
 *   <li>The rig lease: taken for a moment and given back, to prove no stray worker outlived the run. CAT holds no
 *       lease of its own (its run lock is a separate file, released by the launcher after this step); one still held
 *       is a WARN.</li>
 * </ol>
 * The detail lists every clause, so a Cleanup row never reads "all done" behind a caveat. The {@code run.ended} marker
 * is written by the launcher once the stream is closed, as the Python's {@code launch_run} does.
 *
 * <p>A continuous run ({@link LaunchConfig#keepSimulators}, the Python's {@code keep_simulators}) stops none of the
 * simulators: a skipped row names them, left running for the next pass to adopt (D9), and the run forgets them so the
 * launcher's release stops none either. A run that keeps Petro's ports ({@link RunContext#keepsPetroPorts}: a
 * continuous run, or a caller's sinks) leaves them held for whoever outlives it and says so in the detail. The
 * launcher still stops the simulators and the watch and releases the ports after this step, whatever happened, on
 * the same terms. Each kept simulator's log tail ends here ({@code stop_log}), as the Python's.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class CleanupStep implements LaunchStep {

    /** The checklist row of the day close, as the Python's {@code stage_label("close-day")}. */
    static final String DAY_LABEL = DayClose.LABEL;

    /** How long Cleanup waits to prove the rig lease is free: the Python's {@code RIG_LEASE_CHECK_TIMEOUT}. */
    static final Duration RIG_LEASE_CHECK_TIMEOUT = Duration.ofSeconds(5);

    private final DayClose.Closer closer;
    private final Duration leaseTimeout;

    /** The production step: the real day close. */
    CleanupStep() {
        this(DayClose.real(System.getenv()));
    }

    CleanupStep(DayClose.Closer closer) {
        this(closer, RIG_LEASE_CHECK_TIMEOUT);
    }

    CleanupStep(DayClose.Closer closer, Duration leaseTimeout) {
        this.closer = closer;
        this.leaseTimeout = leaseTimeout;
    }

    @Override
    public String name() {
        return LaunchStepNames.CLEANUP;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        List<String> parts = new ArrayList<>();

        DayClose.Result day = DayClose.run(ctx, step, closer, true, true);
        parts.add(day.clause());

        if (ctx.stopWatching()) {
            step.check(true, "connection watch", "stopped", true);
            parts.add("connection watch stopped");
        }

        List<SimProcess> procs = ctx.simulators();
        if (!procs.isEmpty() && ctx.config().keepSimulators()) {
            // A continuous run's next pass adopts them. Only the log tails end here.
            List<String> names = new ArrayList<>();
            for (SimProcess proc : procs) {
                if (proc.stopLog() != null) {
                    proc.stopLog().run();
                }
                names.add(proc.entry().name());
            }
            step.skip("stop simulators", "left running for the next continuous pass: " + String.join(", ", names));
            ctx.clearSimulators();
            parts.add("simulators left running (continuous run)");
        } else if (procs.isEmpty()) {
            step.skip("stop simulators", "none was started by CAT");
            parts.add("no CAT-started simulators");
        } else {
            List<String> stopped;
            try {
                stopped = ctx.supervisor().stop(procs, Supervisor.STOP_WAIT);
            } catch (RuntimeException e) {
                step.check(false, "stop simulators", e.getClass().getSimpleName() + ": " + e.getMessage(), false);
                stopped = null;
            }
            if (stopped != null) {
                step.check(true, "stop simulators", "stopped " + stopped.size() + " CAT-started simulator(s): "
                        + String.join(", ", stopped), true);
                ctx.clearSimulators();
                parts.add(stopped.size() + " simulator(s) stopped");
            } else {
                parts.add("simulators not stopped");
            }
        }

        if (ctx.keepsPetroPorts()) {
            // Petro stays up, so its ports stay held: the caller owns the sinks and closes them when the continuous
            // run ends, or keeps them for the Petro that Launch Simulators left running.
            if (!ctx.petroSinks().isEmpty()) {
                List<String> ports = new ArrayList<>();
                ctx.petroSinks().keySet().stream().sorted().forEach(port -> ports.add(String.valueOf(port)));
                parts.add("Petro port(s) " + String.join(", ", ports) + " kept held");
            }
        } else {
            // After the simulators, so Petro never pushes to a closed port while it runs.
            PetroPorts.releaseAll(ctx);
        }

        parts.add(checkRigLease(ctx, step, leaseTimeout));

        String detail = String.join("; ", parts);
        if (!day.closed()) {
            return step.fail(detail);
        }
        step.setDetail(detail);
        return true;
    }

    /**
     * Proves the rig is not still leased, and says so: the Python's {@code _check_rig_lease}. Deliberately not a
     * release: CAT holds no rig lease; taking it briefly and giving it straight back is the check.
     */
    static String checkRigLease(RunContext ctx, StepEmitter step, Duration timeout) {
        String rigName = ctx.rig() != null ? ctx.rig().rig().name() : ctx.config().rig();
        if (ctx.config().fakePos()) {
            return "no rig lease (fake POS)";
        }
        if (rigName == null || rigName.isBlank()) {
            return "no rig lease (no rig named)";
        }
        RigLease lease = new RigLease(rigName, ctx.paths().leaseDir(), "cat-cleanup/run" + ctx.runId());
        try {
            lease.acquire(timeout);
            lease.release();
        } catch (RigBusyException e) {
            step.warn("rig lease", "rig '" + rigName + "' is still leased after this run — " + e.getMessage());
            return "rig lease STILL HELD (" + rigName + ")";
        } catch (RuntimeException e) {
            lease.release();
            step.warn("rig lease", "could not check the lease on rig '" + rigName + "': "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return "rig lease unknown";
        }
        step.check(true, "rig lease", "rig '" + rigName + "' is free (CAT holds no lease of its own)", true);
        return "rig lease free";
    }
}
