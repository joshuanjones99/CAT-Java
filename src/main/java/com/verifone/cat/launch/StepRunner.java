package com.verifone.cat.launch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.report.events.CatEvent;

/**
 * Runs one step inside its bracket, guaranteeing it reaches a terminal state: the Python CAT's
 * {@code _run_local_step}, {@code _wait_while_paused} and {@code _skip_step}.
 *
 * <p>Order: wait while paused (the operator's Pause is honoured before the step starts), refuse
 * if stopped, then {@code StepEntered}, the body, {@code StepFinished}. A body that throws is a
 * failed step with the exception as its reason, not a crash of the launcher.
 */
final class StepRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StepRunner.class);

    private StepRunner() {}

    /** Thrown inside the run when the operator stops it; the launcher catches it and ends the run. */
    static final class Cancelled extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Cancelled() {
            super("run stopped by the operator", null, false, false);
        }
    }

    /** Runs {@code step}; returns whether the run may go on past it. */
    static boolean runLocal(RunContext ctx, LaunchStep step) {
        String where = "before " + step.name();
        if (ctx.pause().paused()) {
            ctx.emit(new CatEvent.LogLine("INFO", "cat.pause", "PAUSED " + where + ": press Continue", ""));
            ctx.pause().awaitContinue(ctx::cancelled);
            ctx.emit(new CatEvent.LogLine("INFO", "cat.pause", "CONTINUED " + where, ""));
        }
        if (ctx.cancelled()) {
            throw new Cancelled();
        }
        StepEmitter emitter = new StepEmitter(ctx, step.name());
        emitter.enter();
        boolean keepGoing;
        try {
            keepGoing = step.run(ctx, emitter);
        } catch (Cancelled stopped) {
            // The operator's Stop is not a step failure: the bracket still ends, as skipped, and the
            // launcher ends the run.
            emitter.skipStep("stopped by the operator");
            emitter.finish();
            throw stopped;
        } catch (RuntimeException e) {
            LOG.error("step {} raised", step.name(), e);
            keepGoing = emitter.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        emitter.finish();
        return keepGoing;
    }

    /** Marks a step the run never reached as skipped, with its reason. */
    static void skip(RunContext ctx, String name, String reason) {
        new StepEmitter(ctx, name).skipWhole(reason);
    }
}
