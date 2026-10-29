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
    void thePreflightRunsInTheBackgroundAndItsReportIsShownEscaped() throws Exception {
        post("/run/preflight", true);
        String body = "";
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            body = get("/fragments/run").body();
            if (body.contains("REPORT")) {
                break;
            }
            Thread.onSpinWait();
        }

        assertTrue(body.contains("REPORT &lt;ok&gt;"), body);
        assertFalse(body.contains("REPORT <ok>"), body);
    }

    /** A richer run: a plan, two cases (one unmigrated), a test with steps, a validation, simulators, log lines. */
    private static final List<String> RICH = List.of(STARTED,
            "{\"type\":\"plan.resolved\",\"cases\":[{\"key\":\"PETROCORE-1\",\"summary\":\"Cash <sale>\","
                    + "\"node\":\"tests/suites/c/PETROCORE-1-TestCase.feature\"},"
                    + "{\"key\":\"PETROCORE-9\",\"summary\":\"Not migrated\",\"node\":null}]}",
            "{\"type\":\"step.entered\",\"step\":\"System Checks\"}",
            "{\"type\":\"step.check\",\"step\":\"System Checks\",\"label\":\"petroapps current\","
                    + "\"outcome\":\"passed\",\"detail\":\"up to date\",\"elapsed\":0.2}",
            "{\"type\":\"step.finished\",\"step\":\"System Checks\",\"state\":\"complete\",\"detail\":\"\",\"elapsed\":1}",
            "{\"type\":\"simulator.state\",\"name\":\"BUYPASS Host\",\"state\":\"ready\"}",
            "{\"type\":\"simulator.state\",\"name\":\"Receipt Printer\",\"state\":\"ready\",\"bind\":\"COM1\"}",
            "{\"type\":\"connection.state\",\"target\":\"Commander\",\"state\":\"up\"}",
            "{\"type\":\"log.line\",\"at\":\"2026-10-24T10:00:01Z\",\"level\":\"INFO\",\"logger\":\"cat\","
                    + "\"message\":\"PASS  petroapps <current>\",\"step\":\"System Checks\"}",
            "{\"type\":\"log.line\",\"at\":\"2026-10-24T10:00:02Z\",\"level\":\"ERROR\",\"logger\":\"sim.BUYPASS Host\","
                    + "\"message\":\"host said no\",\"step\":\"\"}",
            "{\"type\":\"step.entered\",\"step\":\"Tests Run\"}",
            "{\"type\":\"test.started\",\"nodeid\":\"tests/suites/c/PETROCORE-1-TestCase.feature::cash\"}",
            "{\"type\":\"test.step\",\"nodeid\":\"tests/suites/c/PETROCORE-1-TestCase.feature::cash\",\"index\":1,"
                    + "\"keyword\":\"Given\",\"text\":\"a cash sale\",\"outcome\":\"passed\",\"duration\":1.5}",
            "{\"type\":\"test.validation\",\"at\":\"2026-10-24T10:00:03Z\","
                    + "\"nodeid\":\"tests/suites/c/PETROCORE-1-TestCase.feature::cash\",\"case\":\"PETROCORE-1\","
                    + "\"kind\":\"receipt\",\"name\":\"PETROCORE-1-Receipt.txt\",\"passed\":false,"
                    + "\"diff\":\"-TOTAL 1.00\\n+TOTAL 2.00\",\"expected\":\"HEAD\\nTOTAL 1.00\","
                    + "\"actual\":\"HEAD\\nTOTAL 2.00\"}",
            "{\"type\":\"test.finished\",\"nodeid\":\"tests/suites/c/PETROCORE-1-TestCase.feature::cash\","
                    + "\"outcome\":\"failed\",\"duration\":3.0,\"exc_type\":\"AssertionError\",\"detail\":\"receipt\"}");

    private void startRich() throws Exception {
        engine = new FakeRunEngine(tmp.resolve("results").resolve("rich"), RICH, null)
                .closingWith("{\"type\":\"run.finished\",\"run_id\":\"abc123def456\",\"outcome\":\"failed\","
                        + "\"passed\":0,\"failed\":1,\"warned\":0,\"cancelled\":false,\"elapsed\":4.0}");
        post("/run/start", true);
        engine.awaitWritten();
    }

    @Test
    void theBoardShowsTheCasesInPlanOrderTheSimulatorsAndTheConnections() throws Exception {
        startRich();
        String panel = get("/fragments/run").body();

        assertTrue(panel.contains("PETROCORE-1: Cash &lt;sale&gt;"), panel);
        assertTrue(panel.indexOf("PETROCORE-1:") < panel.indexOf("PETROCORE-9:"), "plan order");
        assertTrue(panel.contains("not migrated"), panel);
        assertTrue(panel.contains("BUYPASS Host") && panel.contains("/run/log?source=sim%3ABUYPASS+Host"), panel);
        assertTrue(panel.contains("/fragments/run/receipts"), "the Receipt Printer's row opens its roll");
        assertTrue(panel.contains("Commander"), panel);
        assertTrue(panel.contains("id=\"run-clock\""), panel);
        assertTrue(panel.contains(">Validations</button>"), panel);

        String side = get("/fragments/run/side").body();
        String hash = side.replaceAll("(?s).*?h=([0-9a-f]+)\".*", "$1");
        assertEquals(204, get("/fragments/run/side?h=" + hash).statusCode(), "an unchanged part is not redrawn");

        engine.release();
        FakeRunEngine.awaitIdle(run);
        String ended = get("/fragments/run").body();
        assertTrue(ended.contains("Run Complete"), ended);
        assertTrue(ended.contains("Skipped:"), ended);
        assertTrue(ended.contains("Total:"), ended);
        assertTrue(ended.contains("PETROCORE-9: Not migrated"), "the Not Run card names the case");
        assertFalse(ended.contains("every 2s"), ended);
    }

    @Test
    void theStepCaseAndValidationsDialogsShowTheirRows() throws Exception {
        startRich();

        String step = get("/fragments/run/step?i=0").body();
        assertTrue(step.contains("petroapps current"), step);
        assertTrue(step.contains("1 check · 1 passed"), step);
        assertTrue(step.contains("Show this step's log") && step.contains("/run/log?step=System+Checks"), step);
        String tests = get("/fragments/run/step?i=4").body();
        assertTrue(tests.contains("PETROCORE-1: Cash &lt;sale&gt;"), tests);
        assertTrue(tests.contains("1 plan case(s) not run"), tests);

        String detail = get("/fragments/run/case?key=PETROCORE-1").body();
        assertTrue(detail.contains("Given a cash sale"), detail);
        assertTrue(detail.contains("AssertionError: receipt"), detail);
        assertTrue(get("/fragments/run/case?key=PETROCORE-9").body().contains("counted as Not Run"));

        String validations = get("/fragments/run/validations").body();
        assertTrue(validations.contains("1 validation, 1 failed this run"), validations);
        assertTrue(validations.contains("Receipts 1 · 1 failed"), validations);
        assertTrue(validations.contains("<tr class=\"changed\">"), validations);
        assertTrue(validations.contains("Expected (baseline)"), validations);
        engine.release();
        FakeRunEngine.awaitIdle(run);
    }

    @Test
    void theLogFiltersBySourceLevelAndSearchAndCopiesEveryKeptLine() throws Exception {
        String page = get("/run/log").body();
        assertTrue(page.contains("No run yet"), page);

        startRich();
        String all = get("/fragments/run/log").body();
        assertTrue(all.contains("PASS  petroapps &lt;current&gt;"), all);
        assertFalse(all.contains("petroapps <current>"), all);
        assertTrue(all.contains("every 2s"), all);
        assertTrue(all.contains("<option value=\"sim:BUYPASS Host\">BUYPASS Host</option>"), all);

        String step = get("/run/log?step=System+Checks").body();
        assertTrue(step.contains("petroapps"), step);
        assertFalse(step.contains("host said no"), step);
        assertTrue(step.contains("value=\"step:System Checks\" selected"), step);

        String errors = get("/fragments/run/log/body?lv=1&level=ERROR").body();
        assertTrue(errors.contains("host said no") && !errors.contains("petroapps"), errors);

        String text = get("/run/log.txt?q=HOST").body();
        assertEquals("10:00:02 E BUYPASS Host: host said no", text);
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
