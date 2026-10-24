package com.verifone.cat.devices.eps;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * A rig file for the device tests, loaded through the real loader. Every host is an RFC 2606 {@code .invalid}
 * name, as the Python CAT's fixture rig: nothing here can reach a device. Test support only.
 */
public final class TestRig {

    public static final String COMMANDER = "commander.testrig.invalid";
    public static final String CONTROLLER = "controller.testrig.invalid";
    public static final String REGISTER = "topaz101.testrig.invalid";
    public static final int EPS_PORT = 41107;

    private TestRig() {}

    /** The fixture rig: no SSH key variable, pumps 1-2, no forecourt. */
    public static Rig load(Path dir) {
        return load(dir, "", "");
    }

    /**
     * @param keyEnvLine an {@code ssh_key_path_env: X} line for the commander block, or empty
     * @param extra      further top-level lines (for example {@code forecourt: petro}), or empty
     */
    public static Rig load(Path dir, String keyEnvLine, String extra) {
        return load(dir, keyEnvLine, extra, REGISTER);
    }

    /** As {@link #load(Path, String, String)}, with the register at {@code registerHost} (a loopback fake shim). */
    public static Rig load(Path dir, String keyEnvLine, String extra, String registerHost) {
        String yaml = """
                name: testrig
                commander:
                  host: %s
                  ssh_user: root
                  eps_port: %d
                %s
                registers:
                  - id: topaz101
                    host: %s
                controller:
                  host: %s
                pinpad: viperpay
                pos_shim: true
                pumps: [1, 2]
                build:
                  expected_version: "057.02"
                %s
                """.formatted(COMMANDER, EPS_PORT, keyEnvLine.isEmpty() ? "" : "  " + keyEnvLine, registerHost,
                CONTROLLER, extra);
        try {
            Path file = Files.createDirectories(dir).resolve("testrig.yaml");
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            return RigLoader.loadFile(file).rig();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
