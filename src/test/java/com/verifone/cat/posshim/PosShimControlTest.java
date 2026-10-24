package com.verifone.cat.posshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.posshim.ShimReplies.PendingKeyResult;
import com.verifone.cat.posshim.ShimReplies.ScanResult;

/**
 * The POS control routes (cancel, void, ENTER with data, scan) against {@link FakeShim} on loopback.
 * Evidence about this repository only: no key is pressed on any register. The reply bodies are the
 * shapes the Python client's mock tests use (tests/unit/test_pos_shim_with_mocks.py).
 */
class PosShimControlTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    void cancelLadderReportsTheShimsVerifiedSuccess() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"cleared\",\"success\":true}");

        assertTrue(client.cancelLadder());
        FakeShim.Seen sent = shim.seen.get(0);
        assertEquals("POST", sent.method());
        assertEquals("/pos/control/cancel", sent.pathAndQuery());
    }

    @Test
    void cancelLadderIsFalseWhenTheOverlaySurvived() {
        // A key the POS ignored must not read as a cleared screen.
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"stuck\",\"success\":false}");

        assertFalse(client.cancelLadder());
    }

    @Test
    void cancelLadderRefusesAReplyWithoutSuccess() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"outcome\":\"cleared\"}");

        DeviceProtocolError error = assertThrows(DeviceProtocolError.class, client::cancelLadder);
        assertTrue(error.getMessage().contains("has no 'success' field"), error.getMessage());
    }

    @Test
    void voidOpenTicketReportsSuccessOnTheVoidRoute() {
        shim.on("POST", "/pos/control/ticket/void", 200, "{\"outcome\":\"voided\",\"success\":true}");

        assertTrue(client.voidOpenTicket());
        assertEquals("/pos/control/ticket/void", shim.seen.get(0).pathAndQuery());
    }

    @Test
    void voidOpenTicketIsFalseWhenTheTicketIsStillOpen() {
        shim.on("POST", "/pos/control/ticket/void", 200, "{\"outcome\":\"open\",\"success\":false}");

        assertFalse(client.voidOpenTicket());
    }

    @Test
    void controlRoutesSendNoBody() {
        shim.on("POST", "/pos/control/cancel", 200, "{\"success\":true}");

        client.cancelLadder();

        assertEquals("{}", shim.seen.get(0).body());
    }

    @Test
    void enterWithDataSendsThePayloadAsAPlainField() {
        shim.on("POST", "/pos/keys/enter", 200, "{\"key\":\"ENTER\",\"processed\":true}");

        PendingKeyResult result = client.pressEnterWithData("123");

        assertTrue(result.processed());
        assertEquals("ENTER", result.key());
        FakeShim.Seen sent = shim.seen.get(0);
        assertEquals("/pos/keys/enter", sent.pathAndQuery());
        assertEquals("{\"data\":\"123\"}", sent.body());
    }

    @Test
    void enterWithDataRefusesAnEmptyPayloadBeforeSendingAnything() {
        assertThrows(IllegalArgumentException.class, () -> client.pressEnterWithData(""));
        assertThrows(IllegalArgumentException.class, () -> client.pressEnterWithData(null));
        assertTrue(shim.seen.isEmpty(), "no request may leave for an empty ENTER");
    }

    @Test
    void scanItemSendsTheScannerDataFields() throws Exception {
        shim.on("POST", "/pos/scan", 200, "{\"processed\":true}");

        ScanResult result = client.scanItem("8900000000000", 104);

        assertTrue(result.processed());
        Map<?, ?> sent = JSON.readValue(shim.seen.get(0).body(), Map.class);
        assertEquals(
                Map.of("scan_data", "8900000000000", "scan_data_type", 104, "decode_data", true,
                        "scan_data_label", "8900000000000", "status", 0),
                sent);
    }

    @Test
    void scanItemHonoursAnExplicitLabel() {
        shim.on("POST", "/pos/scan", 200, "{\"processed\":true}");

        client.scanItem("8900000000000", 104, true, "ITEM-8900000000000", 0);

        assertTrue(shim.seen.get(0).body().contains("\"scan_data_label\":\"ITEM-8900000000000\""));
    }

    @Test
    void aControlRouteAnsweredWithNoClientsIsUnreachableNotAProtocolFault() {
        shim.on("POST", "/pos/control/cancel", 503, "{\"error\":\"no-clients\",\"detail\":\"no POS connected\"}");

        assertThrows(DeviceUnreachable.class, client::cancelLadder);
    }

    @Test
    void a501OnAControlRouteIsUnavailable() {
        shim.on("POST", "/pos/control/printer/reopen", 501, "{\"error\":\"unsupported\",\"detail\":\"no source\"}");

        DeviceUnavailable error = assertThrows(DeviceUnavailable.class,
                () -> client.unsupported("POST", "/pos/control/printer/reopen", Map.of()));
        assertTrue(error.getMessage().contains("no source"), error.getMessage());
    }

    @Test
    void unsupportedRouteThatAnswers200IsAContractChange() {
        shim.on("POST", "/pos/control/serial/reset", 200, "{\"success\":true}");

        assertThrows(DeviceProtocolError.class,
                () -> client.unsupported("POST", "/pos/control/serial/reset", Map.of()));
    }

    @Test
    void aShimThatIsNotThereIsUnreachableForEveryControlRoute() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        PosShimClient nowhere = new PosShimClient(shim.host(), closedPort, null, TIMEOUT);

        assertThrows(DeviceUnreachable.class, nowhere::cancelLadder);
        assertThrows(DeviceUnreachable.class, nowhere::voidOpenTicket);
        assertThrows(DeviceUnreachable.class, () -> nowhere.pressEnterWithData("1"));
        assertThrows(DeviceUnreachable.class, () -> nowhere.scanItem("1", 104));
    }
}
