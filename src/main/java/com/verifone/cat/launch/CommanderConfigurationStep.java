package com.verifone.cat.launch;

/**
 * Step 4, Commander Configuration: the build, the upgrade, provisioning, the EPS and PATS inits and
 * the day close. Unavailable: every stage of it talks to the Commander, and no Commander client is
 * ported. The Python CAT's Commander CGILink client was blocked in wave 1 (HANDOFF.md section 5).
 */
final class CommanderConfigurationStep extends UnportedStep {

    CommanderConfigurationStep() {
        super("src/cat/launch/steps/commander.py",
                "the Commander client (petrotest.devices.config_shim CGILink), commander-init, the day "
                        + "close (cat/launch/steps/cleanup.py _close_day), and the build, upgrade and "
                        + "provision stages");
    }

    @Override
    public String name() {
        return LaunchStepNames.COMMANDER;
    }
}
