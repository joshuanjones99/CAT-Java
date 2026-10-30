package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.Say;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ProcessOps;

/**
 * The cloud tunnel's start (the Python's {@code cat.launch.tunnel} and its tests in {@code test_cloud_tunnel.py}):
 * adoption, a tunnel that comes up and is left running, one that exits 0 (not needed), one that fails, one that never
 * says "up". The child is a fake process that writes its log; nothing is spawned and no Commander is reached.
 */
class TunnelStartTest {

    @TempDir
    Path dir;

    /** A child that has written {@code text} to its log, alive or exited with {@code code}. */
    private static final class FakeProcess extends Process {
        boolean alive;
        final int code;
        boolean destroyed;

        FakeProcess(boolean alive, int code) {
            this.alive = alive;
            this.code = code;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return code;
        }

        @Override
        public int exitValue() {
            return code;
        }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private final class Seams implements TunnelStart.Seams {
        ProcessOps.Snapshot running = ProcessOps.Snapshot.ok("");
        String log = "";
        FakeProcess process = new FakeProcess(true, 0);
        final List<List<String>> spawned = new ArrayList<>();
        final List<String> refreshed = new ArrayList<>();
        long now;

        @Override
        public ProcessOps.Snapshot running() {
            return running;
        }

        @Override
        public Process spawn(List<String> argv, Path cwd, Path logPath) throws IOException {
            spawned.add(argv);
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, log, StandardCharsets.UTF_8);
            return process;
        }

        @Override
        public String refreshGateway(Rig rig) {
            refreshed.add("refresh");
            return "gateway not on: left alone";
        }

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void pause(Duration duration) {
            now += duration.toNanos();
        }
    }

    private Map<String, String> env() {
        return Map.of("CAT_RESULTS_DIR", dir.toString());
    }

    @Test
    void onlyAServingTunnelForThisRigIsAdopted() {
        assertTrue(TunnelStart.isTunnelFor("\"C:\\py\\python.exe\" -m petrotest.cli cloud-tunnel --rig lab", "lab"));
        assertTrue(TunnelStart.isTunnelFor("C:\\py\\Scripts\\petrotest.exe cloud-tunnel --rig=lab --force", "lab"));
        assertTrue(TunnelStart.isTunnelFor("\"C:\\jdk\\bin\\javaw.exe\" -cp a;b com.verifone.cat.Main cloud-tunnel "
                + "--rig lab", "lab"));
        assertFalse(TunnelStart.isTunnelFor("python.exe -m petrotest.cli cloud-tunnel --rig lab --stop", "lab"));
        assertFalse(TunnelStart.isTunnelFor("python.exe -m petrotest.cli cloud-tunnel --rig lab2", "lab"));
        assertFalse(TunnelStart.isTunnelFor("python.exe -m petrotest.cli commander-init --rig lab", "lab"));
    }

    @Test
    void theLogOutlivesTheRunUnderTheResultsRoot() {
        assertEquals(dir.resolve("cloud-tunnel").resolve("testrig.log"), TunnelStart.log(env(), "testrig"));
    }

    @Test
    void aTunnelThatComesUpIsLeftRunning() {
        Seams seams = new Seams();
        seams.log = "cloud-tunnel: profile qat\ncloud-tunnel: up (qat): mqtt-qat.example.invalid:8883\n";
        Say.Recorded say = new Say.Recorded();

        assertTrue(TunnelStart.start("testrig", null, env(), dir, seams, say));

        assertEquals(1, seams.spawned.size());
        List<String> argv = seams.spawned.get(0);
        assertEquals(List.of("com.verifone.cat.Main", "cloud-tunnel", "--rig", "testrig"),
                argv.subList(argv.size() - 4, argv.size()));
        assertFalse(argv.contains("--stop"));
        assertFalse(seams.process.destroyed, "left running");
        assertTrue(say.outLines().get(say.outLines().size() - 1).startsWith("cloud-tunnel: up (qat)"), say.outText());
    }

    @Test
    void aTunnelNotNeededExitsZeroAndPasses() {
        Seams seams = new Seams();
        seams.process = new FakeProcess(false, 0);
        seams.log = "cloud-tunnel: not needed - the Commander reaches mqtt.example.invalid:8883 itself";
        Say.Recorded say = new Say.Recorded();
        assertTrue(TunnelStart.start("testrig", null, env(), dir, seams, say));
        assertTrue(say.outText().contains("not needed"), say.outText());
    }

    @Test
    void aTunnelThatFailsFailsWithItsLastLine() {
        Seams seams = new Seams();
        seams.process = new FakeProcess(false, 1);
        seams.log = "cloud-tunnel: no cloud endpoints\n";
        Say.Recorded say = new Say.Recorded();
        assertFalse(TunnelStart.start("testrig", null, env(), dir, seams, say));
        assertEquals("cloud-tunnel: no cloud endpoints", say.lastErr());
    }

    @Test
    void aTunnelThatNeverSaysUpIsKilledAtTheDeadline() {
        Seams seams = new Seams();
        seams.log = "cloud-tunnel: reloading sshd";
        Say.Recorded say = new Say.Recorded();
        assertFalse(TunnelStart.start("testrig", null, env(), dir, seams, say));
        assertTrue(seams.process.destroyed);
        assertEquals("cloud-tunnel did not come up in 180 s", say.lastErr());
        assertFalse(say.outText().contains("reloading sshd"), "a partial line is not shown while it runs");
    }

    @Test
    void aRunningTunnelIsAdoptedAndItsGatewayRefreshed() {
        Seams seams = new Seams();
        seams.running = ProcessOps.Snapshot.ok("\"C:\\CAT\\.venv\\Scripts\\python.exe\" -m petrotest.cli cloud-tunnel "
                + "--rig testrig\n");
        Say.Recorded say = new Say.Recorded();
        assertTrue(TunnelStart.start("testrig", null, env(), dir, seams, say));
        assertEquals(List.of(), seams.spawned);
        assertEquals(List.of("refresh"), seams.refreshed);
        assertEquals(List.of("cloud-tunnel already running for testrig: adopted, left running",
                "cloud-tunnel: gateway not on: left alone"), say.outLines());
    }

    @Test
    void aProbeThatCannotTellIsSaidAndATunnelStarted() {
        Seams seams = new Seams();
        seams.running = ProcessOps.Snapshot.failed("powershell timed out");
        seams.log = "cloud-tunnel: up (prod)\n";
        Say.Recorded say = new Say.Recorded();
        assertTrue(TunnelStart.start("testrig", null, env(), dir, seams, say));
        assertTrue(say.outLines().get(0).startsWith("could not check for a running cloud-tunnel (powershell timed "
                + "out)"), say.outText());
        assertEquals(1, seams.spawned.size());
    }
}
