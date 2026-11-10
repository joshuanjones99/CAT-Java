package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.BoundingBox;
import com.verifone.cat.core.Json;
import com.verifone.cat.pos.driver.PlaywrightHome;
import com.verifone.cat.pos.inspect.LocalDevTools;
import com.verifone.cat.posshim.PosShimClient;

import io.javalin.Javalin;

/**
 * The POS page and its screen socket, driven over HTTP and a WebSocket. The register is played by a local headless
 * Chromium ({@link LocalDevTools}), skipped without one. Evidence about this repository only.
 */
class WebAppTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newHttpClient();
    private Javalin app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private void start(String cdpUrl) {
        // The POS page never calls the shim: port 1 on loopback stands in for it.
        PosShimClient shim = new PosShimClient("localhost", 1, null, Duration.ofSeconds(1));
        app = WebApp.createWithScreen(shim, "CAT-Java: test / topaz101", cdpUrl).start(WebApp.BIND_HOST, 0);
    }

    @Test
    void thePageIsTheRegistersScreenAndNothingElse() throws Exception {
        start("http://register.invalid:19220");

        String page = get("/pos").body();

        assertTrue(page.contains("<canvas id=\"pos-screen\""), page);
        assertTrue(page.contains("data-socket=\"" + PosScreenRoutes.PATH + "\""), page);
        assertTrue(page.contains("/static/pos-screen.js"), page);
        // One big picture: the bare layout, with no description and no card around it.
        assertTrue(page.contains("<body class=\"app bare\""), page);
        assertFalse(page.contains("CAT-Java: test / topaz101"), page);
        assertFalse(page.contains("class=\"card"), page);
        assertFalse(page.contains("/fragments/health"), page);
        assertFalse(page.contains("/fragments/keys"), page);
        assertEquals(200, get("/static/pos-screen.js").statusCode());
        assertEquals(404, get("/fragments/screen").statusCode(), "the pos-shim cards are gone");
    }

    @Test
    void withNoRegisterThePageSaysSoAndOpensNothing() throws Exception {
        start(null);

        String page = get("/pos").body();

        assertTrue(page.contains("Register not connected"), page);
        assertFalse(page.contains("<canvas"), page);
    }

    @Test
    void withNoRegisterTheSocketSaysWhy() throws Exception {
        start(null);
        Socket socket = Socket.open(socketUri(), null);

        JsonNode error = socket.next("error");
        assertTrue(error.path("text").asText().contains("--rig"), error.toString());
        socket.close();
    }

    @Test
    void anotherSiteCannotOpenTheScreen() {
        start("http://register.invalid:19220");

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> Socket.open(socketUri(), "http://elsewhere.invalid"));

        assertTrue(refused.getCause() instanceof WebSocketHandshakeException, refused.toString());
        assertEquals(403, ((WebSocketHandshakeException) refused.getCause()).getResponse().statusCode());
    }

    @Test
    void theScreenArrivesAndAClickOnItReachesTheRegistersPage() throws Exception {
        try (LocalDevTools chromium = LocalDevTools.openOrNull()) {
            Assumptions.assumeTrue(chromium != null,
                    "Chromium is not installed for Playwright; skipping. Install it with: " + LocalDevTools.INSTALL_COMMAND);
            start(chromium.cdpUrl());
            Socket socket = Socket.open(socketUri(), "http://" + WebApp.BIND_HOST + ":" + app.port());

            JsonNode frame = socket.next("frame");
            assertFalse(frame.path("data").asText().isEmpty());
            assertTrue(frame.path("w").asDouble() > 0, frame.path("w").toString());
            assertEquals("screen test", socket.next("attached").path("title").asText());

            BoundingBox button = chromium.page().locator("#press").boundingBox();
            double x = button.x + button.width / 2;
            double y = button.y + button.height / 2;
            socket.send("{\"t\":\"mouse\",\"type\":\"mousePressed\",\"x\":" + x + ",\"y\":" + y
                    + ",\"button\":\"left\",\"buttons\":1,\"clickCount\":1}");
            socket.send("{\"t\":\"mouse\",\"type\":\"mouseReleased\",\"x\":" + x + ",\"y\":" + y
                    + ",\"button\":\"left\",\"buttons\":0,\"clickCount\":1}");
            socket.send("not json"); // dropped
            chromium.page().waitForFunction("() => document.querySelector('#press').dataset.n === '1'");
            socket.close();
        }
    }

    /**
     * The page's own script, in a second Chromium (the operator's window, with no DevTools port, so it is never the
     * one shown): the screen draws on its own, and a click and typing on the canvas land on the register's page.
     */
    @Test
    void theOperatorsClickAndTypingOnTheCanvasReachTheRegister() throws Exception {
        try (LocalDevTools chromium = LocalDevTools.openOrNull()) {
            Assumptions.assumeTrue(chromium != null,
                    "Chromium is not installed for Playwright; skipping. Install it with: " + LocalDevTools.INSTALL_COMMAND);
            start(chromium.cdpUrl());
            try (Playwright playwright = PlaywrightHome.create(); Browser operator = playwright.chromium().launch()) {
                Page page = operator.newPage();
                page.navigate("http://" + WebApp.BIND_HOST + ":" + app.port() + "/pos");
                page.waitForSelector("#pos-screen.live");
                page.waitForFunction("() => document.querySelector('#pos-screen-status').textContent === 'Live'");
                assertFalse(page.isVisible("#pos-screen-status"), "the status hides once the screen is live");
                assertFalse(page.isVisible("h1"), "no heading over the picture");
                assertTrue(page.getAttribute("#pos-screen", "title").contains("screen test"));

                Page register = chromium.page();
                double[] viewport = {register.viewportSize().width, register.viewportSize().height};
                BoundingBox canvas = picture(page);
                BoundingBox button = register.locator("#press").boundingBox();
                page.mouse().click(onCanvas(canvas, viewport, button, true), onCanvas(canvas, viewport, button, false));
                register.waitForFunction("() => document.querySelector('#press').dataset.n === '1'");

                BoundingBox input = register.locator("#typed").boundingBox();
                page.mouse().click(onCanvas(canvas, viewport, input, true), onCanvas(canvas, viewport, input, false));
                page.keyboard().type("ab");
                register.waitForFunction("() => document.querySelector('#typed').value === 'ab'");
            }
        }
    }

    /**
     * The box the picture is drawn in: the canvas fills the page and the picture is contained in it, so the picture
     * is the canvas's box narrowed to the frame's aspect and centred. Also checks the picture is big: it fills the
     * content area in one direction.
     */
    private static BoundingBox picture(Page page) {
        BoundingBox box = page.locator("#pos-screen").boundingBox();
        Object size = page.evaluate("() => [document.querySelector('#pos-screen').width,"
                + " document.querySelector('#pos-screen').height]");
        java.util.List<?> wh = (java.util.List<?>) size;
        double w = ((Number) wh.get(0)).doubleValue();
        double h = ((Number) wh.get(1)).doubleValue();
        double scale = Math.min(box.width / w, box.height / h);
        BoundingBox picture = new BoundingBox();
        picture.width = w * scale;
        picture.height = h * scale;
        picture.x = box.x + (box.width - picture.width) / 2;
        picture.y = box.y + (box.height - picture.height) / 2;
        BoundingBox main = page.locator("main").boundingBox();
        assertTrue(picture.width > main.width * 0.9 || picture.height > main.height * 0.9,
                "the picture fills the area one way: " + picture.width + "x" + picture.height + " in "
                        + main.width + "x" + main.height);
        return picture;
    }

    /** Where on the operator's canvas the centre of a box on the register's page is drawn. */
    private static double onCanvas(BoundingBox canvas, double[] viewport, BoundingBox box, boolean x) {
        return x ? canvas.x + (box.x + box.width / 2) / viewport[0] * canvas.width
                : canvas.y + (box.y + box.height / 2) / viewport[1] * canvas.height;
    }

    private URI socketUri() {
        return URI.create("ws://" + WebApp.BIND_HOST + ":" + app.port() + PosScreenRoutes.PATH);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://" + WebApp.BIND_HOST + ":" + app.port() + path))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The page's side of the screen socket: messages arrive in a queue. */
    private static final class Socket implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder parts = new StringBuilder();
        private WebSocket socket;

        static Socket open(URI uri, String origin) throws Exception {
            Socket listener = new Socket();
            WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
            if (origin != null) {
                builder.header("Origin", origin);
            }
            listener.socket = builder.buildAsync(uri, listener).get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return listener;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            parts.append(data);
            if (last) {
                try {
                    messages.add(Json.MAPPER.readTree(parts.toString()));
                } catch (IOException e) {
                    throw new AssertionError("not JSON from the screen socket", e);
                }
                parts.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        /** The next message of {@code type}, skipping others (frames keep coming). */
        JsonNode next(String type) throws InterruptedException {
            long end = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < end) {
                JsonNode m = messages.poll(end - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (m != null && type.equals(m.path("type").asText())) {
                    return m;
                }
            }
            return fail("no '" + type + "' message within " + WAIT.toSeconds() + " s");
        }

        void send(String text) throws Exception {
            socket.sendText(text, true).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        }

        void close() {
            socket.abort();
        }
    }
}
