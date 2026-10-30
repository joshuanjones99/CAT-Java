package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceUnreachable;

/**
 * CSSP, BTT's card server protocol, against a real loopback socket: the Python's {@code test_vats_parts.py} CSSP
 * tests, with its byte vectors. Evidence about this repository only.
 */
class CardServerTest {

    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    @Test
    void selectRequestIsVatsCollisFraming() {
        byte[] expected = concat(HexFormat.of().parseHex("0012EA10DF0102A100DF0308"),
                "C:\\x.xml".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected, CardServer.selectRequest("C:\\x.xml"));
    }

    @Test
    void selectRequestUsesTheLongLengthFormPast127Bytes() {
        String path = "C:\\" + "a".repeat(197); // 200 bytes
        byte[] request = CardServer.selectRequest(path);
        // length, EA, 81 D1 (209-byte body), DF0102A100, DF03, 81 C8 (200), path
        assertArrayEquals(HexFormat.of().parseHex("EA81D1"), Arrays.copyOfRange(request, 2, 5));
        assertArrayEquals(HexFormat.of().parseHex("DF0381C8"), Arrays.copyOfRange(request, 10, 14));
        assertEquals(request.length - 2, ((request[0] & 0xFF) << 8) | (request[1] & 0xFF));
    }

    @Test
    void theStartAndStopRequestsAreVatsCollisBytes() {
        assertEquals("0007EA05DF0102B100", HexFormat.of().withUpperCase().formatHex(CardServer.START));
        assertEquals("0007EA05DF0102C100", HexFormat.of().withUpperCase().formatHex(CardServer.STOP));
    }

    @Test
    void aCouplingErrorIsStoppedAndRetriedOnceAsVatsCollisDoes() throws IOException {
        try (CardServerStub server = new CardServerStub(List.of(0x99, 0x00, 0x00, 0x00), false);
                CardServer card = new CardServer(LOOPBACK, server.port())) {
            card.load("C:\\x.xml");
            List<String> tails = server.requests.stream()
                    .map(r -> HexFormat.of().formatHex(Arrays.copyOfRange(r, r.length - 2, r.length))).toList();
            assertEquals(List.of("6d6c", "c100", "6d6c", "b100"), tails); // "ml", STOP, "ml", START
        }
    }

    @Test
    void anInvalidImageIsNamed() throws IOException {
        try (CardServerStub server = new CardServerStub(List.of(0x14), false);
                CardServer card = new CardServer(LOOPBACK, server.port())) {
            CardServer.CardServerError e = assertThrows(CardServer.CardServerError.class,
                    () -> card.load("C:\\nope.xml"));
            assertTrue(e.getMessage().contains("invalid card image"), e.getMessage());
            assertTrue(e.getMessage().contains("SELECT nope.xml"), e.getMessage());
            assertTrue(e.getMessage().contains("(0x14)"), e.getMessage());
        }
    }

    @Test
    void noCardServerSaysWhatToStart() throws IOException {
        int free;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            free = socket.getLocalPort();
        }
        CardServer card = new CardServer(LOOPBACK, free, Duration.ofSeconds(1));
        DeviceUnreachable e = assertThrows(DeviceUnreachable.class, () -> card.load("C:\\x.xml"));
        assertTrue(e.getMessage().contains("card simulation server started"), e.getMessage());
    }

    @Test
    void aServerThatAcceptsAndNeverAnswersIsNotLiveAndSaysSoQuickly() throws IOException {
        try (CardServerStub silent = CardServerStub.silent()) {
            long started = System.nanoTime();
            assertFalse(new CardServer(LOOPBACK, silent.port(), Duration.ofMillis(500)).ping());
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
        }
    }

    @Test
    void aStopWithNothingLoadedSendsNothingAndALiveServerPings() throws IOException {
        try (CardServerStub server = CardServerStub.answering()) {
            CardServer card = new CardServer(LOOPBACK, server.port());
            card.stop();
            assertTrue(server.requests.isEmpty());
            assertTrue(card.ping());
            assertFalse(card.connected(), "a ping closes its connection");
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
