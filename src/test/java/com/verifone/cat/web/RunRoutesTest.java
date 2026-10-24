package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.runconfig.ConfigFixture;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.FakeRunEngine;
import com.verifone.cat.runservice.RunService;

import io.javalin.Javalin;

/**
 * The Run tab's Overview and Log over HTTP, with a fake engine: no run reaches a rig. Evidence about
 * this repository only.
 */
class RunRoutesTest {

    private static final String STARTED = "{\"type\":\"run.started\",\"at\":\"2026-10-24T10:00:00Z\","
            + "\"run_id\":\"abc123def456\",\"rig\":\"lab\",\"test_plans\":[\"PETROCORE-135127\"],\"fake_pos\":false}";

    @TempDir
    Path tmp;

    private ConfigForm form;
    private RunService run;
    private FakeRunEngine engine;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        Path folder = tmp.resolve("results").resolve("abc123def456");
        engine = new FakeRunEngine(folder, List.of(STARTED,
                "{\"type\":\"step.entered\",\"step\":\"System Checks\"}",
                "{\"type\":\"step.finished\",\"step\":\"System Checks\",\"state\":\"failed\","
                        + "\"detail\":\"<script>alert(1)</script>\\nsecond line\"}"), null);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("run.log"), "<b>hi</b> and & more\n", StandardCharsets.UTF_8);
        // A fixed mid-morning clock: a continuous pass must not wait for the Commander's forced end of day,
        // whatever the time of day the test runs at.
        Clock morning = Clock.fixed(LocalDateTime.of(2026, 10, 24, 10, 0).atZone(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault());
        run = new RunService("lab", () -> engine, () -> "REPORT <ok>", morning);
        PosShimClient client = new PosShimClient("127.0.0.1", 1, null, Duration.ofSeconds(1));
        app = WebApp.create(client, "CAT-Java: test", form, run).start(WebApp.BIND_HOST, 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    @Test
    void theOverviewOffersStartAndPreflightBeforeARun() throws Exception {
        String page = get("/run").body();

        assertTrue(page.contains("Start run"), page);
        assertTrue(page.contains("Preflight (check only)"), page);
        assertTrue(page.contains("Pre-Run"), page);
        assertTrue(page.contains("Schedule run"), page);
        assertTrue(page.contains("Continuous run"), page);
        assertFalse(page.contains("Not built yet: Schedule"), page);
        assertFalse(get("/fragments/run").body().contains("every 2s"));
    }

    @Test
    void theScheduleAndTheContinuousRunWorkFromThePage() throws Exception {
        String scheduled = postForm("/run/schedule", "at=2999-01-01+23%3A00", true).body();
        assertTrue(scheduled.contains("Scheduled for 2999-01-01 23:00:00."), scheduled);
        assertTrue(scheduled.contains("#1 2999-01-01 23:00:00"), scheduled);
        assertTrue(scheduled.contains("every 2s"), scheduled);

        String refused = postForm("/run/schedule", "at=nonsense", true).body();
        assertTrue(refused.contains("Could not read that time"), refused);

        assertFalse(postForm("/run/schedule/cancel", "id=1", true).body().contains("#1 2999"));
        assertTrue(postForm("/run/schedule/clear", "", true).body().contains("Schedule cleared."));
        assertEquals(403, postForm("/run/schedule", "at=23%3A00", false).statusCode());

        String continuous = post("/run/continuous", true).body();
        assertTrue(continuous.contains("Finish continuous"), continuous);
        engine.awaitWritten();
        assertTrue(post("/run/continuous", true).body().contains("will finish after pass 1"));
        engine.release();
        FakeRunEngine.awaitIdle(run);
        assertFalse(get("/fragments/run").body().contains("every 2s"));
    }

    @Test
    void theActionsRefuseARequestThatIsNotFromThePage() throws Exception {
        assertEquals(403, post("/run/start", false).statusCode());
        assertEquals(403, post("/run/stop", false).statusCode());
        assertEquals(403, post("/run/preflight", false).statusCode());
    }

    @Test
    void startRunsAndThePanelPollsWhileItGoes() throws Exception {
        HttpResponse<String> started = post("/run/start", true);

        assertEquals(200, started.statusCode());
        engine.awaitWritten();
        String panel = get("/fragments/run").body();
        assertTrue(panel.contains("every 2s"), panel);
        assertTrue(panel.contains("abc123def456"), panel);
        assertTrue(panel.contains("System Checks"), panel);
        assertTrue(panel.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), panel);
        assertFalse(panel.contains("<script>alert(1)"), panel);
        // A failed step is a card of its own, above the step list, with its detail as the warning line.
        assertTrue(panel.contains("<h2>System Checks failed</h2>"), panel);
        assertTrue(panel.contains("<p class=\"warn\">&lt;script&gt;alert(1)&lt;/script&gt;</p>"
                + "<p class=\"warn\">second line</p>"), panel);

        assertTrue(post("/run/start", true).body().contains("A run is in progress: stop it first."));
        assertTrue(post("/run/pause", true).body().contains("Continue"));
        assertTrue(post("/run/stop", true).body().contains("Stopping"));
        engine.release();
        FakeRunEngine.awaitIdle(run);
        assertFalse(get("/fragments/run").body().contains("every 2s"));
        assertTrue(post("/run/new", true).body().contains("Pre-Run"));
    }

    @Test
    void startIsRefusedWhenTheConfigCannotRun() throws Exception {
        ConfigForm empty = ConfigFixture.form(tmp.resolve("empty"));
        Javalin other = WebApp.create(new PosShimClient("127.0.0.1", 1, null, Duration.ofSeconds(1)),
                "CAT-Java: test", empty, run).start(WebApp.BIND_HOST, 0);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + other.port() + "/run/start"))
                            .header("HX-Request", "true").POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertTrue(response.body().contains("Nothing to run yet"), response.body());
            assertFalse(response.body().contains("every 2s"));
        } finally {
            other.stop();
        }
    }

    @Test
    void thePreflightReportIsShownEscaped() throws Exception {
        String body = post("/run/preflight", true).body();

        assertTrue(body.contains("REPORT &lt;ok&gt;"), body);
        assertFalse(body.contains("REPORT <ok>"), body);
    }

    @Test
    void theLogTabShowsTheRunLogEscapedAndPollsWhileRunning() throws Exception {
        String page = get("/run/log").body();
        assertTrue(page.contains("No run yet") || page.contains("<pre class=\"run-log\">"), page);

        post("/run/start", true);
        engine.awaitWritten();
        String log = get("/fragments/run/log").body();

        assertTrue(log.contains("&lt;b&gt;hi&lt;/b&gt; and &amp; more"), log);
        assertFalse(log.contains("<b>hi</b>"), log);
        assertTrue(log.contains("every 2s"), log);
        engine.release();
        FakeRunEngine.awaitIdle(run);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, boolean htmx)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, boolean htmx) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + WebApp.BIND_HOST + ":" + app.port() + path);
    }
}
