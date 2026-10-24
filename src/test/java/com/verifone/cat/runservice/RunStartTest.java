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
    void aPartialRunIsRefusedBeforeItStarts() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        form.setScope("tags", "smoke", false);

        RunStart.Plan plan = RunStart.from(form, "lab");

        assertFalse(plan.startable());
        assertTrue(plan.refusal().startsWith("Partial runs are not ported"), plan.refusal());
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
