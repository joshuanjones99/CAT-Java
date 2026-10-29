package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.ConfigFixture;
import com.verifone.cat.runconfig.ConfigForm;

/** Start run's config, built from the Config tab's saved choices, and its refusals. */
class RunStartTest {

    @TempDir
    Path tmp;

    @Test
    void aRunNeedsARigNamed() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);

        RunStart.Plan plan = RunStart.from(form, "");

        assertFalse(plan.startable());
        assertTrue(plan.refusal().contains("--rig"), plan.refusal());
    }

    @Test
    void nothingTickedRefusesTheStart() throws IOException {
        RunStart.Plan plan = RunStart.from(ConfigFixture.form(tmp), "lab");

        assertFalse(plan.startable());
        assertTrue(plan.refusal().startsWith("Nothing to run yet"), plan.refusal());
    }

    @Test
    void aWholePlanBecomesTheLaunchConfigWithTheSavedPlans() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);

        RunStart.Plan plan = RunStart.from(form, "lab");

        assertTrue(plan.startable(), plan.refusal());
        assertEquals("lab", plan.config().rig());
        assertEquals(List.of(ConfigFixture.PLAN), plan.config().testPlans());
        assertEquals(UpgradeMode.SKIP, plan.config().upgradeMode());
        assertFalse(plan.config().partial());
    }

    @Test
    void aPartialRunIsCarriedAsTheScopeForTheLauncherToSelect() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("tags", "fuel", false);

        RunStart.Plan plan = RunStart.from(form, "lab");

        assertTrue(plan.startable(), plan.refusal());
        assertTrue(plan.config().partial());
        assertEquals("fuel", plan.config().scope().tags());
        assertTrue(plan.readBack().stream().anyMatch(l -> l.warning() && l.text().startsWith("Run scope:")),
                plan.readBack().toString());
    }

    @Test
    void aTagExpressionThatMatchesNothingStillRefuses() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("tags", "smoke", false);

        RunStart.Plan plan = RunStart.from(form, "lab");

        assertFalse(plan.startable());
        assertTrue(plan.refusal().startsWith("Nothing to run yet"), plan.refusal());
    }

    @Test
    void theSavedChoicesAndTheDefaultsMakeTheConfigTheWayLaunchDoes() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        com.verifone.cat.launch.LastConfig saved = com.verifone.cat.launch.LastConfig.of(java.util.Map.of(
                "config-upgrade", "new_install", "config-exec", "BDD", "config-tp-branch", "PETROCORE-1",
                "config-cmd-branch", "Solutions", "config-cmd-build", "134", "config-coverage", true));
        RunStart.Context ctx = new RunStart.Context("lab", () -> saved, java.util.Map.of(), null, null, null);

        RunStart.Plan plan = RunStart.from(form, ctx);

        assertTrue(plan.startable(), plan.refusal());
        assertEquals(UpgradeMode.NEW_INSTALL, plan.config().upgradeMode());
        assertEquals("BDD", plan.config().executionMode());
        assertEquals("PETROCORE-1", plan.config().testPlanBranch());
        assertEquals("Solutions", plan.config().commanderBranch());
        assertEquals("134", plan.config().commanderBuild());
        assertTrue(plan.config().coverage());
        assertTrue(plan.config().provisionRegister());
        // No plan row names simulators, so every launchable one is started, as launch does.
        assertEquals(com.verifone.cat.launch.LaunchDefaults.defaultSimulators(), plan.config().simulators());
        List<String> lines = plan.readBack().stream().map(RunStart.Line::text).toList();
        assertTrue(lines.contains("Upgrade:       new_install"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Commander:     download Solutions 134 for buypass, "
                + "install it with PSI (new install), then run psi-init")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("What this run will start: Simulators: ")),
                lines.toString());
    }

    @Test
    void thePagesDownloadsChoiceReplacesTheSavedOne() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        com.verifone.cat.launch.LastConfig saved = com.verifone.cat.launch.LastConfig.of(java.util.Map.of(
                "config-upgrade", "new_install", "config-cmd-branch", "Solutions", "config-cmd-build", "134"));
        RunStart.Context ctx = new RunStart.Context("lab", () -> saved, java.util.Map.of(), null, null, null)
                .withDownloads(() -> new com.verifone.cat.launch.LaunchDefaults.Downloads(UpgradeMode.SKIP,
                        "buypass", "", "", "", "master", ""));

        RunStart.Plan plan = RunStart.from(form, ctx);

        assertEquals(UpgradeMode.SKIP, plan.config().upgradeMode());
        assertEquals("", plan.config().commanderBranch());
        assertEquals("", plan.config().simBranch(), "a simulator branch with no build replaces nothing");
    }

    @Test
    void theXraySwitchIsOnByDefaultAndCarriedIntoTheLaunchConfig() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);

        assertTrue(RunStart.from(form, "lab").config().xrayReport());
    }

    @Test
    void aSavedXrayOffIsCarriedAsOff() throws IOException {
        Path saved = tmp.resolve("cfg").resolve("last-config.json");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "{\"config-xray\": false}");
        ConfigForm form = ConfigFixture.form(tmp);
        form.load();
        form.togglePlan(ConfigFixture.PLAN);

        assertFalse(RunStart.from(form, "lab").config().xrayReport());
    }
}
