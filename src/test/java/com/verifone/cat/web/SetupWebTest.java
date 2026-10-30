package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;

import io.javalin.Javalin;

/**
 * The Setup page over HTTP, with two synthetic items. Nothing here touches the workstation: the
 * probes only count themselves, and the env is an empty map over the JVM's own temp folder.
 */
class SetupWebTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger probes = new AtomicInteger();
    private Javalin app;
    private String base;

    @BeforeEach
    void start() {
        List<SetupItem> items = List.of(
                new SetupItem("passing", "Passing check", false, true, false, "nothing",
                        env -> {
                            probes.incrementAndGet();
                            return Result.passed("passing", "all good");
                        }),
                new SetupItem("failing", "Failing <b>check</b>", true, true, true, "run <code>the step</code>",
                        env -> {
                            probes.incrementAndGet();
                            return Result.failed("failing", "value <script>alert(1)</script>");
                        }));
        SetupEnv env = new SetupEnv(java.util.Map.of(), java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), (argv, timeout) -> {
                    throw new AssertionError("the Setup page must not run a command in this test");
                });
        app = Javalin.create(config -> config.showJavalinBanner = false);
        SetupRoutes.register(app, new SetupService(new SetupAudit(items, env)));
        app.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> checkWith(boolean htmx) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/setup/check"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theFirstViewRunsNoCheckAndSaysSo() throws Exception {
        HttpResponse<String> response = get("/setup");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<h1>Setup</h1>"), response.body());
        assertTrue(response.body().contains("Not checked yet"), response.body());
        assertEquals(0, probes.get(), "opening the page must not probe the workstation");
    }

    @Test
    void aCheckWithoutHtmxIsRefusedAndRunsNothing() throws Exception {
        HttpResponse<String> response = checkWith(false);

        assertEquals(403, response.statusCode());
        assertEquals(0, probes.get());
    }

    @Test
    void aCheckWithHtmxRunsEveryItemAndShowsTheResults() throws Exception {
        HttpResponse<String> response = checkWith(true);
        String html = response.body();

        assertEquals(200, response.statusCode());
        assertEquals(2, probes.get());
        assertTrue(html.contains("id=\"setup-panel\""), html);
        assertTrue(html.contains("1 of 2 checks pass"), html);
        assertTrue(html.contains("1 need attention (1 required)."), html);
        assertTrue(html.contains("all good"), html);
        assertTrue(html.contains("needs attention"), html);
    }

    @Test
    void textFromTheWorkstationIsEscapedAndNeverRunAsMarkup() throws Exception {
        String html = checkWith(true).body();

        assertFalse(html.contains("<script>alert(1)</script>"), html);
        assertTrue(html.contains("value &lt;script&gt;alert(1)&lt;/script&gt;"), html);
        assertTrue(html.contains("Failing &lt;b&gt;check&lt;/b&gt;"), html);
        assertTrue(html.contains("run &lt;code&gt;the step&lt;/code&gt;"), html);
    }

    @Test
    void aSignInThatFailsSaysItIsEnteredByHand() throws Exception {
        String html = checkWith(true).body();

        assertTrue(html.contains("run &lt;code&gt;the step&lt;/code&gt;"), html);
        assertTrue(html.contains("Sign in on Setup · 1 setup item(s)"), html);
    }

    @Test
    void theRecordedResultsSurviveAPageView() throws Exception {
        checkWith(true);
        String html = get("/setup").body();

        assertTrue(html.contains("1 of 2 checks pass"), html);
        assertEquals(2, probes.get(), "a page view reads the last check, it does not run another");
    }
}
