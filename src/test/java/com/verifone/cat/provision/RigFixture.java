package com.verifone.cat.provision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Rigs for the tests, loaded through {@link RigLoader} so the strict schema is the one checked. Hostnames are the
 * RFC 2606 {@code .invalid} names, which do not resolve: no test can reach a real box through them.
 */
final class RigFixture {

    /** A rig with one register, the shim, and a Commander key read from {@code PETROTEST_TEST_SSH_KEY}. */
    static final String RIG = """
            name: bench
            commander:
              host: commander.invalid
              ssh_user: root
              eps_port: 41107
              ssh_key_path_env: PETROTEST_TEST_SSH_KEY
            registers:
              - id: topaz101
                host: register.invalid
                cdp_port: 9222
            controller:
              host: controller.invalid
            pinpad: viperpay
            pos_shim: true
            build:
              expected_version: "057.02"
            """;

    /** The same rig with a second register that opted into a browser override. */
    static final String TWO_REGISTERS = RIG.replace("cdp_port: 9222\n", "cdp_port: 9222\n  - id: topaz102\n"
            + "    host: register2.invalid\n    browser_override: chromium\n");

    private RigFixture() {}

    static Rig load(Path dir, String yaml) {
        try {
            Files.writeString(dir.resolve("bench.yaml"), yaml);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return RigLoader.load("bench", dir);
    }
}
