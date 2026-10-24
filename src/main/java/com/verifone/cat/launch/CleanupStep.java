package com.verifone.cat.launch;

/**
 * Step 7, Cleanup: close the day, stop the simulators CAT started, end the connection watch, check the
 * rig lease. Unavailable: the day close needs the Commander client, and the stop needs the simulator
 * supervisor. The run lock is released by the launcher after this step whether or not it ran, and
 * the {@code run.ended} marker is written by the launcher too.
 */
final class CleanupStep extends UnportedStep {

    CleanupStep() {
        super("src/cat/launch/steps/cleanup.py",
                "the day close (_close_day, which needs the Commander client), the stop of CAT-started "
                        + "simulators (cat.sims.supervisor) and the connection watch from step 2");
    }

    @Override
    public String name() {
        return LaunchStepNames.CLEANUP;
    }
}
