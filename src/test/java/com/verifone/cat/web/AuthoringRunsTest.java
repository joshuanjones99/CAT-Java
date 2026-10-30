package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.authoring.Replay;
import com.verifone.cat.authoring.ReplayGate;
import com.verifone.cat.authoring.SessionException;
import com.verifone.cat.launch.LastConfig;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchDefaults;
import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.runconfig.ConfigFixture;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunStart;

/**
 * Review finding 4: an Authoring replay runs on the form's whole config (the Python's {@code replay_config}), not a bare
 * one, but never installs a build. Nothing is launched; evidence about this repository only.
 */
class AuthoringRunsTest {

    @TempDir
    Path tmp;

    private RunService service(String rig) {
        return new RunService(rig, () -> {
            throw new AssertionError("nothing is launched in this test");
        }, () -> "", Clock.systemDefaultZone());
    }

    @Test
    void theReplayBaseIsTheFormsWholeConfigWithUpgradeSkip() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp); // no plan ticked: a replay needs none
        RunService run = service("lab");
        LastConfig saved = LastConfig.of(Map.of("config-exec", "BDD", "config-tp-branch", "PETROCORE-1",
                "config-coverage", true));
        run.startContext(new RunStart.Context("lab", () -> saved, Map.of(), null, null, null)
                .withDownloads(() -> new LaunchDefaults.Downloads(UpgradeMode.NEW_INSTALL, "buypass", "Solutions",
                        "134", "", "master", "74")));

        LaunchConfig base = AuthoringRuns.over(run, form, tmp).baseConfig();

        assertEquals("lab", base.rig());
        assertEquals(UpgradeMode.SKIP, base.upgradeMode(), "a replay never installs a build");
        assertTrue(base.provisionRegister(), "as replay_config: provision_register=True");
        assertEquals("PETROCORE-1", base.testPlanBranch());
        assertEquals("BDD", base.executionMode());
        assertTrue(base.coverage());
        assertEquals("master", base.simBranch());
        assertEquals("74", base.simBuild());
        assertEquals(LaunchDefaults.defaultSimulators(), base.simulators());
        assertTrue(base.testPlans().isEmpty());

        Replay suite = new Replay("authored/s1", List.of(new Replay.ReplayCase("c1", "t", "d",
                tmp.resolve("PETROCORE-9-TestCase.feature").toString(), 9)), "", false);
        LaunchConfig replay = ReplayGate.replayConfig(base, suite);
        assertEquals(UpgradeMode.SKIP, replay.upgradeMode());
        assertTrue(replay.provisionRegister());
        assertEquals("PETROCORE-1", replay.testPlanBranch());
        assertEquals(base.simulators(), replay.simulators());
        assertEquals(suite.suites(), replay.suites());
        assertEquals(List.of("PETROCORE-9"), replay.selectionOnly());
        assertFalse(replay.snapshotUpdate());
    }

    @Test
    void noRigIsRefusedSayingWhy() throws IOException {
        ConfigForm form = ConfigFixture.form(tmp);
        SessionException e = assertThrows(SessionException.class,
                () -> AuthoringRuns.over(service(""), form, tmp).baseConfig());
        assertTrue(e.getMessage().contains("No rig is named"), e.getMessage());
    }
}
