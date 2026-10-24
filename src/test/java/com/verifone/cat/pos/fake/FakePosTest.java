package com.verifone.cat.pos.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pos.Locators;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fake POS's server, over HTTP with {@link HttpClient}: its loopback binding, its routes, and
 * its state machine. Ported from the state-machine and server tests of the Python's
 * {@code tests/unit/test_fake_pos.py}; the Playwright page-object cases are not ported (the
 * {@code PosDriver} is not in this port) and the browser case is in {@link FakePosBrowserTest}.
 *
 * <p>Evidence about this repository only. The fake POS is not a register.
 */
class FakePosTest {

    private static final int PAYMENT_GATEWAY = 145397;
    private static final int CASH = 145394;
    private static final int CREDIT = 145395;
    private static final int OTHER = 145398;
    private static final int TEST_ITEM = 9999;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger cashCalls = new AtomicInteger();
    private final AtomicInteger creditCalls = new AtomicInteger();
    private FakePos pos;

    @BeforeEach
    void open() throws IOException {
        pos = FakePos.open(new FakePos.Hooks(cashCalls::incrementAndGet, creditCalls::incrementAndGet));
    }

    @AfterEach
    void close() {
        pos.close();
    }

    // -- the server ------------------------------------------------------------------

    @Test
    void bindsTheLoopbackAddressOnly() throws IOException {
        InetAddress host = InetAddress.getByName(pos.url().getHost());
        assertTrue(host.isLoopbackAddress(), "the fake POS must bind loopback: " + host);
        assertTrue(pos.port() > 0);
    }

    @Test
    void servesThePageWithItsTitle() throws Exception {
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(page.body().contains("<title>Fake POS</title>"));
    }

    @Test
    void anUnknownPathIsNotFound() throws Exception {
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void anythingButGetIsRefused() throws Exception {
        HttpRequest post = HttpRequest.newBuilder(pos.url().resolve("/api/state"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aSelectWithoutANumberIsRefusedNotGuessed() throws Exception {
        assertEquals(400, get("/api/select?item=abc").statusCode());
    }

    @Test
    void aSelectWithNoItemParameterAddsItemZeroAsThePythonDoes() throws Exception {
        JsonNode state = json("/api/select");
        assertEquals(List.of(0), items(state));
    }

    // -- the state machine -----------------------------------------------------------

    @Test
    void startsIdleWithNoSale() throws Exception {
        JsonNode state = json("/api/state");
        assertFalse(state.get("is_transaction_active").asBoolean());
        assertEquals(List.of(), items(state));
        assertEquals("", state.get("receipt_html").asText());
    }

    @Test
    void theCsrButtonReadsCsrFuncWhenIdle() throws Exception {
        String csrId = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7).value();
        assertTrue(get("/").body().contains("<button id=\"" + csrId + "\" data-dynamic=\"true\">CSR Func</button>"));
    }

    @Test
    void selectingAnItemDoesNotChangeTheState() throws Exception {
        JsonNode state = json("/api/select?item=" + TEST_ITEM);
        assertFalse(state.get("is_transaction_active").asBoolean());
        assertEquals(List.of(TEST_ITEM), items(state));
    }

    @Test
    void paymentGatewayActivatesTheTransactionAndTheCsrButtonSaysErrorCorrect() throws Exception {
        JsonNode state = json("/api/mop?mop=" + PAYMENT_GATEWAY);
        assertTrue(state.get("is_transaction_active").asBoolean());
        String csrId = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7).value();
        assertTrue(get("/").body().contains(csrId + "\" data-dynamic=\"true\">Error Correct</button>"));
    }

    @Test
    void cashWhileActiveRunsTheCashHookAndReturnsToIdle() throws Exception {
        json("/api/mop?mop=" + PAYMENT_GATEWAY);
        JsonNode state = json("/api/mop?mop=" + CASH);
        assertEquals(1, cashCalls.get());
        assertEquals(0, creditCalls.get());
        assertFalse(state.get("is_transaction_active").asBoolean());
    }

    @Test
    void cashWhileIdleDoesNothing() throws Exception {
        JsonNode state = json("/api/mop?mop=" + CASH);
        assertEquals(0, cashCalls.get());
        assertFalse(state.get("is_transaction_active").asBoolean());
    }

    @Test
    void creditWhileActiveRunsTheCreditHookAndReturnsToIdle() throws Exception {
        json("/api/mop?mop=" + PAYMENT_GATEWAY);
        JsonNode state = json("/api/mop?mop=" + CREDIT);
        assertEquals(1, creditCalls.get());
        assertEquals(0, cashCalls.get());
        assertFalse(state.get("is_transaction_active").asBoolean());
    }

    @Test
    void otherEndsTheTransactionWithoutAHook() throws Exception {
        json("/api/mop?mop=" + PAYMENT_GATEWAY);
        JsonNode state = json("/api/mop?mop=" + OTHER);
        assertEquals(0, cashCalls.get());
        assertEquals(0, creditCalls.get());
        assertFalse(state.get("is_transaction_active").asBoolean());
    }

    @Test
    void itemsPersistThroughPaymentSoTheReceiptCanBeRead() throws Exception {
        json("/api/select?item=" + TEST_ITEM);
        json("/api/mop?mop=" + PAYMENT_GATEWAY);
        JsonNode state = json("/api/mop?mop=" + CASH);
        assertEquals(List.of(TEST_ITEM), items(state));
    }

    @Test
    void aMopIsNeverAPriceLine() throws Exception {
        json("/api/select?item=" + TEST_ITEM);
        json("/api/select?item=" + PAYMENT_GATEWAY);
        json("/api/select?item=" + CASH);
        json("/api/select?item=" + OTHER);
        String receipt = json("/api/state").get("receipt_html").asText();
        String descriptionId = Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.at(0).value();
        assertTrue(receipt.contains(descriptionId));
        assertFalse(receipt.contains(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.at(1).value()));
        assertTrue(receipt.contains("$12.99"));
    }

    @Test
    void theReceiptRouteServesTheSameLines() throws Exception {
        json("/api/select?item=" + TEST_ITEM);
        String fromRoute = get("/api/receipt").body();
        assertEquals(json("/api/state").get("receipt_html").asText(), fromRoute);
        assertTrue(fromRoute.contains("$12.99"));
    }

    // -- helpers ---------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(pos.url().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path);
        return JSON.readTree(response.body());
    }

    private static List<Integer> items(JsonNode state) {
        List<Integer> out = new ArrayList<>();
        state.get("selected_items").forEach(node -> out.add(node.asInt()));
        return out;
    }
}
