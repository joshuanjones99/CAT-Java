package com.verifone.cat.rig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RigLoaderTest {

    // Shaped like the Python CAT's rigs/example.yaml, documentation addresses included. The schema is
    // strict now (as the Python one is), so the required keys pinpad:, build: and the commander's
    // ssh_user: and eps_port: are present; an unknown key would fail the load.
    private static final String RIG = """
            name: bench
            commander:
              host: 10.0.0.11
              ssh_user: root
              eps_port: 41107
            registers:
              - id: topaz101
                host: 10.0.0.101
                cdp_port: 9222
              - id: topaz102
                host: 10.0.0.102
            controller:
              host: 10.0.0.160
            pinpad: viperpay
            pos_shim: true
            pumps: [1, 2, 3]
            build:
              expected_version: "057.02"
            """;

    private static final String PLAIN = """
            name: plain
            commander: {host: 10.0.0.11, ssh_user: root, eps_port: 41107}
            registers:
              - {id: r1, host: 10.0.0.101}
            controller: {host: 10.0.0.160}
            pinpad: viperpay
            build: {expected_version: "057.02"}
            """;

    @TempDir
    Path dir;

    @Test
    void loadsANamedRigFromTheRigsDirectory() throws IOException {
        Files.writeString(dir.resolve("bench.yaml"), RIG);

        Rig rig = RigLoader.load("bench", dir);

        assertEquals("bench", rig.name());
        assertTrue(rig.posShim());
        assertEquals("10.0.0.101", rig.register(null).host());
        assertEquals("10.0.0.102", rig.register("topaz102").host());
    }

    @Test
    void loadsARigFileByPath() throws IOException {
        Path file = dir.resolve("elsewhere.yaml");
        Files.writeString(file, RIG);

        assertEquals("bench", RigLoader.load(file.toString(), Path.of("unused")).name());
    }

    @Test
    void posShimDefaultsToFalse() throws IOException {
        Files.writeString(dir.resolve("plain.yaml"), PLAIN);

        assertFalse(RigLoader.load("plain", dir).posShim());
    }

    @Test
    void unknownRegisterNamesTheOnesThereAre() throws IOException {
        Files.writeString(dir.resolve("bench.yaml"), RIG);
        Rig rig = RigLoader.load("bench", dir);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> rig.register("topaz109"));
        assertTrue(e.getMessage().contains("[topaz101, topaz102]"), e.getMessage());
    }

    @Test
    void missingRigSaysWhereItLooked() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RigLoader.load("nope", dir));
        assertTrue(e.getMessage().contains("PETROTEST_RIGS_DIR"), e.getMessage());
    }

    @Test
    void rigWithoutRegistersIsRefused() throws IOException {
        Files.writeString(dir.resolve("empty.yaml"), "name: empty\nregisters: []\n");

        assertThrows(IllegalArgumentException.class, () -> RigLoader.load("empty", dir));
    }

    @Test
    void rigsDirComesFromTheEnvironmentWhenSet() {
        assertEquals(Path.of("D:\\rigs"), RigLoader.rigsDir(Map.of("PETROTEST_RIGS_DIR", "D:\\rigs")));
        assertEquals(RigLoader.DEFAULT_RIGS_DIR, RigLoader.rigsDir(Map.of()));
    }
}
