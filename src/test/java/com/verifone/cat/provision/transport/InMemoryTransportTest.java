package com.verifone.cat.provision.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;

import org.junit.jupiter.api.Test;

/** The in-process fake that consumers of {@link Transport} use in place of a box. */
class InMemoryTransportTest {

    private static final Duration T = Duration.ofSeconds(5);

    @Test
    void aWriteIsReadBackWithItsModeAndIsRecorded() {
        var box = new InMemoryTransport("topaz101");
        box.put("/home/topaz/lib/x.jar", "jar".getBytes(StandardCharsets.UTF_8), 0644, T);
        assertArrayEquals("jar".getBytes(StandardCharsets.UTF_8), box.get("/home/topaz/lib/x.jar", T).orElseThrow());
        assertEquals(Optional.of(0644), box.modeOf("/home/topaz/lib/x.jar"));
        assertEquals(java.util.List.of("/home/topaz/lib/x.jar"), box.writes());
    }

    @Test
    void anAbsentFileIsEmptyNotAnException() {
        var box = new InMemoryTransport();
        assertEquals(Optional.empty(), box.get("/nope", T));
        assertEquals(java.util.List.of("/nope"), box.reads());
    }

    @Test
    void aSilentlyDroppedWriteDoesNotLand() {
        var box = new InMemoryTransport().silentlyDrop("/a");
        box.put("/a", new byte[] {1}, 0600, T);
        assertEquals(Optional.empty(), box.get("/a", T));
        assertEquals(java.util.List.of("/a"), box.writes(), "the attempt is still recorded");
    }

    @Test
    void aFailedWriteThrowsADeviceError() {
        var box = new InMemoryTransport().failWritesTo("/a");
        assertThrows(DeviceError.class, () -> box.put("/a", new byte[] {1}, 0600, T));
    }

    @Test
    void aTimedOutReadIsUnreachable() {
        var box = new InMemoryTransport().failReadsOf("/a");
        assertThrows(DeviceUnreachable.class, () -> box.get("/a", T));
    }

    @Test
    void anUnsetCommandSucceedsWithNoOutputAndIsRecorded() {
        var box = new InMemoryTransport();
        CommandResult result = box.run("true", T);
        assertTrue(result.ok());
        assertEquals("", result.stdout());
        assertEquals(java.util.List.of("true"), box.ran());
    }

    @Test
    void aConfiguredCommandReturnsItsResult() {
        var box = new InMemoryTransport().command("setenforce 0", new CommandResult(1, "", "not applicable"));
        CommandResult result = box.run("setenforce 0", T);
        assertFalse(result.ok());
        assertEquals("not applicable", result.stderr());
    }

    @Test
    void mkdirAndExistsTrackDirectoriesAndFiles() {
        var box = new InMemoryTransport().withFile("/seed", new byte[0]);
        assertFalse(box.exists("/home/topaz", T));
        box.mkdir("/home/topaz", T);
        assertTrue(box.exists("/home/topaz", T));
        assertTrue(box.exists("/seed", T));
    }

    @Test
    void closeIsRecorded() {
        var box = new InMemoryTransport();
        assertFalse(box.closed());
        box.close();
        assertTrue(box.closed());
    }
}
