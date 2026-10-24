package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Loopback only: a listening socket is "up", a closed port is "down". No device is contacted. */
class ConnectionProbeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ExecutorService pool;
    private ConnectionProbe probe;
    private ServerSocket listener;

    @BeforeEach
    void setUp() throws IOException {
        pool = ConnectionProbe.newProbePool();
        probe = new ConnectionProbe(pool);
        listener = new ServerSocket(0, 50, loopback());
    }

    @AfterEach
    void tearDown() throws IOException {
        listener.close();
        pool.shutdownNow();
    }

    static InetAddress loopback() {
        return InetAddress.getLoopbackAddress();
    }

    static String loopbackHost() {
        return loopback().getHostAddress();
    }

    /** A port that was just free: nothing listens on it, so a connect is refused. */
    static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, loopback())) {
            return probe.getLocalPort();
        }
    }

    @Test
    void aListeningPortIsUp() {
        ProbeResult result = probe.probe(Target.tcp("Commander", loopbackHost(), listener.getLocalPort()), TIMEOUT);

        assertEquals(ProbeState.UP, result.state());
        assertEquals("connected on port " + listener.getLocalPort(), result.detail());
    }

    @Test
    void aClosedPortIsDown() throws IOException {
        int port = closedPort();

        ProbeResult result = probe.probe(Target.tcp("Commander", loopbackHost(), port), TIMEOUT);

        assertEquals(ProbeState.DOWN, result.state());
        assertTrue(result.detail().startsWith("port " + port + ": "), result.detail());
    }

    @Test
    void aTargetWithNoPortIsUnknownNotDown() {
        ProbeResult result = probe.probe(Target.tcp("Optional", loopbackHost(), null), TIMEOUT);

        assertEquals(ProbeState.UNKNOWN, result.state());
        assertEquals("no address declared in the rig file", result.detail());
    }

    @Test
    void aPortOutOfRangeIsUnknownAndDoesNotEscape() {
        ProbeResult result = probe.probe(Target.tcp("Broken", loopbackHost(), 70_000), TIMEOUT);

        assertEquals(ProbeState.UNKNOWN, result.state());
        assertTrue(result.detail().startsWith("probe failed: "), result.detail());
    }

    @Test
    void aSweepKeepsOrderAndOneBadRowDoesNotTakeTheOthersDown() throws IOException {
        List<Target> targets = List.of(
                Target.tcp("Up", loopbackHost(), listener.getLocalPort()),
                Target.tcp("Down", loopbackHost(), closedPort()),
                Target.tcp("Undeclared", loopbackHost(), null),
                Target.tcp("Out of range", loopbackHost(), 70_000));

        List<ProbeResult> results = probe.probeAll(targets, TIMEOUT);

        assertEquals(List.of("Up", "Down", "Undeclared", "Out of range"),
                results.stream().map(ProbeResult::target).toList());
        assertEquals(List.of(ProbeState.UP, ProbeState.DOWN, ProbeState.UNKNOWN, ProbeState.UNKNOWN),
                results.stream().map(ProbeResult::state).toList());
    }

    @Test
    void aZeroTimeoutStillReturnsRatherThanBlocking() {
        // A zero connect timeout would mean "forever" to the socket; the floor keeps it bounded.
        long started = System.nanoTime();

        ProbeResult result = probe.probe(Target.tcp("Commander", loopbackHost(), listener.getLocalPort()), Duration.ZERO);

        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(10)) < 0);
        assertTrue(result.state() == ProbeState.UP || result.state() == ProbeState.DOWN, result.toString());
    }

    @Test
    void theFixedTargetsAreCommanderRegisterAndHostPcInThatOrder() {
        List<Target> fixed = new Endpoints("commander.test", "1", "register.test", "controller.test").fixedTargets();

        assertEquals(List.of("Commander", "Register 1", "Host PC"), fixed.stream().map(Target::name).toList());
        assertEquals(Integer.valueOf(Endpoints.COMMANDER_SERVICE_PORT), fixed.get(0).port());
        assertEquals(Integer.valueOf(Endpoints.REGISTER_SERVICE_PORT), fixed.get(1).port());
        assertEquals(Target.Kind.LOCAL, fixed.get(2).kind());
        assertEquals(null, fixed.get(2).port());
    }
}
