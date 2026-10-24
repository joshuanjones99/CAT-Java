package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.RunScope;

/**
 * The JaCoCo switch on {@link LaunchConfig}: off by default, and every constructor that predates it still works.
 */
class CoverageSwitchTest {

    @Test
    void aRunIsNotCoveredUnlessTheSwitchIsOn() {
        assertFalse(LaunchConfig.forRig("lab").coverage());
    }

    @Test
    void theBuilderTurnsTheSwitchOn() {
        assertTrue(new LaunchConfig.Builder("lab").coverage(true).build().coverage());
    }

    @Test
    void theTenArgumentConstructorStillWorksAndLeavesCoverageOff() {
        LaunchConfig config = new LaunchConfig("lab", List.of(), List.of("PETROCORE-135127"), UpgradeMode.SKIP,
                false, false, false, List.of(), RunScope.whole(), true);

        assertTrue(config.xrayReport());
        assertFalse(config.coverage());
    }

    @Test
    void theNineArgumentConstructorStillWorksAndLeavesBothSwitchesOff() {
        LaunchConfig config = new LaunchConfig("lab", List.of(), List.of(), UpgradeMode.SKIP, false, false, false,
                List.of(), RunScope.whole());

        assertFalse(config.xrayReport());
        assertFalse(config.coverage());
    }
}
