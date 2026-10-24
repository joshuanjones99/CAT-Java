package com.verifone.cat.launch;

/**
 * Step 3, Start Simulators. With nothing selected it is complete and says so; with a simulator
 * selected it is unavailable, because the supervisor that adopts or starts one is not ported.
 *
 * <p>The Python step also fails the run on a name the registry does not know. That check needs the
 * registry, so it waits for the supervisor too.
 */
final class StartSimulatorsStep implements LaunchStep {

    @Override
    public String name() {
        return LaunchStepNames.SIMULATORS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        if (ctx.config().simulators().isEmpty()) {
            step.skip("simulators", "none selected");
            return true;
        }
        return step.unavailable("src/cat/launch/steps/simulators.py",
                "the simulator supervisor (cat.sims.supervisor), the simulator registry (registry.yaml) "
                        + "and the Java Access Bridge for the Swing start buttons");
    }
}
