package com.verifone.cat.launch;

/**
 * A step whose body is not ported to CAT-Java yet. It does no work and always reports itself
 * unavailable, which is a failure: an unported step never reports success.
 *
 * <p>The reason names the Python module the step lives in and the piece that is missing, so a run
 * that stops at it says what to build next.
 */
abstract class UnportedStep implements LaunchStep {

    private final String pythonModule;
    private final String missing;

    protected UnportedStep(String pythonModule, String missing) {
        this.pythonModule = pythonModule;
        this.missing = missing;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        return step.unavailable(pythonModule, missing);
    }
}
