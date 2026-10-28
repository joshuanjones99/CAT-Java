package com.verifone.cat.launch;

import java.io.PrintStream;
import java.util.Map;

import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.forecourt.PetroPumps;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;

/**
 * The day close step 4 runs last and step 7 runs first: the Python CAT's {@code cat.launch.steps.cleanup._close_day},
 * one place for both, so they always run the same clause. The close itself is {@link CloseDay}; its progress lines
 * go to the step's log.
 *
 * <p>The guards, in the Python's order, each a clause and (but the first) a skipped row:
 * <ol>
 *   <li>no rig, or a fake-POS run: {@code no rig: day not closed}, no row;</li>
 *   <li>the run never reached step 4 ({@link RunContext#commanderReached}): skipped;</li>
 *   <li>a PSI install that has not reached {@code psi-init} ({@link RunContext#commanderLoginReady}): skipped, as a
 *       {@code manager} login with the lab password would count towards a lockout;</li>
 *   <li>{@code afterTests} (Cleanup's) and Tests Run never began: skipped, the register is left alone.</li>
 * </ol>
 * A close the Commander refused because it forces automatic end of day ({@link CloseDay.Outcome#FORCED_AEOD}, the
 * Python's {@code FORCED_AEOD_EXIT}) is a WARN when {@code forcedAeodWarns} (Cleanup's), else a failure like any
 * other. A failure's clause carries the close's last error line, as the Python's carries its child's.
 *
 * <p>As the Python's, PATS's pump port {@value PetroPumps#PATS_FUEL_PORT} is lent to the close and held again after
 * (when the run held it): the close's register pass ({@link com.verifone.cat.closeday.PosRegisterPass}, which
 * {@link CloseDay#forRig} builds) lifts a pump holding a prepay nobody fuelled through Petro on that port. "Tests
 * started" is {@link RunContext#testsStarted}, which the Tests Run step sets when its first case starts.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class DayClose {

    /** The close's checklist row, as the Python's {@code stage_label("close-day")} names it. */
    static final String LABEL = "Close the day";

    /** What a close ended in: whether the run may count the day closed (or not needed), and the clause for it. */
    record Result(boolean closed, String clause) {}

    /** The close itself, for one rig, its lines to {@code say}. A test passes a recorder. */
    @FunctionalInterface
    interface Closer {
        CloseDay.Outcome close(Rig rig, Say say);
    }

    private DayClose() {}

    /** The production close: {@link CloseDay#forRig} with {@code env} for the key and paths. */
    static Closer real(Map<String, String> env) {
        return (rig, say) -> {
            PrintStream out = LinePrintStream.out(say);
            PrintStream err = LinePrintStream.err(say);
            try {
                return CloseDay.forRig(rig, env, out, err).close();
            } finally {
                out.close();
                err.close();
            }
        };
    }

    /**
     * Closes the day, or says why not. Never throws for the close's own fault: every failure is a clause.
     *
     * @param forcedAeodWarns a forced-AEOD refusal is a WARN (Cleanup's)
     * @param afterTests      skip when Tests Run never began (Cleanup's)
     */
    static Result run(RunContext ctx, StepEmitter step, Closer closer, boolean forcedAeodWarns, boolean afterTests) {
        if (ctx.config().rig().isEmpty() || ctx.config().fakePos()) {
            return new Result(true, "no rig: day not closed");
        }
        if (!ctx.commanderReached()) {
            step.skip(LABEL, "the run stopped before Commander Configuration");
            return new Result(true, "day not closed: run stopped before Commander Configuration");
        }
        if (!ctx.commanderLoginReady()) {
            step.skip(LABEL, "PSI install did not reach psi-init, manager password not reset");
            return new Result(true, "day not closed: install did not reach psi-init");
        }
        if (afterTests && !ctx.testsStarted()) {
            step.skip(LABEL, "no test ran, so the register is left alone");
            return new Result(true, "day not closed: no test ran");
        }
        LoadedRig loaded = ctx.rig();
        if (loaded == null) {
            String clause = "close day FAILED (the rig was not loaded in System Checks)";
            step.substep(LABEL, SubstepOutcome.FAILED, clause, 0.0);
            return new Result(false, clause);
        }

        step.log("CAT-Java close-day --rig " + ctx.config().rig(), "INFO");
        step.substep(LABEL, SubstepOutcome.RUNNING, "", 0.0);
        long started = System.nanoTime();
        CommanderConfigurationStep.StepSay say = new CommanderConfigurationStep.StepSay(step);
        CloseDay.Outcome outcome;
        // The close lifts a pump holding a prepay nobody fuelled, which needs PATS's port: lent, as the tests are.
        boolean lent = PetroPorts.release(ctx, PetroPumps.PATS_FUEL_PORT);
        try {
            outcome = closer.close(loaded.rig(), say);
        } catch (StageUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            // The rest of Cleanup still has to stop the simulators and check the lease.
            String clause = "close day FAILED (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            step.substep(LABEL, SubstepOutcome.FAILED, clause, elapsed(started));
            return new Result(false, clause);
        } finally {
            if (lent) {
                PetroPorts.hold(ctx, step);
            }
        }
        double elapsed = elapsed(started);
        if (outcome == CloseDay.Outcome.FORCED_AEOD && forcedAeodWarns) {
            step.log("WARN  close-day — the Commander forces automatic end of day and refused a manual close; "
                    + "the next run's Commander Configuration closes it", "WARNING");
            step.substep(LABEL, SubstepOutcome.WARNED, "the Commander forces automatic end of day", elapsed);
            return new Result(true, "day not closed: the Commander forces AEOD (warned)");
        }
        if (outcome != CloseDay.Outcome.CLOSED) {
            String detail = say.lastErr().isEmpty() ? "exit " + outcome.exitCode() : say.lastErr();
            String clause = "close day FAILED (" + detail + ")";
            step.substep(LABEL, SubstepOutcome.FAILED, clause, elapsed);
            return new Result(false, clause);
        }
        step.substep(LABEL, SubstepOutcome.PASSED, "day closed", elapsed);
        return new Result(true, "day closed");
    }

    private static double elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }
}
