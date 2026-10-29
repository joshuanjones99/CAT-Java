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
        expected.put("commander-prep", com.verifone.cat.commander.CommanderPrepCommand.class);
        expected.put("commander-init", com.verifone.cat.commander.CommanderInitCommand.class);
        expected.put("payment-links", com.verifone.cat.commander.links.PaymentLinksCommand.class);
        expected.put("register-app", com.verifone.cat.commander.registerapp.RegisterAppCommand.class);
        expected.put("payment-ready", com.verifone.cat.commander.PaymentReadyCommand.class);
        expected.put("commander-trust", com.verifone.cat.commander.CommanderTrustCommand.class);
        expected.put("secondary-fep", com.verifone.cat.commander.SecondaryFepCli.class);
        expected.put("csite-reset", com.verifone.cat.commander.connect.CSiteReset.class);
        expected.put("cloud-snapshot", com.verifone.cat.commander.connect.CloudSnapshotCommand.class);
        expected.put("build-pos-shim", com.verifone.cat.provision.PosShimBuildCommand.class);
        expected.put("install-resume", com.verifone.cat.launch.InstallResume.class);
        expected.put("upgrade", com.verifone.cat.upgrade.UpgradeCommand.class);
        expected.put("build", com.verifone.cat.build.BuildCommand.class);
        expected.put("builds", com.verifone.cat.build.BuildsCommand.class);
        expected.put("artifactory-login", com.verifone.cat.build.ArtifactoryLogin.class);
        expected.put("render-payload", ProvisionCommand.class);

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
