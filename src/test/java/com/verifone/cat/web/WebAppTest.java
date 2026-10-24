package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

import io.javalin.Javalin;

/** Drives the real server over HTTP, in front of {@link FakeShim}. Evidence about this repository only. */
class WebAppTest {

    private FakeShim shim;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        shim = new FakeShim()
                .onFixture("GET", "/health", "health.json")
                .onFixture("GET", "/pos/screen", "screen.json")
                .onFixture("GET", "/pos/keys", "keys.json")
                .on("POST", "/pos/keys", 200, """
                        {"client":"topaz101","requested":["YES"],
                         "pressed":[{"key":"YES","processed":false,"elapsed_ms":15000}],"all_processed":false}
                        """);
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        app = WebApp.start(client, "CAT-Java: test / topaz101", 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        shim.close();
    }

    @Test
    void pageLoadsHtmxAndTheCards() throws Exception {
        String page = get("/pos").body();

        assertTrue(page.contains("/static/htmx.min.js"));
        assertTrue(page.contains("hx-get=\"/fragments/screen\""));
        assertEquals(200, get("/static/htmx.min.js").statusCode());
    }

    @Test
    void screenFragmentShowsTheOverlayAndSoftKeys() throws Exception {
        String html = get("/fragments/screen").body();

        assertTrue(html.contains("NO OPEN CASHIER SESSION. LOGGING OUT."), html);
        assertTrue(html.contains("Return To Sales"), html);
        assertTrue(html.contains("204.98"), html);
    }

    @Test
    void healthFragmentShowsTheClients() throws Exception {
        assertTrue(get("/fragments/health").body().contains("topaz101"));
    }

    @Test
    void keysFragmentOffersOnlyBuildableNames() throws Exception {
        String html = get("/fragments/keys").body();

        assertTrue(html.contains("VOID_TICKET"), html);
        assertFalse(html.contains(">APPROVE<"), html);
    }

    @Test
    void shimErrorRendersAsACardNotAnHttpError() throws Exception {
        shim.on("GET", "/pos/screen", 503, "{\"error\":\"no-state\",\"detail\":\"POS has pushed nothing.\"}");

        HttpResponse<String> response = get("/fragments/screen");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("DeviceUnreachable"), response.body());
    }

    @Test
    void keyPressFromThePageReachesTheShim() throws Exception {
        HttpResponse<String> response = postKey("YES", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("not processed"), response.body());
        assertEquals("{\"keys\":[\"YES\"]}", shim.seen.get(shim.seen.size() - 1).body());
    }

    @Test
    void keyPressWithoutTheHtmxHeaderIsRefused() throws Exception {
        HttpResponse<String> response = postKey("YES", false);

        assertEquals(403, response.statusCode());
        assertTrue(shim.seen.stream().noneMatch(s -> s.method().equals("POST")));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postKey(String key, boolean fromHtmx) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/keys"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("key=" + key));
        if (fromHtmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + WebApp.BIND_HOST + ":" + app.port() + path);
    }
}
