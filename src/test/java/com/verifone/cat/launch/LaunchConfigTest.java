package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.runconfig.RunScope;

/** What a run is given: the argv it would spawn, the suite summary, the partial flag, the upgrade mode. */
class LaunchConfigTest {

    @Test
    void aRigRunDefaultsToNothingUpgradedAndTheWholePlan() {
        LaunchConfig config = LaunchConfig.forRig("lab");

        assertEquals(UpgradeMode.SKIP, config.upgradeMode());
        assertFalse(config.partial());
        assertTrue(config.simulators().isEmpty());
    }

    @Test
    void aRunNeedsARigUnlessItIsAFakePos() {
        assertThrows(IllegalArgumentException.class, () -> LaunchConfig.forRig(""));
        assertTrue(new LaunchConfig.Builder("").fakePos(true).build().fakePos());
    }

    @Test
    void theRigArgvIsPytestOverTheSuitesWithTheRigNamed() {
        LaunchConfig config = new LaunchConfig.Builder("lab")
                .suite("tests/suites/charity/PETROCORE-61334-TestCase.feature")
                .suite("tests/suites/charity/test_b.py")
                .build();

        assertEquals(List.of("python", "-m", "pytest",
                "tests/suites/charity/PETROCORE-61334-TestCase.feature", "tests/suites/charity/test_b.py",
                "--rig", "lab"), config.pytestArgv("python"));
    }

    @Test
    void aFakePosArgvNamesFakePosAndNoRigAndPinpadIsImpliedByIt() {
        LaunchConfig config = new LaunchConfig.Builder("")
                .fakePos(true)
                .fakePinpad(true)
                .suite("tests/suites/x/test_a.py")
                .build();

        assertEquals(List.of("python", "-m", "pytest", "tests/suites/x/test_a.py", "--fake-pos"),
                config.pytestArgv("python"));
    }

    @Test
    void fakePinpadAloneAddsItsFlagAndSnapshotUpdateAddsItsFlag() {
        LaunchConfig config = new LaunchConfig.Builder("lab").fakePinpad(true).snapshotUpdate(true).build();

        assertEquals(List.of("python", "-m", "pytest", "--rig", "lab", "--fake-pinpad", "--snapshot-update"),
                config.pytestArgv("python"));
    }

    @Test
    void theSuiteSummaryNamesEachDirectoryOnceNotEachFile() {
        LaunchConfig config = new LaunchConfig.Builder("lab")
                .suite("tests/suites/charity/a.feature")
                .suite("tests/suites/charity/b.feature")
                .suite("tests/suites/other/test_x.py::test_y")
                .build();

        assertEquals("tests/suites/charity tests/suites/other", config.suiteSummary());
    }

    @Test
    void aDirectoryTargetIsLeftAloneInTheSummary() {
        LaunchConfig config = new LaunchConfig.Builder("lab").suite("tests/suites/charity").build();

        assertEquals("tests/suites/charity", config.suiteSummary());
    }

    @Test
    void aTagsScopeIsPartialAndTheWholePlanIsNot() {
        LaunchConfig partial = new LaunchConfig.Builder("lab")
                .scope(new RunScope(RunScope.Mode.TAGS, "@smoke", List.of(), null, "", true))
                .build();

        assertTrue(partial.partial());
        assertFalse(LaunchConfig.forRig("lab").partial());
    }

    @Test
    void anUpgradeModeIsParsedByItsWireValueAndBlankIsSkip() {
        assertEquals(UpgradeMode.NEW_INSTALL, UpgradeMode.of("new_install"));
        assertEquals(UpgradeMode.SKIP, UpgradeMode.of("  "));
        assertEquals(UpgradeMode.CLASSIC, UpgradeMode.of("CLASSIC"));
        assertThrows(IllegalArgumentException.class, () -> UpgradeMode.of("psi"));
    }
}
