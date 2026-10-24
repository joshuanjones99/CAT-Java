package com.verifone.cat.connections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The System Checks network lines, with a fake CGILink on loopback. The fake answers the way the
 * Python CAT's tests do: a healthy Commander with a parse error, a broken one with the AAA fault,
 * and a Tomcat still starting. None of it is the lab Commander.
 */
class RigReadinessTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private ExecutorService pool;
    private HttpServer cgi;

    @BeforeEach
    void setUp() {
        pool = ConnectionProbe.newProbePool();
    }

    @AfterEach
    void tearDown() {
        if (cgi != null) {
            cgi.stop(0);
        }
        pool.shutdownNow();
    }

    /** A fake CGILink that answers every request with {@code status} and {@code body}. */
    private int serveCgi(int status, String body) throws IOException {
        cgi = HttpServer.create(new InetSocketAddress(ConnectionProbeTest.loopback(), 0), 0);
        cgi.createContext("/cgi-bin/CGILink", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        cgi.start();
        return cgi.getAddress().getPort();
    }

    private RigReadiness readiness(int commanderPort) {
        return new RigReadiness(new ConnectionProbe(pool), commanderPort);
    }

    private static Endpoints endpoints(String commanderHost) {
        return new Endpoints(commanderHost, "1", ConnectionProbeTest.loopbackHost(), ConnectionProbeTest.loopbackHost());
    }

    @Test
    void aDeadCommanderPortFailsTheReachableLineWithTheCommanderHint() throws IOException {
        int closed = ConnectionProbeTest.closedPort();

        List<Readiness> lines = readiness(closed).check(endpoints(ConnectionProbeTest.loopbackHost()), TIMEOUT);

        assertEquals(List.of("controller rig adapter", "commander not reachable"),
                lines.stream().map(Readiness::label).toList());
        assertTrue(lines.get(0).ok());
        assertFalse(lines.get(1).ok());
        assertTrue(lines.get(1).required());
        assertTrue(lines.get(1).detail().endsWith(RigReadiness.COMMANDER_HINT), lines.get(1).detail());
    }

    @Test
    void aHealthyCommanderAnswersWithItsOwnParseError() throws IOException {
        int port = serveCgi(200, "<Response><ParseError>Invalid root element</ParseError></Response>");

        List<Readiness> lines = readiness(port).check(endpoints(ConnectionProbeTest.loopbackHost()), TIMEOUT);

        assertEquals("commander answers", lines.get(lines.size() - 1).label());
        assertTrue(lines.get(lines.size() - 1).ok());
    }

    @Test
    void aCommanderWithADeadRmiTierIsNamedNotRepaired() throws IOException {
        int port = serveCgi(200, "<Response>" + RigReadiness.AAA_DOWN_FAULT + "</Response>");

        List<Readiness> lines = readiness(port).check(endpoints(ConnectionProbeTest.loopbackHost()), TIMEOUT);

        Readiness answer = lines.get(lines.size() - 1);
        assertEquals("commander not answering", answer.label());
        assertFalse(answer.ok());
        assertTrue(answer.detail().contains(RigReadiness.AAA_DOWN_HINT), answer.detail());
    }

    @Test
    void aCommanderStillStartingAnswers503AndFails() throws IOException {
        int port = serveCgi(503, "");

        Readiness answer = readiness(port).commanderAnswers(ConnectionProbeTest.loopbackHost(), port, TIMEOUT);

        assertFalse(answer.ok());
        assertTrue(answer.detail().contains("503"), answer.detail());
    }

    @Test
    void aControllerAddressNotHeldHereFailsTheAdapterLineAsRequired() throws IOException {
        String notHeld = java.net.InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1}).getHostAddress();
        Endpoints endpoints = new Endpoints(ConnectionProbeTest.loopbackHost(), "1",
                ConnectionProbeTest.loopbackHost(), notHeld);

        List<Readiness> lines = readiness(ConnectionProbeTest.closedPort()).check(endpoints, TIMEOUT);

        Readiness adapter = lines.get(0);
        assertFalse(adapter.ok());
        assertTrue(adapter.required());
        assertTrue(adapter.detail().endsWith(RigReadiness.ADAPTER_HINT), adapter.detail());
        assertEquals("commander not reachable", lines.get(1).label());
        assertTrue(lines.get(1).detail().endsWith(RigReadiness.ADAPTER_HINT), lines.get(1).detail());
    }
}
