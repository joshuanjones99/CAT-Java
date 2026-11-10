package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.runconfig.RunScope;

/**
 * The web's run-start config ({@link LaunchDefaults#fromSaved}), field by field, and where it agrees with
 * {@code launch}'s flags-over-saved config ({@link LaunchCommand.Options#toConfig}) and where the two differ on purpose.
 */
class LaunchDefaultsTest {

    private static final LaunchDefaults.Picked PICKED = new LaunchDefaults.Picked(
            List.of("Petro Simulator Suite", "LoySim"), List.of("printer", "topaz102"), true, true);

    private static LastConfig everythingSaved() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put(LastConfig.UPGRADE, "new_install");
        saved.put(LastConfig.DIST, " chevron ");
        saved.put(LastConfig.EXEC, "BDD");
        saved.put(LastConfig.TP_BRANCH, "release/57");
        saved.put(LastConfig.CC_ONBOARD, true);
        saved.put(LastConfig.CC_LAB, "QAT");
        saved.put(LastConfig.CC_EMAIL, "lab@example.com");
        saved.put(LastConfig.CC_SERVICE_ID, "1234567");
        saved.put(LastConfig.CC_PASSWORD, "saved-pw");
        saved.put(LastConfig.COVERAGE, true);
        saved.put(LastConfig.XRAY, false);
        saved.put(LastConfig.CMD_BRANCH, "PETROCORE-1");
        saved.put(LastConfig.CMD_BUILD, "12");
        saved.put(LastConfig.FND_BRANCH, "master057.02");
        saved.put(LastConfig.FND_BUILD, "34");
        saved.put(LastConfig.SIM_BRANCH, "master");
        saved.put(LastConfig.SIM_BUILD, "74");
        saved.put(LastConfig.IFSF, "-ifsfPOS");
        return LastConfig.of(saved);
    }

    @Test
    void withNothingSavedEveryFieldIsThePythonDefaultAndTheRegisterIsProvisioned() {
        LaunchConfig config = LaunchDefaults.fromSaved("lab", List.of("PETROCORE-70960"), null, true,
                LastConfig.none(), Map.of(), PICKED, null);

        LaunchConfig expected = new LaunchConfig.Builder("lab")
                .testPlan("PETROCORE-70960")
                .xrayReport(true)
                .provisionRegister(true)
                .simulators(PICKED.simulators())
                .connections(PICKED.connections())
                .build();
        assertEquals(expected, config);
        assertEquals(UpgradeMode.SKIP, config.upgradeMode());
        assertEquals("buypass", config.dist());
        assertEquals("dev", config.executionMode());
        assertEquals("master", config.testPlanBranch());
        assertEquals(RunScope.whole(), config.scope());
        assertEquals(LaunchConfig.NO_COMMANDER_CONNECT, config.commanderConnectSettings());
    }

    @Test
    void theSavedChoicesFillEveryFieldTheyHoldAndTheXrayArgumentWinsOverTheSavedSwitch() {
        RunScope scope = RunScope.whole().withMode(RunScope.Mode.TAGS).withTags("@smoke");
        LaunchConfig config = LaunchDefaults.fromSaved("lab", List.of("A-1", "A-2"), scope, true, everythingSaved(),
                Map.of(CommanderConnect.PASSWORD_ENV, "env-pw"), PICKED, null);

        LaunchConfig expected = new LaunchConfig.Builder("lab")
                .testPlan("A-1").testPlan("A-2")
                .upgradeMode(UpgradeMode.NEW_INSTALL)
                .dist("chevron")
                .executionMode("BDD")
                .testPlanBranch("release/57")
                .xrayReport(true)
                .coverage(true)
                .commanderConnect(true)
                .commanderConnect(new CommanderConnect("QAT", "lab@example.com", "1234567", "saved-pw"))
                .provisionRegister(true)
                .scope(scope)
                .commanderBranch("PETROCORE-1").commanderBuild("12")
                .foundationBranch("master057.02").foundationBuild("34")
                .simBranch("master").simBuild("74")
                .simulators(PICKED.simulators())
                .connections(PICKED.connections())
                .build();
        assertEquals(expected, config);
        // run_plan reads neither, nor does the web's run start: the saved IFSF mode and local build are not carried.
        assertEquals("", config.ifsfMode());
        assertEquals("", config.localBuild());
    }

    @Test
    void thePasswordComesFromTheEnvironmentWhenNoFileHoldsIt() {
        LaunchConfig config = LaunchDefaults.fromSaved("lab", List.of(), null, false,
                LastConfig.of(Map.of(LastConfig.CC_EMAIL, "lab@example.com")),
                Map.of(CommanderConnect.PASSWORD_ENV, "env-pw"), PICKED, null);
        assertEquals(new CommanderConnect("Prod", "lab@example.com", "", "env-pw"), config.commanderConnectSettings());
    }

    @Test
    void aSavedExecutionModeTheLauncherDoesNotKnowIsTheDefaultHereAndAUsageErrorForLaunch() {
        LastConfig saved = LastConfig.of(Map.of(LastConfig.EXEC, "prod"));
        assertEquals("dev", LaunchDefaults.fromSaved("lab", List.of(), null, false, saved, Map.of(), PICKED, null)
                .executionMode());
        LaunchCommand.Options options = LaunchCommand.Options.parse(new String[] {"--rig", "lab"});
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> options.toConfig(saved, Map.of(), List.of(), List.of()));
        assertEquals("--exec must be one of [dev, BDD], not 'prod'.", refused.getMessage());
    }

    @Test
    void aSavedUpgradeModeNobodyKnowsIsRefusedByBothAndLaunchNamesItBeforeTheExecutionMode() {
        LastConfig saved = LastConfig.of(Map.of(LastConfig.UPGRADE, "psi", LastConfig.EXEC, "prod"));
        IllegalArgumentException web = assertThrows(IllegalArgumentException.class,
                () -> LaunchDefaults.fromSaved("lab", List.of(), null, false, saved, Map.of(), PICKED, null));
        LaunchCommand.Options options = LaunchCommand.Options.parse(new String[] {"--rig", "lab"});
        IllegalArgumentException cli = assertThrows(IllegalArgumentException.class,
                () -> options.toConfig(saved, Map.of(), List.of(), List.of()));
        assertEquals(web.getMessage(), cli.getMessage());
        assertThrows(IllegalArgumentException.class, () -> UpgradeMode.of("psi"));
        assertEquals(assertThrows(IllegalArgumentException.class, () -> UpgradeMode.of("psi")).getMessage(),
                cli.getMessage());
    }

    @Test
    void thePagesDownloadsChoiceReplacesTheSavedOneAndIsNeverReadFromTheFile() {
        // The saved upgrade mode is not even parsed: a file holding a mode nobody knows does not stop the page's run.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(LastConfig.UPGRADE, "psi");
        raw.put(LastConfig.DIST, "chevron");
        raw.put(LastConfig.CMD_BRANCH, "Saved");
        raw.put(LastConfig.CMD_BUILD, "1");
        raw.put(LastConfig.FND_BRANCH, "SavedFoundation");
        raw.put(LastConfig.FND_BUILD, "2");
        raw.put(LastConfig.SIM_BRANCH, "SavedSims");
        raw.put(LastConfig.SIM_BUILD, "3");
        LastConfig saved = LastConfig.of(raw);

        LaunchConfig chosen = LaunchDefaults.fromSaved("lab", List.of(), null, false, saved, Map.of(), PICKED,
                new LaunchDefaults.Downloads(UpgradeMode.CLASSIC, "", "Solutions", "134", "ignored", "master", "74"));
        assertEquals(UpgradeMode.CLASSIC, chosen.upgradeMode());
        assertEquals("buypass", chosen.dist());
        assertEquals("Solutions", chosen.commanderBranch());
        assertEquals("134", chosen.commanderBuild());
        assertEquals("", chosen.localBuild());
        assertEquals("", chosen.foundationBranch());
        assertEquals("", chosen.foundationBuild());
        assertEquals("master", chosen.simBranch());
        assertEquals("74", chosen.simBuild());

        // A branch without its number (or a number without its branch) is no build; with no branch the local build is.
        LaunchConfig halves = LaunchDefaults.fromSaved("lab", List.of(), null, false, saved, Map.of(), PICKED,
                new LaunchDefaults.Downloads(null, "sunoco", "", "134", "Solutions133", "master", ""));
        assertEquals(UpgradeMode.SKIP, halves.upgradeMode());
        assertEquals("sunoco", halves.dist());
        assertEquals("", halves.commanderBranch());
        assertEquals("", halves.commanderBuild());
        assertEquals("Solutions133", halves.localBuild());
        assertEquals("", halves.simBranch());
        assertEquals("", halves.simBuild());

        LaunchConfig branchOnly = LaunchDefaults.fromSaved("lab", List.of(), null, false, saved, Map.of(), PICKED,
                new LaunchDefaults.Downloads(UpgradeMode.SKIP, "buypass", "Solutions", "", "Solutions133", "", "74"));
        assertEquals("", branchOnly.commanderBranch());
        assertEquals("", branchOnly.commanderBuild());
        assertEquals("", branchOnly.localBuild());
        assertEquals("", branchOnly.simBranch());
        assertEquals("", branchOnly.simBuild());
    }

    @Test
    void launchWithNoFlagsTakesTheSavedChoicesExactlyAsTheWebDoes() {
        // What the two share: with nothing typed, launch's config is the web's for a whole-plan run that reports to
        // Xray as the saved switch says. (They differ on purpose only in a bad saved execution mode, above.)
        for (LastConfig saved : List.of(LastConfig.none(), everythingSaved())) {
            LaunchConfig web = LaunchDefaults.fromSaved("lab", List.of("A-1"), null, saved.xray(), saved,
                    Map.of(CommanderConnect.PASSWORD_ENV, "env-pw"), PICKED, null);
            LaunchConfig cli = LaunchCommand.Options.parse(new String[] {"--rig", "lab", "--plan", "A-1"})
                    .toConfig(saved, Map.of(CommanderConnect.PASSWORD_ENV, "env-pw"), PICKED.simulators(),
                            PICKED.connections());
            assertEquals(web, cli);
        }
    }

    @Test
    void aFlagWinsOverTheSavedChoiceAndATypedCommanderBranchTakesNoSavedFoundation() {
        LaunchConfig cli = LaunchCommand.Options.parse(new String[] {"--rig", "lab", "--upgrade", "skip", "--dist",
                "sunoco", "--branch", "feature/x", "--solutions-build", "228", "--sim-build", "80", "--no-xray",
                "--no-coverage", "--no-cc-onboard"}).toConfig(everythingSaved(), Map.of(), List.of(), List.of());
        assertEquals(UpgradeMode.SKIP, cli.upgradeMode());
        assertEquals("sunoco", cli.dist());
        assertEquals("feature/x", cli.testPlanBranch());
        assertEquals("BDD", cli.executionMode());
        assertEquals("Solutions", cli.commanderBranch());
        assertEquals("228", cli.commanderBuild());
        assertEquals("", cli.foundationBranch());
        assertEquals("", cli.foundationBuild());
        assertEquals("master", cli.simBranch());
        assertEquals("80", cli.simBuild());
        assertEquals(false, cli.xrayReport());
        assertEquals(false, cli.coverage());
        assertEquals(false, cli.commanderConnect());
        assertEquals(true, cli.provisionRegister());
    }
}
