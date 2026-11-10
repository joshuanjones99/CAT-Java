package com.verifone.cat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.Artifact;
import com.verifone.cat.provision.PosShimBuild;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.TargetRole;
import com.verifone.cat.provision.TargetSpec;
import com.verifone.cat.setup.SetupItems;
import com.verifone.cat.sims.ToolsRoot;

/**
 * {@link ShimPush}'s checks before anything is reached, the build it runs when the payload lacks the shim, and the one
 * register target and artifact it hands provision. Over a fake build and a fake provision, with the key and payload in
 * a temporary folder; nothing here reaches a register or compiles. Evidence about this repository only.
 */
class ShimPushTest {

    private static final RegisterTarget REG = new RegisterTarget("Reg101", "register.invalid", 8801, 9222);

    @TempDir
    Path dir;

    private final AtomicReference<TargetSpec> provisioned = new AtomicReference<>();
    private final AtomicReference<ProvisionOptions> options = new AtomicReference<>();
    private final AtomicReference<PosShimBuild.Options> built = new AtomicReference<>();

    private final ShimPush.Provision fake = (spec, opts) -> {
        provisioned.set(spec);
        options.set(opts);
        return new ProvisionReport(ShimPush.LABEL, false);
    };

    /** A build that writes every output, as the real one does. */
    private final ShimPush.Build builds = (opts, say) -> {
        built.set(opts);
        write(opts.payload(), PosShimBuild.OUTPUTS);
    };

    private final ShimPush.Build mustNotBuild = (opts, say) -> {
        throw new AssertionError("the payload was built already");
    };

    private static void write(Path pos, List<String> names) {
        try {
            Files.createDirectories(pos);
            for (String name : names) {
                Files.writeString(pos.resolve(name), "built");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path key() throws IOException {
        Path key = dir.resolve("key.ppk");
        Files.writeString(key, "PuTTY-User-Key-File-2: ssh-rsa\n");
        return key;
    }

    private Path builtPayload() {
        Path root = dir.resolve("payload");
        write(root.resolve("pos"), PosShimBuild.OUTPUTS);
        return root;
    }

    private Map<String, String> env(Path key, Path payload) {
        return Map.of(SetupItems.SSH_KEY_ENV, key.toString(), "CAT_PAYLOAD_ROOT", payload.toString(),
                "CAT_BRIDGE_ROOT", dir.resolve("cat-java").toString(), "PETROAPPS_ROOT", dir.resolve("petroapps")
                        .toString(), ToolsRoot.ENV, dir.resolve("tools").toString());
    }

    private static String lastError(Say.Recorded say) {
        return say.errLines().get(say.errLines().size() - 1);
    }

    @Test
    void theKeyIsTheEnvironmentsElseTheToolsRootsPpk() {
        assertEquals(Path.of("C:/keys/k.pem"), ShimPush.keyFile(Map.of(SetupItems.SSH_KEY_ENV, "C:/keys/k.pem")));
        assertEquals(dir.resolve(SetupItems.SSH_KEY_FILE),
                ShimPush.keyFile(Map.of(ToolsRoot.ENV, dir.toString())));
    }

    @Test
    void noKeyFailsWithNothingBuiltOrReached() {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(dir.resolve("absent.ppk"), builtPayload()), mustNotBuild, fake, say));

        assertNull(provisioned.get());
        assertTrue(lastError(say).startsWith("no SSH key at"), lastError(say));
    }

    @Test
    void aPayloadWithoutTheShimIsBuiltFromCatJavasBridgeThenPushed() throws IOException {
        Path payload = dir.resolve("empty");
        Say.Recorded say = new Say.Recorded();

        assertTrue(ShimPush.push(REG, env(key(), payload), builds, fake, say), say.errLines().toString());

        PosShimBuild.Options build = built.get();
        assertEquals(dir.resolve("cat-java"), build.repoRoot());
        assertEquals(dir.resolve("petroapps"), build.petroapps());
        assertEquals(dir.resolve("tools").resolve("pos-shim-deps"), build.depsDir());
        assertEquals(payload.resolve("pos"), build.payload());
        assertFalse(build.check());
        assertTrue(ShimPush.missingPayload(payload).isEmpty());
        assertEquals("register.invalid", provisioned.get().host());
    }

    @Test
    void aBuiltPayloadIsPushedWithoutBuildingAgain() throws IOException {
        assertTrue(ShimPush.push(REG, env(key(), builtPayload()), mustNotBuild, fake, new Say.Recorded()));
    }

    @Test
    void aFailedBuildFailsThePushWithNothingReached() throws IOException {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(key(), dir.resolve("empty")), (opts, s) -> {
            throw new PosShimBuild.BuildError("no javac in this JRE");
        }, fake, say));

        assertNull(provisioned.get());
        assertEquals("could not build the pos-shim: no javac in this JRE; nothing was pushed", lastError(say));
    }

    @Test
    void aBuildThatLeavesAFileOutFailsThePush() throws IOException {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(key(), dir.resolve("empty")),
                (opts, s) -> write(opts.payload(), List.of("petrotest-pos-shim.jar")), fake, say));

        assertNull(provisioned.get());
        assertTrue(lastError(say).contains("left out automation-target-pos-topaz.jar"), lastError(say));
    }

    @Test
    void theRegisterAloneGetsTheShimsArtifactAloneAsRootWithTheKey() throws IOException {
        Path key = key();
        Say.Recorded say = new Say.Recorded();

        assertTrue(ShimPush.push(REG, env(key, builtPayload()), mustNotBuild, fake, say));

        assertEquals(new TargetSpec(TargetRole.REGISTER, "Reg101", "register.invalid", "root", 22, 8801,
                key.toString()), provisioned.get());
        ProvisionOptions opts = options.get();
        assertEquals(List.of("pos_rmi_agent"), opts.artifacts().stream().map(Artifact::name).toList());
        assertEquals(List.of(), opts.registerChecklist());
        assertFalse(opts.stage());
        assertFalse(opts.posShimChecklist().isEmpty());
        assertTrue(say.outLines().stream().anyMatch(l -> l.contains("the Commander is not touched")),
                say.outLines().toString());
    }

    @Test
    void aProvisionThatThrowsIsAFailedPushNotACrash() throws IOException {
        Say.Recorded say = new Say.Recorded();

        assertFalse(ShimPush.push(REG, env(key(), builtPayload()), mustNotBuild, (spec, opts) -> {
            throw new DeviceError("ssh: connection refused");
        }, say));

        assertEquals("provision: ssh: connection refused", lastError(say));
    }
}
