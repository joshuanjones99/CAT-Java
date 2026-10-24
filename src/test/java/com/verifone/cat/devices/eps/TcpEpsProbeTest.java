package com.verifone.cat.devices.eps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.rig.Rig;

/**
 * {@link TcpEpsProbe}, a bounded TCP connect, against a real loopback listener. A port of the Python CAT's
 * {@code tests/unit/test_devices_eps.py}. Not {@code eps_reachable}'s probe any more; see
 * {@link CommanderLinksTest} for that.
 */
class TcpEpsProbeTest {

    private static final Duration T = Duration.ofSeconds(5);
    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    @TempDir
    Path dir;

    @Test
    void aListeningPortIsReachable() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(new TcpEpsProbe(LOOPBACK, listener.getLocalPort()).reachable(T));
        }
    }

    @Test
    void aClosedPortIsNotReachable() throws IOException {
        int port;
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = listener.getLocalPort();
        }
        assertFalse(new TcpEpsProbe(LOOPBACK, port).reachable(T));
    }

    @Test
    void anUnresolvableHostIsNotReachable() {
        // .invalid is reserved by RFC 2606 and cannot resolve: the unresolved branch, not the timeout one.
        assertFalse(new TcpEpsProbe("no-such-host.invalid", 41107).reachable(T));
    }

    @Test
    void theProbeNeverWrites() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(new TcpEpsProbe(LOOPBACK, listener.getLocalPort()).reachable(T));
            listener.setSoTimeout(5000);
            try (Socket peer = listener.accept(); InputStream in = peer.getInputStream()) {
                peer.setSoTimeout(5000);
                assertEquals(-1, in.read(), "the probe sent a byte before closing");
            }
        }
    }

    @Test
    void fromRigPointsAtTheControllerWhereThePaymentHostListens() {
        Rig rig = TestRig.load(dir);
        TcpEpsProbe probe = TcpEpsProbe.fromRig(rig);
        assertEquals(rig.controller().host(), probe.host());
        assertNotEquals(rig.commander().host(), probe.host());
        assertEquals(rig.commander().epsPort(), probe.port());
        assertEquals(TestRig.CONTROLLER + ":41107", probe.describe());
    }
}
