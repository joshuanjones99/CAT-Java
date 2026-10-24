package com.verifone.cat.launch;

/**
 * One of the seven steps: the Python CAT's {@code StepBody}, as a class.
 *
 * <p>{@link #run} does the step's work through the emitter and returns whether the run may go on.
 * A {@code false} ends the run: the steps after it are marked skipped. The bracket
 * ({@link StepRunner#runLocal}) owns {@code StepEntered} and {@code StepFinished}, so a step can
 * never end without a terminal state.
 *
 * <p>A step that is not ported returns {@link StepEmitter#unavailable}, which is a failure. No step
 * may report {@code complete} without doing its work.
 */
public interface LaunchStep {

    /** The step's name, one of {@link LaunchStepNames#ALL}. */
    String name();

    /**
     * Does the step's work.
     *
     * @return {@code true} when the run should go on to the next step
     */
    boolean run(RunContext ctx, StepEmitter step);
}
