package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The step names are the Python CAT's {@code cat.state.STEP_NAMES}, spelled and ordered the same. */
class LaunchStepNamesTest {

    @Test
    void theSevenNamesAreThePythonCatsInItsOrder() {
        assertEquals(List.of(
                "System Checks",
                "Connections",
                "Start Simulators",
                "Commander Configuration",
                "Tests Run",
                "Generate Report",
                "Cleanup"), LaunchStepNames.ALL);
    }

    @Test
    void buildAndUpgradeShareStepFourAsInPython() {
        assertEquals(LaunchStepNames.COMMANDER, "Commander Configuration");
    }
}
