package com.verifone.cat.commander.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.RunScope;

/**
 * The Commander Connect switch on {@link LaunchConfig}: off by default, every constructor that predates it still
 * works, and the password never reaches the record's {@code toString}.
 */
class CommanderConnectSwitchTest {

    @Test
    void aRunDoesNotOnboardUnlessTheSwitchIsOn() {
        LaunchConfig config = LaunchConfig.forRig("lab");
        assertFalse(config.commanderConnect());
        assertEquals(LaunchConfig.NO_COMMANDER_CONNECT, config.commanderConnectSettings());
        assertEquals("Prod", config.commanderConnectSettings().lab());
    }

    @Test
    void theBuilderCarriesTheSwitchAndItsValues() {
        CommanderConnect values = new CommanderConnect("QAT", "a@b.com", "1234567", "hunter2");
        LaunchConfig config = new LaunchConfig.Builder("lab").commanderConnect(true).commanderConnect(values).build();
        assertTrue(config.commanderConnect());
        assertEquals(values, config.commanderConnectSettings());
        assertFalse(config.toString().contains("hunter2"), config.toString());
    }

    @Test
    void theOlderConstructorsStillWorkAndLeaveTheSwitchOff() {
        LaunchConfig eleven = new LaunchConfig("lab", List.of(), List.of(), UpgradeMode.SKIP, false, false, false,
                List.of(), RunScope.whole(), true, true);
        assertTrue(eleven.coverage());
        assertFalse(eleven.commanderConnect());
        LaunchConfig nine = new LaunchConfig("lab", List.of(), List.of(), UpgradeMode.SKIP, false, false, false,
                List.of(), RunScope.whole());
        assertFalse(nine.commanderConnect());
    }

    @Test
    void nullValuesAreTheDefaults() {
        LaunchConfig config = new LaunchConfig("lab", List.of(), List.of(), UpgradeMode.SKIP, false, false, false,
                List.of(), RunScope.whole(), false, false, true, null);
        assertTrue(config.commanderConnect());
        assertEquals(LaunchConfig.NO_COMMANDER_CONNECT, config.commanderConnectSettings());
    }
}
