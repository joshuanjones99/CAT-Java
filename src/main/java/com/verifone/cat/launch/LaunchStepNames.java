package com.verifone.cat.launch;

import java.util.List;

/**
 * The seven step names, in run order, spelled as the Python CAT's {@code cat.state.STEP_NAMES}.
 *
 * <p>The events carry these strings and the run log's {@code STEP n} tags are their positions, so a
 * misspelt name would make a step invisible to a reader. {@code LaunchStepNamesTest} pins them.
 * Build and Upgrade share step 4 in the Python CAT and so does this port.
 */
public final class LaunchStepNames {

    public static final String SYSTEM_CHECKS = "System Checks";
    public static final String CONNECTIONS = "Connections";
    public static final String SIMULATORS = "Start Simulators";
    public static final String COMMANDER = "Commander Configuration";
    public static final String TESTS = "Tests Run";
    public static final String REPORT = "Generate Report";
    public static final String CLEANUP = "Cleanup";

    /** Every step, in run order. */
    public static final List<String> ALL = List.of(
            SYSTEM_CHECKS, CONNECTIONS, SIMULATORS, COMMANDER, TESTS, REPORT, CLEANUP);

    private LaunchStepNames() {}
}
