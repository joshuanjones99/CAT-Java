package com.verifone.cat.cloudtunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What a run adopts or starts ({@link CloudTunnelProcess}): the Python {@code cat.launch.tunnel}'s rules, ported with
 * its tests' cases ({@code test_only_a_serving_tunnel_for_this_rig_is_adopted}) plus this port's own command lines.
 */
class CloudTunnelProcessTest {

    @Test
    void onlyAServingTunnelForThisRigIsAdopted() {
        // The Python's own table.
        assertTrue(CloudTunnelProcess.isTunnelFor("\"C:\\py\\python.exe\" -m petrotest.cli cloud-tunnel --rig lab", "lab"));
        assertTrue(CloudTunnelProcess.isTunnelFor("C:\\py\\Scripts\\petrotest.exe cloud-tunnel --rig=lab --force", "lab"));
        assertEquals(false, CloudTunnelProcess.isTunnelFor(
                "\"C:\\py\\python.exe\" -m petrotest.cli cloud-tunnel --rig lab --stop", "lab"));
        assertEquals(false, CloudTunnelProcess.isTunnelFor(
                "\"C:\\py\\python.exe\" -m petrotest.cli cloud-tunnel --rig lab2", "lab"));
        assertEquals(false, CloudTunnelProcess.isTunnelFor(
                "\"C:\\py\\python.exe\" -m petrotest.cli commander-init --rig lab", "lab"));
        // This port's: the spawned command line, run.cmd's, and the jar's.
        String spawned = String.join(" ", CloudTunnelProcess.argv("C:\\jdk\\bin\\javaw.exe",
                "target\\classes;target\\lib\\*", "lab"));
        assertTrue(CloudTunnelProcess.isTunnelFor(spawned, "lab"), spawned);
        assertTrue(CloudTunnelProcess.isTunnelFor(
                "\"C:\\jdk\\bin\\java.exe\" -jar target\\cat-java.jar cloud-tunnel --rig lab --no-gateway", "lab"));
        assertEquals(false, CloudTunnelProcess.isTunnelFor(spawned, "other"));
    }

    @Test
    void theSpawnedCommandLineRunsThisBuildsMainWithNoSwitch() {
        assertEquals(List.of("java", "-cp", "cp", "com.verifone.cat.Main", "cloud-tunnel", "--rig", "lab"),
                CloudTunnelProcess.argv("java", "cp", "lab"));
    }

    @Test
    void theLogIsUnderTheResultsRootNotARunFolder() {
        Path root = Path.of("results");
        assertEquals(root.resolve("cloud-tunnel").resolve("lab.log"), CloudTunnelProcess.logPath(root, "lab"));
    }

    @Test
    void theStartBudgetAndTheUpLineAreThePythons() {
        assertEquals(Duration.ofSeconds(180), CloudTunnelProcess.START_TIMEOUT);
        assertEquals(Duration.ofMillis(500), CloudTunnelProcess.POLL);
        assertTrue(CloudTunnelServerTest.QAT_UP.startsWith("up ("));
        assertTrue(("cloud-tunnel: " + CloudTunnelServerTest.QAT_UP).startsWith(CloudTunnelProcess.UP_LINE));
        assertTrue(CloudTunnelProcess.EXES.containsAll(List.of("java.exe", "javaw.exe", "python.exe", "petrotest.exe")));
    }
}
