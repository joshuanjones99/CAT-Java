package com.verifone.cat.launch;

import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.SubstepOutcome;

/**
 * One step's {@code StepEntered} -> {@code StepProgress}* -> {@code StepFinished} bracket: the Python
 * CAT's {@code cat.launch.context._StepEmitter}.
 *
 * <p>A step body writes its checklist through this: {@link #check} for PASS/FAIL rows, {@link #skip},
 * {@link #warn}, {@link #log}. {@link #fail} and {@link #unavailable} set the state to failed; the
 * default is {@code complete}. {@link StepRunner} finishes the bracket.
 */
public final class StepEmitter {

    /** The logger name of a step's own lines (the Python CAT's {@code cat}). */
    static final String LOGGER = "cat";

    private final RunContext ctx;
    private final String step;
    private String state = StepResult.COMPLETE;
    private String detail = "";
    private long startedNanos;

    StepEmitter(RunContext ctx, String step) {
        this.ctx = ctx;
        this.step = step;
        this.startedNanos = System.nanoTime();
    }

    public String step() {
        return step;
    }

    /** The state the bracket will report: {@code complete}, {@code failed} or {@code skipped}. */
    public String state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    void enter() {
        startedNanos = System.nanoTime();
        ctx.emit(new CatEvent.StepEntered(step));
    }

    /** Progress within the step; {@code total} {@code null} while it is indeterminate. */
    public void progress(String text, int done, Integer total) {
        ctx.emit(new CatEvent.StepProgress(step, done, total, text));
    }

    /** One line in this step's log (its step field routes it to the step's rows). */
    public void log(String message, String level) {
        ctx.emit(new CatEvent.LogLine(level, LOGGER, message, step));
    }

    /** {@code PASS  <label> — <detail>} or {@code FAIL ...}, and the checklist row for it. */
    public void check(boolean ok, String label, String text, boolean required) {
        String tag = ok ? "PASS" : "FAIL";
        String level = ok ? "INFO" : (required ? "ERROR" : "WARNING");
        log(tag + "  " + label + (text.isEmpty() ? "" : " — " + text), level);
        String outcome = ok ? SubstepOutcome.PASSED : (required ? SubstepOutcome.FAILED : SubstepOutcome.WARNED);
        substep(label, outcome, text, 0.0);
    }

    /** {@code SKIP  <label> — <reason>}: a sub-task this run does not need. */
    public void skip(String label, String reason) {
        log("SKIP  " + label + (reason.isEmpty() ? "" : " — " + reason), "INFO");
        substep(label, SubstepOutcome.SKIPPED, reason, 0.0);
    }

    /** {@code WARN  <label> — <detail>}: a sub-task that went wrong without failing the step. */
    public void warn(String label, String text) {
        log("WARN  " + label + (text.isEmpty() ? "" : " — " + text), "WARNING");
        substep(label, SubstepOutcome.WARNED, text, 0.0);
    }

    /** One row of the step's checklist, without a log line. */
    public void substep(String label, String outcome, String text, double elapsed) {
        ctx.emit(new CatEvent.StepCheck(step, label, outcome, text, elapsed));
    }

    /** Sets the one-line detail the step's bracket reports, as the Python CAT's {@code step.detail}. */
    public void setDetail(String text) {
        detail = text == null ? "" : text;
    }

    /**
     * The step has nothing to do in this run: {@code skipped}, with its reason. The run goes on.
     * Pair it with {@link #skip} for the checklist row, as the Python CAT does.
     */
    public boolean skipStep(String reason) {
        state = StepResult.SKIPPED;
        detail = reason;
        return true;
    }

    /** The step failed. Always returns {@code false}: the run does not go on past a failed step. */
    public boolean fail(String reason) {
        state = StepResult.FAILED;
        detail = reason;
        log("FAIL  " + step + " — " + reason, "ERROR");
        return false;
    }

    /**
     * The step's work is not in CAT-Java yet. Always a failure, never a success; the reason names
     * the Python module and the piece that is missing.
     */
    public boolean unavailable(String pythonModule, String missing) {
        return fail("unavailable in CAT-Java: needs " + missing + " (Python: " + pythonModule + ")");
    }

    /** The bracket's end: {@code StepFinished} with the state and detail the body left. */
    void finish() {
        double elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        ctx.emit(new CatEvent.StepFinished(step, state, elapsed, detail));
        ctx.recordStep(step, state, detail);
    }

    /** A step the run never reached: {@code skipped}, with its reason. */
    void skipWhole(String reason) {
        state = StepResult.SKIPPED;
        detail = reason;
        ctx.emit(new CatEvent.StepFinished(step, StepResult.SKIPPED, 0.0, reason));
        ctx.recordStep(step, StepResult.SKIPPED, reason);
    }
}
