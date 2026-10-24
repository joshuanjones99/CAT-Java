package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@link Endpoints} derived from a rig, as the Python CAT's {@code cat.connections.targets_for} derives
 * them. The rig names only {@code .invalid} hosts and loopback, so nothing here reaches a device.
 */
class EndpointsTest {

    @TempDir
    Path dir;

    private static final String FLEET = """
            name: fleet
            commander:
              host: commander.invalid
              ssh_user: root
              eps_port: 41107
            registers:
              - id: topaz101
                host: register1.invalid
                cdp_port: 9222
                printer_port: COM3
              - id: topaz102
                host: register2.invalid
            controller:
              host: 127.0.0.1
            pinpad: viperpay
            build:
              expected_version: "057.02"
            devices:
              pinpad_hardware:
                host: pinpad.invalid
                port: 22001
              Commander:
                host: other.invalid
                port: 22002
            """;

    private Rig fleet() throws IOException {
        Path file = dir.resolve("fleet.yaml");
        Files.writeString(file, FLEET);
        return RigLoader.loadFile(file).rig();
    }

    @Test
    void theFixedEndpointsComeFromTheRigsCommanderPrimaryRegisterAndController() throws IOException {
        Endpoints endpoints = Endpoints.of(fleet());

        assertEquals(new Endpoints("commander.invalid", "topaz101", "register1.invalid", "127.0.0.1"), endpoints);
        List<Target> fixed = endpoints.fixedTargets();
        assertEquals(List.of("Commander", "Register topaz101", "Host PC"),
                fixed.stream().map(Target::name).toList());
        assertEquals(Integer.valueOf(80), fixed.get(0).port());
        assertEquals(Integer.valueOf(22), fixed.get(1).port());
        assertEquals(Target.Kind.LOCAL, fixed.get(2).kind());
        assertTrue(fixed.stream().allMatch(Target::fixed));
    }

    @Test
    void theOptionalNamesAreTheExtraRegistersThenTheDeviceKeysInSortedOrder() throws IOException {
        assertEquals(List.of("topaz102", "Commander", "pinpad_hardware"), Endpoints.optionalTargetNames(fleet()));
    }

    @Test
    void withNothingSelectedOnlyTheFixedTargetsAreProbed() throws IOException {
        List<Target> targets = Endpoints.targetsFor(fleet(), List.of());

        assertEquals(3, targets.size());
        assertTrue(targets.stream().allMatch(Target::fixed));
    }

    @Test
    void aSelectedExtraRegisterAndDeviceAreRowsThatAreNotFixed() throws IOException {
        List<Target> targets = Endpoints.targetsFor(fleet(), List.of("topaz102", "pinpad_hardware"));

        assertEquals(List.of("Commander", "Register topaz101", "Host PC", "Register topaz102", "pinpad_hardware"),
                targets.stream().map(Target::name).toList());
        Target extra = targets.get(3);
        assertEquals("register2.invalid", extra.host());
        assertEquals(Integer.valueOf(22), extra.port());
        assertFalse(extra.fixed());
        Target device = targets.get(4);
        assertEquals("pinpad.invalid", device.host());
        assertEquals(Integer.valueOf(22001), device.port());
        assertFalse(device.fixed());
    }

    @Test
    void aSelectedNameTheRigDoesNotDeclareIsARowWithNoAddressThatReportsUnknown() throws IOException {
        List<Target> targets = Endpoints.targetsFor(fleet(), List.of("nothing-declared"));

        Target row = targets.get(targets.size() - 1);
        assertEquals("nothing-declared", row.name());
        assertEquals("", row.host());
        assertNull(row.port());
        assertFalse(row.fixed());
        ExecutorService pool = ConnectionProbe.newProbePool();
        try {
            ProbeResult result = new ConnectionProbe(pool).probe(row, Duration.ofSeconds(1));
            assertEquals(ProbeState.UNKNOWN, result.state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDeviceKeyThatCollidesWithAFixedRowIsOneRowAndTheFixedOneWins() throws IOException {
        List<Target> targets = Endpoints.targetsFor(fleet(), List.of("Commander"));

        List<Target> commander = targets.stream().filter(t -> t.name().equals("Commander")).toList();
        assertEquals(1, commander.size());
        assertEquals("commander.invalid", commander.get(0).host());
        assertTrue(commander.get(0).fixed());
    }
}
