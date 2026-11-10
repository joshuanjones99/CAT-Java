package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;
import com.verifone.cat.shell.Shell;

import io.javalin.Javalin;

/**
 * A card whose fix is a command: the page offers a button that runs it, the check never runs it by itself, and an
 * optional one is not counted as needing attention. The command is a synthetic one that flips a flag; nothing here
 * touches the workstation.
 */
class SetupCommandWebTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicInteger runs = new AtomicInteger();
    private SetupAudit audit;
    private Javalin app;
    private String base;

    @BeforeEach
    void start() {
        SetupItem passing = new SetupItem("passing", "Passing check", false, true, false, "",
                env -> Result.passed("passing", "all good"));
        SetupItem installable = new SetupItem("installable", "Installable thing", true, false, false,
                "Press Install.", env -> installed.get()
                        ? Result.passed("installable", "present")
                        : Result.failed("installable", "not installed"))
                .withCommand("Install", env -> {
                    runs.incrementAndGet();
                    installed.set(true);
                    return Result.passed("installable", "installed");
                });
        SetupEnv env = new SetupEnv(Map.of(), Path.of(System.getProperty("java.io.tmpdir")),
                Path.of(System.getProperty("java.io.tmpdir")), (argv, timeout) -> {
                    throw new AssertionError("no real command in this test");
                });
        audit = new SetupAudit(List.of(passing, installable), env);
        app = Javalin.create(config -> config.showJavalinBanner = false);
        SetupRoutes.register(app, new SetupService(audit));
        app.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> post(String path, String form) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("HX-Request", "true")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theCheckOffersTheButtonWithoutRunningItOrNagging() throws Exception {
        String html = post("/setup/check", "").body();

        assertEquals(0, runs.get(), "the check must never run a command by itself");
        assertTrue(html.contains("hx-post=\"/setup/run\""), html);
        assertTrue(html.contains(">Install</button>"), html);
        assertTrue(html.contains("<span class=\"badge\">optional</span>"), html);
        assertFalse(html.contains("need attention"), html);
        assertTrue(audit.stillNeedsAttention().isEmpty(), "an offered item is not something to nag about");
        assertEquals(1, audit.failures().size(), "it still fails its probe");
    }

    @Test
    void theButtonRunsTheCommandAndTheCardTurnsGreen() throws Exception {
        post("/setup/check", "");

        HttpResponse<String> response = post("/setup/run", "name=installable");

        assertEquals(200, response.statusCode());
        assertEquals(1, runs.get());
        assertEquals("present", audit.results().get("installable").detail(), "detected again after the command");
        assertFalse(response.body().contains(">Install</button>"), response.body());
        assertTrue(response.body().contains("2 of 2 checks pass"), response.body());
    }

    @Test
    void aRunWithoutHtmxIsRefusedAndRunsNothing() throws Exception {
        post("/setup/check", "");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/setup/run"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=installable")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
        assertEquals(0, runs.get());
    }

    @Test
    void anItemWithNoCommandIsRefused() throws Exception {
        post("/setup/check", "");

        String html = post("/setup/run", "name=passing").body();

        assertTrue(html.contains("That setup item has no command CAT runs."), html);
        assertEquals(0, runs.get());
    }

    @Test
    void whileTheCommandRunsTheButtonIsDisabledAndThePanelPolls() {
        List<Runnable> queued = new java.util.ArrayList<>();
        SetupService service = new SetupService(() -> audit, env -> com.verifone.cat.setup.ToolStaging.NONE,
                new Shell(), Runnable::run, Runnable::run, queued::add, false);
        service.check(false);

        SetupService.Said said = service.runCommand("installable");
        String running = SetupPage.panel(service.snapshot());
        SetupService.Said again = service.runCommand("installable");

        assertTrue(said.ok(), said.text());
        assertTrue(running.contains("Running…"), running);
        assertTrue(running.contains("disabled aria-busy=\"true\""), running);
        assertTrue(running.contains("hx-trigger=\"every 2s\""), running);
        assertTrue(again.text().contains("already running"), again.text());
        assertEquals(1, queued.size(), "a second press while it runs does nothing");

        queued.get(0).run();

        assertEquals(1, runs.get());
        assertFalse(SetupPage.panel(service.snapshot()).contains("every 2s"));
    }
}
