package com.verifone.cat.posshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.posshim.ShimReplies.Health;
import com.verifone.cat.posshim.ShimReplies.KeyPressResult;
import com.verifone.cat.posshim.ShimReplies.Screen;
import com.verifone.cat.posshim.ShimReplies.SupportedKeys;

class PosShimClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private FakeShim shim;
    private PosShimClient client;

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        client = new PosShimClient(shim.host(), shim.port(), null, TIMEOUT);
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    @Test
    void healthParsesTheLabShimsReply() {
        shim.onFixture("GET", "/health", "health.json");

        Health health = client.health();

        assertTrue(health.ok());
        assertEquals(List.of("topaz101"), health.clients());
        assertEquals(List.of("topaz101"), health.drivableClients());
        assertTrue(health.statePresent());
        assertTrue(health.lastChangeAgeS().containsKey("topaz101"));
        assertTrue(health.drivableButSilent().isEmpty());
    }

    @Test
    void screenParsesOverlayExpressPanelAndTotals() {
        shim.onFixture("GET", "/pos/screen", "screen.json");

        Screen screen = client.screen();

        assertEquals("topaz101", screen.client());
        assertTrue(screen.statePresent());
        assertTrue(screen.overlayPresent());
        assertEquals("NO OPEN CASHIER SESSION. LOGGING OUT.", screen.overlay().line1());
        assertTrue(screen.overlay().autoHide());
        assertEquals(8, screen.expressPanel().size());
        assertEquals("Help", screen.expressPanel().get(1).label());
        assertEquals(204.98, screen.totals().get("total_due"), 0.001);
    }

    @Test
    void screenWithNoPushedStateHasNullPanels() {
        shim.onFixture("GET", "/pos/screen", "screen-no-state.json");

        Screen screen = client.screen();

        assertFalse(screen.statePresent());
        assertNull(screen.overlay());
        assertNull(screen.expressPanel());
        assertNull(screen.totals());
        assertNull(screen.lastChangeAgeS());
    }

    @Test
    void supportedKeysParses() {
        shim.onFixture("GET", "/pos/keys", "keys.json");

        SupportedKeys keys = client.supportedKeys();

        assertTrue(keys.namedKeys().contains("VOID_TICKET"));
        assertEquals(List.of("APPROVE"), keys.rejectedNames());
    }

    @Test
    void pressKeysPostsTheKeysAndReadsTheResult() {
        shim.on("POST", "/pos/keys", 200, """
                {"client":"topaz101","requested":["YES"],
                 "pressed":[{"key":"YES","processed":true,"elapsed_ms":231}],"all_processed":true}
                """);

        KeyPressResult result = client.pressKeys(List.of("YES"));

        assertTrue(result.allProcessed());
        assertEquals(231, result.pressed().get(0).elapsedMs());
        FakeShim.Seen sent = shim.seen.get(0);
        assertEquals("POST", sent.method());
        assertEquals("{\"keys\":[\"YES\"]}", sent.body());
    }

    @Test
    void namedClientIsSentAsAQueryParameter() {
        shim.onFixture("GET", "/pos/screen", "screen.json");
        PosShimClient named = new PosShimClient(shim.host(), shim.port(), "topaz101", TIMEOUT);

        named.screen();

        assertEquals("/pos/screen?client=topaz101", shim.seen.get(0).pathAndQuery());
    }

    @Test
    void unsupportedSurfaceIsUnavailableNotFalse() {
        shim.on("GET", "/health", 501, FakeShim.fixture("unsupported-printer.json"));

        DeviceUnavailable e = assertThrows(DeviceUnavailable.class, client::health);
        assertTrue(e.getMessage().contains("Printer port state"), e.getMessage());
    }

    @Test
    void posNotTalkingToTheShimIsUnreachable() {
        shim.on("GET", "/pos/screen", 503, "{\"error\":\"no-clients\",\"detail\":\"No POS has connected.\"}");

        DeviceUnreachable e = assertThrows(DeviceUnreachable.class, client::screen);
        assertTrue(e.getMessage().contains("No POS has connected."), e.getMessage());
    }

    @Test
    void contractDisagreementIsAProtocolError() {
        shim.on("POST", "/pos/keys", 400, "{\"error\":\"unknown-key\",\"detail\":\"APPROVE cannot be built\"}");

        DeviceProtocolError e = assertThrows(DeviceProtocolError.class, () -> client.pressKeys(List.of("APPROVE")));
        assertTrue(e.getMessage().contains("unknown-key"), e.getMessage());
    }

    @Test
    void nonJsonServerErrorKeepsTheStatus() {
        shim.on("GET", "/health", 500, "<html>proxy error</html>");

        DeviceError e = assertThrows(DeviceError.class, client::health);
        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertTrue(e.getMessage().contains("proxy error"), e.getMessage());
    }

    @Test
    void replyOfTheWrongShapeIsAProtocolError() {
        shim.on("GET", "/health", 200, "[1, 2, 3]");

        assertThrows(DeviceProtocolError.class, client::health);
    }

    @Test
    void noShimListeningIsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        PosShimClient nowhere = new PosShimClient(shim.host(), closedPort, null, TIMEOUT);

        assertThrows(DeviceUnreachable.class, nowhere::health);
    }
}
