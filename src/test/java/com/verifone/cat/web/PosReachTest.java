package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * {@link PosReach#tcp}'s three answers, against loopback listeners and a {@code .invalid} name only. Evidence about
 * this repository, not about a register.
 */
class PosReachTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private final String loopback = InetAddress.getLoopbackAddress().getHostAddress();

    /** A loopback port nothing listens on: bound, then released. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void anOpenShimPortIsLive() throws IOException {
        try (ServerSocket shim = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(PosReach.State.LIVE,
                    PosReach.tcp(closedPort(), TIMEOUT).probe(loopback, shim.getLocalPort()));
        }
    }

    @Test
    void aRefusedShimPortIsARegisterWithNoShim() throws IOException {
        assertEquals(PosReach.State.NO_SHIM, PosReach.tcp(closedPort(), TIMEOUT).probe(loopback, closedPort()));
    }

    @Test
    void aHostThatAnswersNothingIsNotConnected() throws IOException {
        assertEquals(PosReach.State.NOT_CONNECTED,
                PosReach.tcp(closedPort(), TIMEOUT).probe("register.invalid", closedPort()));
    }
}
