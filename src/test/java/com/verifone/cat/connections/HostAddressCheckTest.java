package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;

import org.junit.jupiter.api.Test;

/** The Host PC row, against addresses this machine has by construction and one it cannot have. */
class HostAddressCheckTest {

    @Test
    void theLoopbackAddressIsHeldHere() {
        ProbeResult result = HostAddressCheck.check(Target.local("Host PC", ConnectionProbeTest.loopbackHost()));

        assertEquals(ProbeState.UP, result.state());
        assertEquals(Target.Kind.LOCAL, result.kind());
    }

    @Test
    void anAddressNoAdapterHoldsIsDownNotUnknown() throws IOException {
        // TEST-NET-1 (RFC 5737), a documentation range: no adapter on any machine holds it.
        String notHeld = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1}).getHostAddress();

        ProbeResult result = HostAddressCheck.check(Target.local("Host PC", notHeld));

        assertEquals(ProbeState.DOWN, result.state());
        assertTrue(result.detail().contains("not the controller the rig file names"), result.detail());
    }

    @Test
    void aWildcardAddressIsUnknownBecauseEveryMachineHoldsIt() throws IOException {
        String wildcard = InetAddress.getByAddress(new byte[4]).getHostAddress();

        ProbeResult result = HostAddressCheck.check(Target.local("Host PC", wildcard));

        assertEquals(ProbeState.UNKNOWN, result.state());
        assertTrue(result.detail().contains("wildcard"), result.detail());
    }

    @Test
    void aMissingHostIsUnknown() {
        assertEquals(ProbeState.UNKNOWN, HostAddressCheck.check(Target.local("Host PC", "  ")).state());
    }
}
