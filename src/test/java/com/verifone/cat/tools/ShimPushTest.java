package com.verifone.cat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.PosShimBuild;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.setup.SetupItems;
import com.verifone.cat.sims.ToolsRoot;

/**
 * {@link ShimPush}'s checks before anything is reached, and the one register target it hands provision. Over a fake
 * provision, with the key and payload in a temporary folder; nothing here reaches a register. Evidence about this
 * repository only.
 */
class ShimPushTest {

    private static final RegisterTarget REG = new RegisterTarget("Reg101", "register.invalid", 8801, 9222);

    @TempDir
    Path dir;

    private final AtomicReference<TargetSpec> provisioned = new AtomicReference<>();

    private final ShimPush.Provision fake = (spec, options) -> {
        provisioned.set(spec);
        return new ProvisionReport(ShimPush.LABEL, false);
    };

    private Path key() throws IOException {
        Path key = dir.resolve("key.ppk");
        Files.writeString(key, "PuTTY-User-Key-File-2: ssh-rsa\n");
        return key;
    }

    private Path payload() throws IOException {
        Path pos = dir.resolve("payload").resolve("pos");
        Files.createDirectories(pos);
        for (String name : PosShimBuild.OUTPUTS) {
            Files.writeString(pos.resolve(name), "built");
        }
        return pos.getParent();
    }

    private Map<String, String> env(Path key, Path payload) {
        return Map.of(SetupItems.SSH_KEY_ENV, key.toString(), "CAT_PAYLOAD_ROOT", payload.toString());
    }

    @Test
    void theKeyIsTheEnvironmentsElseTheToolsRootsPpk() {
        assertEquals(Path.of("C:/keys/k.pem"), ShimPush.keyFile(Map.of(SetupItems.SSH_KEY_ENV, "C:/keys/k.pem")));
        assertEquals(dir.resolve(SetupItems.SSH_KEY_FILE),
                ShimPush.keyFile(Map.of(ToolsRoot.ENV, dir.toString())));
    }

    @Test
    void noKeyFailsWithNothingReached() throws IOException {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(dir.resolve("absent.ppk"), payload()), fake, say));

        assertNull(provisioned.get());
        String error = say.errLines().get(say.errLines().size() - 1);
        assertTrue(error.startsWith("no SSH key at"), error);
        assertTrue(error.endsWith("nothing was pushed"), error);
    }

    @Test
    void noBuiltPayloadFailsWithNothingReachedAndSaysWhatIsMissing() throws IOException {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(key(), dir.resolve("empty")), fake, say));

        assertNull(provisioned.get());
        String error = say.errLines().get(say.errLines().size() - 1);
        assertTrue(error.contains("no pos-shim payload in"), error);
        assertTrue(error.contains("petrotest-pos-shim.jar"), error);
        assertTrue(error.contains("build-pos-shim"), error);
    }

    @Test
    void theRegisterIsProvisionedAloneAsRootWithTheKey() throws IOException {
        Path key = key();
        Say.Recorded say = new Say.Recorded();

        assertTrue(ShimPush.push(REG, env(key, payload()), fake, say));

        TargetSpec spec = provisioned.get();
        assertEquals(new TargetSpec(TargetRole.REGISTER, "Reg101", "register.invalid", "root", 22, 8801,
                key.toString()), spec);
        assertTrue(say.outLines().get(0).contains("the Commander is not touched"), say.outLines().toString());
    }

    @Test
    void aProvisionThatThrowsIsAFailedPushNotACrash() throws IOException {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(key(), payload()), (spec, options) -> {
            throw new DeviceError("ssh: connection refused");
        }, say));

        assertEquals("provision: ssh: connection refused", say.errLines().get(say.errLines().size() - 1));
    }
}
