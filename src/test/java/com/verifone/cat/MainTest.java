package com.verifone.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.verifone.cat.closeday.CloseDayCommand;
import com.verifone.cat.cloudtunnel.CloudTunnelCommand;
import com.verifone.cat.commander.connect.CommanderConnectCommand;
import com.verifone.cat.commander.snapshot.CommanderSnapshotCommand;
import com.verifone.cat.launch.LaunchCommand;
import com.verifone.cat.preflight.PreflightCommand;
import com.verifone.cat.provision.ProvisionCommand;
import com.verifone.cat.upgrade.PsiInitCommand;
import com.verifone.cat.commander.emv.EmvKeysCommand;
import com.verifone.cat.xray.XraySidecar;

/** Main's dispatch table: each command word maps to its class. Nothing is run. */
class MainTest {

    @Test
    void eachWordMapsToItsCommandClass() {
        Map<String, Class<?>> expected = new LinkedHashMap<>();
        expected.put("preflight", PreflightCommand.class);
        expected.put("launch", LaunchCommand.class);
        expected.put("provision", ProvisionCommand.class);
        expected.put("xray", XraySidecar.class);
        expected.put("close-day", CloseDayCommand.class);
        expected.put("commander-snapshot", CommanderSnapshotCommand.class);
        expected.put("commander-connect", CommanderConnectCommand.class);
        expected.put("cloud-tunnel", CloudTunnelCommand.class);
        expected.put("psi-init", PsiInitCommand.class);
        expected.put("emv-keys", EmvKeysCommand.class);
        expected.put("coverage", com.verifone.cat.coverage.CoverageCommand.class);
        expected.put("xray-attach", com.verifone.cat.xray.Attach.class);
        expected.put("upgrade", com.verifone.cat.upgrade.UpgradeCommand.class);
        expected.put("build", com.verifone.cat.build.BuildCommand.class);
        expected.put("builds", com.verifone.cat.build.BuildsCommand.class);
        expected.put("artifactory-login", com.verifone.cat.build.ArtifactoryLogin.class);
        expected.put("render-payload", ProvisionCommand.class);
        expected.put("fetch-sims", com.verifone.cat.sims.FetchSimsCommand.class);
        expected.put("record", com.verifone.cat.recorder.RecordCommand.class);
        expected.put("generate", com.verifone.cat.recorder.GenerateCommand.class);
        expected.put("steps", com.verifone.cat.authoring.StepsCommand.class);
        expected.put("authoring", com.verifone.cat.authoring.AuthoringCommand.class);
        expected.put("publish", com.verifone.cat.publish.PublishCommand.class);

        assertEquals(List.copyOf(expected.keySet()), List.copyOf(Main.COMMANDS.keySet()));
        expected.forEach((word, type) -> {
            Main.Command command = Main.COMMANDS.get(word);
            assertEquals(type, command.type(), word);
            assertNotNull(command.run(), word);
        });
    }

    @Test
    void everyCommandClassHasARunOfStringArray() throws NoSuchMethodException {
        for (Main.Command command : Main.COMMANDS.values()) {
            assertEquals(int.class, command.type().getMethod("run", String[].class).getReturnType(),
                    command.type().getName());
        }
    }

    @Test
    void webIsNotACommandWordSoItReachesTheWebUi() {
        assertTrue(!Main.COMMANDS.containsKey("web"));
    }
}
