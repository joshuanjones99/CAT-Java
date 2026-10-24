package com.verifone.cat.launch;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.LoadedRig;

/**
 * Step 7, Cleanup: close the day, stop the simulators CAT started, end the connection watch, check the
 * rig lease.
 *
 * <p>The day close is wired: {@link CloseDay} closes the period on the rig's Commander, and its progress
 * lines go to this step's log. It is skipped, as in the Python, when the run had no rig, ran against the
 * fake POS, or never reached Commander Configuration or Tests Run. The rest is not ported (the simulator
 * stop, the connection watch and the lease check), so the step still ends {@code failed} as unavailable
 * once the day close has been tried. The run lock is released by the launcher after this step whether or
 * not it ran, and the {@code run.ended} marker is written by the launcher too.
 *
 * <p>The Python's {@code commander_reached}, {@code commander_login_ready} and {@code tests_started} flags
 * are not carried by {@link RunContext}. The step results stand in for them: Commander Configuration must
 * be complete, and Tests Run complete or failed (a step that began). Only a run that completed step 4 can
 * reach the close, and no step 4 is ported yet, so the close is not reached by any run today.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class CleanupStep implements LaunchStep {

    /** The Python step this one ports, for the unavailable message. */
    static final String PYTHON_MODULE = "src/cat/launch/steps/cleanup.py";

    /** The checklist row of the day close, as the Python's {@code stage_label("close-day")}. */
    static final String DAY_LABEL = "Close the day";

    /** What the step still lacks, named for the operator. */
    static final String MISSING = "the simulator stop (cat.sims.supervisor), the connection watch from step 2 "
            + "and the rig lease check";

    @Override
    public String name() {
        return LaunchStepNames.CLEANUP;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        String failure = closeTheDay(ctx, step);
        if (failure != null) {
            return step.fail(failure);
        }
        return step.unavailable(PYTHON_MODULE, MISSING);
    }

    /** The day close. Returns the failure clause, or null when the day is closed, warned or not needed. */
    private static String closeTheDay(RunContext ctx, StepEmitter step) {
        if (ctx.config().rig().isEmpty() || ctx.config().fakePos()) {
            step.log("no rig: day not closed", "INFO");
            return null;
        }
        if (!isOneOf(stateOf(ctx, LaunchStepNames.COMMANDER), StepResult.COMPLETE)) {
            step.skip(DAY_LABEL, "the run stopped before Commander Configuration");
            return null;
        }
        if (!isOneOf(stateOf(ctx, LaunchStepNames.TESTS), StepResult.COMPLETE, StepResult.FAILED)) {
            // The close's register pass signs a cashier on; with no case run there is nothing to clear.
            step.skip(DAY_LABEL, "no test ran, so the register is left alone");
            return null;
        }
        LoadedRig loaded = ctx.rig();
        if (loaded == null) {
            return "close day FAILED (the rig was not loaded in System Checks)";
        }

        step.substep(DAY_LABEL, SubstepOutcome.RUNNING, "", 0.0);
        long started = System.nanoTime();
        CloseDay.Outcome outcome;
        PrintStream out = linesTo(step, "INFO");
        PrintStream err = linesTo(step, "WARNING");
        try {
            outcome = CloseDay.forRig(loaded.rig(), System.getenv(), out, err).close();
        } catch (RuntimeException e) {
            String clause = "close day FAILED (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            step.substep(DAY_LABEL, SubstepOutcome.FAILED, clause, elapsed(started));
            return clause;
        } finally {
            out.close();
            err.close();
        }

        double elapsed = elapsed(started);
        if (outcome == CloseDay.Outcome.CLOSED) {
            step.substep(DAY_LABEL, SubstepOutcome.PASSED, "day closed", elapsed);
            return null;
        }
        if (outcome == CloseDay.Outcome.FORCED_AEOD) {
            step.log("WARN  close-day — the Commander forces automatic end of day and refused a manual close; "
                    + "the next run's Commander Configuration closes it", "WARNING");
            step.substep(DAY_LABEL, SubstepOutcome.WARNED, "the Commander forces automatic end of day", elapsed);
            return null;
        }
        String clause = "close day FAILED (exit " + outcome.exitCode() + ")";
        step.substep(DAY_LABEL, SubstepOutcome.FAILED, clause, elapsed);
        return clause;
    }

    /** The state a step ended in, or pending when the run never reached it. */
    private static String stateOf(RunContext ctx, String name) {
        return ctx.stepResults().stream()
                .filter(result -> result.name().equals(name))
                .map(StepResult::state)
                .findFirst()
                .orElse(StepResult.PENDING);
    }

    private static boolean isOneOf(String state, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(state)) {
                return true;
            }
        }
        return false;
    }

    private static double elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    /** A stream whose each line is one line of this step's log, at {@code level}. */
    private static PrintStream linesTo(StepEmitter step, String level) {
        return new PrintStream(new LineOutputStream(line -> step.log(line, level)), true, StandardCharsets.UTF_8);
    }

    /** Splits written bytes into lines, dropping blank ones, and hands each to {@code sink}. */
    private static final class LineOutputStream extends OutputStream {

        private final Consumer<String> sink;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        LineOutputStream(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                pending.write(b);
            }
        }

        @Override
        public void close() {
            if (pending.size() > 0) {
                emit();
            }
        }

        private void emit() {
            String line = pending.toString(StandardCharsets.UTF_8).strip();
            pending.reset();
            if (!line.isEmpty()) {
                sink.accept(line);
            }
        }
    }
}
