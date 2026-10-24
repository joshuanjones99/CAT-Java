package com.verifone.cat.runservice;

import java.util.List;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runconfig.RunScope;

/**
 * Turns the Config tab's saved choices into the {@link LaunchConfig} Start run launches, or says
 * why they cannot start a run. The Python CAT's {@code CatService._startable_config}.
 *
 * <p>Nothing is upgraded, started or simulated from here: the Downloads, Execution and simulator
 * choices are not ported yet, so a run from this page is the seven steps with their defaults.
 */
public final class RunStart {

    /** Partial runs are refused here as the launcher refuses them, so the operator is told before the run starts. */
    static final String PARTIAL_REFUSED = "Partial runs are not ported to CAT-Java yet: pick Whole plan on the "
            + "Config tab (the Python CAT reads petroapps' case files to make a partial selection).";

    private RunStart() {}

    /**
     * The config to start, or {@code null} with the refusal in {@link Plan#refusal()}.
     *
     * @param rig the rig the web UI was started with; empty refuses the start
     */
    public record Plan(LaunchConfig config, String refusal) {

        public Plan {
            refusal = refusal == null ? "" : refusal;
        }

        public boolean startable() {
            return config != null && refusal.isEmpty();
        }
    }

    public static Plan from(ConfigForm form, String rig) {
        if (rig == null || rig.isBlank()) {
            return refused("No rig is named, so a run has nowhere to go. Start CAT-Java with --rig.");
        }
        RunScope scope = form.scope();
        if (scope.mode() != RunScope.Mode.WHOLE) {
            return refused(PARTIAL_REFUSED);
        }
        List<String> missing = form.missingRequirements();
        if (!missing.isEmpty()) {
            return refused("Nothing to run yet: " + String.join("; ", missing) + ".");
        }
        if (form.plans().isEmpty()) {
            return refused("Nothing to run yet: tick a test plan on the Config tab.");
        }
        LaunchConfig config = new LaunchConfig(rig, List.of(), form.plans(), UpgradeMode.SKIP,
                false, false, false, List.of(), scope);
        return new Plan(config, "");
    }

    private static Plan refused(String why) {
        return new Plan(null, why);
    }
}
