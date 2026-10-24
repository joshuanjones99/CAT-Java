package com.verifone.cat.provision.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The targets {@code provision} signs in to, resolved from a rig file and a fake environment. */
class SshTargetTest {

    private static final String KEY_ENV = "PROVISION_TEST_SSH_KEY";

    @TempDir
    Path tmp;

    private Rig rig(String keyLine) throws Exception {
        Path file = tmp.resolve("sshtest.yaml");
        Files.writeString(file, """
                name: sshtest
                commander:
                  host: commander.invalid
                  ssh_user: root
                  eps_port: 41107
                %s
                registers:
                  - id: topaz101
                    host: register.invalid
                controller:
                  host: 127.0.0.1
                pinpad: viperpay
                build:
                  expected_version: "057.02"
                """.formatted(keyLine));
        LoadedRig loaded = RigLoader.loadFile(file);
        return loaded.rig();
    }

    @Test
    void theCommanderKeyIsReadFromTheNamedVariableNotTheRigFile() throws Exception {
        Rig rig = rig("  ssh_key_path_env: " + KEY_ENV);
        var env = Map.of(KEY_ENV, "C:\\keys\\from-env.pem");
        SshTarget commander = SshTarget.commander(rig, env);
        assertEquals("commander", commander.name());
        assertEquals("commander.invalid", commander.host());
        assertEquals("root", commander.user());
        assertEquals(22, commander.port());
        assertEquals("C:\\keys\\from-env.pem", commander.keyFilename());
    }

    @Test
    void anUnsetKeyVariableGivesNoKeyFileNotAnError() throws Exception {
        Rig rig = rig("  ssh_key_path_env: " + KEY_ENV);
        assertNull(SshTarget.commander(rig, Map.of()).keyFilename());
    }

    @Test
    void aRigWithNoKeyVariableNamedGivesNoKeyFile() throws Exception {
        Rig rig = rig("");
        assertNull(SshTarget.commander(rig, Map.of(KEY_ENV, "C:\\keys\\ignored.pem")).keyFilename());
    }

    @Test
    void aRegisterTakesTheCommandersAccountAndKeyAsPythonDoes() throws Exception {
        Rig rig = rig("  ssh_key_path_env: " + KEY_ENV);
        var env = Map.of(KEY_ENV, "C:\\keys\\shared.pem");
        SshTarget register = SshTarget.register(rig, rig.register("topaz101"), env);
        assertEquals("topaz101", register.name());
        assertEquals("register.invalid", register.host());
        assertEquals("root", register.user());
        assertEquals(22, register.port());
        assertEquals("C:\\keys\\shared.pem", register.keyFilename());
    }

    @Test
    void aTargetWithNoUserOrHostIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SshTarget("x", "h", 22, "", null));
        assertThrows(IllegalArgumentException.class, () -> new SshTarget("x", "", 22, "root", null));
        assertThrows(IllegalArgumentException.class, () -> new SshTarget("x", "h", 0, "root", null));
    }
}
