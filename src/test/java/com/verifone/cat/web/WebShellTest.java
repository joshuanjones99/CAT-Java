package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.results.ResultsLibrary;
import com.verifone.cat.runconfig.ConfigFixture;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runconfig.SavedChoices;
import com.verifone.cat.runservice.FakeRunEngine;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItem;
import com.verifone.cat.setup.ToolStaging;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.shell.Shell;
import com.verifone.cat.sims.FakeProcessOps;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.Supervisor;

import io.javalin.Javalin;

/**
 * The site shell over HTTP: the ping, the shell poll (toasts, loading line, setup marker), Shut down CAT, the Start
 * run gate, the Config sections and {@code serve}'s start-up rules. Every part that would reach the machine is a
 * fake: the shim points at a closed port, the run engine is fake, setup's single sign-in item reads nothing.
 * Evidence about this repository only.
 */
class WebShellTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private Javalin app;
    private Shell shell;
    private ConfigForm form;
    private RunService run;
    private final AtomicInteger engines = new AtomicInteger();
    private final CountDownLatch exited = new CountDownLatch(1);
    private boolean signedIn;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        form = ConfigFixture.form(tmp);
        form.togglePlan(ConfigFixture.PLAN);
        Path folder = tmp.resolve("results").resolve("run1");
        Files.createDirectories(folder);
        run = new RunService("lab", () -> {
            engines.incrementAndGet();
            return new FakeRunEngine(folder, List.of(), null);
        }, () -> "", Clock.systemDefaultZone());
        shell = new Shell();
        SetupItem login = new SetupItem("verifone_login", "Verifone sign-in", true, false, true, "sign in",
                env -> signedIn ? Result.passed("verifone_login", "stored") : Result.failed("verifone_login", "none"));
        SetupEnv env = new SetupEnv(Map.of(), tmp, tmp, (argv, timeout) -> {
            throw new AssertionError("no command in this test");
        });
        SetupService setup = new SetupService(() -> new SetupAudit(List.of(login), env), e -> ToolStaging.NONE, shell,
                Runnable::run, Runnable::run, true);
        SimEntry row = SimEntry.exeRow("Inert").exe("missing-simulator.exe").match("cat-web-shell-token").build();
        WebApp.Parts parts = WebApp.parts(new PosShimClient("127.0.0.1", 1, null, Duration.ofSeconds(1)), "test",
                new ResultsLibrary(tmp.resolve("results"), tmp.resolve("results").resolve("index.json")), form, run,
                new SimulatorsService(new Supervisor(new FakeProcessOps()), SimContext.of(tmp, ""), List.of(row),
                        Runnable::run));
        parts.shell = shell;
        parts.setup = setup;
        parts.downloads = new DownloadsService(() -> {
            throw new IllegalStateException("no Artifactory in this test");
        }, tmp.resolve("builds"));
        parts.settingsFile = tmp.resolve("web-settings.json");
        parts.env = Map.of();
        parts.exit = exited::countDown;
        app = WebApp.build(parts).start(WebApp.BIND_HOST, 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    private String uri(String path) {
        return "http://" + WebApp.BIND_HOST + ":" + app.port() + path;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(uri(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, boolean htmx) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePingSaysCat() throws Exception {
        HttpResponse<String> reply = get(WebApp.PING_PATH);
        assertEquals(200, reply.statusCode());
        assertEquals("CAT", reply.body());
    }

    @Test
    void theShellPollCarriesNewToastsTheLoadingLineAndTheSetupStatusAndIsTheHeartbeat() throws Exception {
        long before = shell.lastToast();
        shell.notify("first <b>", "warning", 5);
        shell.loading("setup", "checking setup");
        shell.setupStatus("Sign in on Setup · 1 setup item(s)");

        JsonNode state = JSON.readTree(get("/_cat/shell?page=p1&since=" + before).body());

        assertEquals(1, state.get("toasts").size());
        assertEquals("first <b>", state.get("toasts").get(0).get("message").asText(), "text, escaped by the page");
        assertEquals("warning", state.get("toasts").get(0).get("kind").asText());
        assertEquals("Loading CAT — checking setup", state.get("loading").asText());
        assertEquals(1, shell.openPages(System.nanoTime()));
        assertEquals(0, JSON.readTree(get("/_cat/shell?page=p1&since=" + shell.lastToast()).body())
                .get("toasts").size());

        post("/_cat/page-closed?page=p1", "", false);
        assertEquals(0, shell.openPages(System.nanoTime()));
    }

    @Test
    void everyPageCarriesTheShellAndTheSetupMarkerFollowsTheStatus() throws Exception {
        String quiet = get("/results").body();
        assertTrue(quiet.contains("data-shell=\"on\""), quiet);
        assertTrue(quiet.contains("id=\"cat-setup-marker\""), quiet);
        assertTrue(quiet.contains("width=\"16\" height=\"16\" hidden>"), "no status, marker hidden");
        assertFalse(get("/run").body().contains("class=\"setup-warning\""));

        shell.setupStatus("Setup: 2 item(s) need attention");
        String runPage = get("/run").body();
        assertTrue(runPage.contains("class=\"setup-warning\" href=\"/setup\""), runPage);
        assertTrue(runPage.contains("Setup: 2 item(s) need attention"), runPage);
        assertFalse(get("/results").body().contains("class=\"setup-warning\""), "only Run carries the button");
        shell.loading("tools", "downloading Java17");
        assertTrue(get("/tools").body().contains("Loading CAT — downloading Java17"));
    }

    @Test
    void shutDownNeedsThePageThenStopsWhatRunsAndCloses() throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        shell.onShutdown(hooks::incrementAndGet);
        assertEquals(403, post("/_cat/shutdown", "", false).statusCode());
        assertEquals(0, hooks.get());

        assertEquals(204, post("/_cat/shutdown", "", true).statusCode());
        assertTrue(exited.await(10, TimeUnit.SECONDS));
        assertEquals(1, hooks.get());
    }

    @Test
    void startRunIsRefusedWithoutTheSignInsAndSaysWhatIsMissing() throws Exception {
        String overview = post("/run/start", "", true).body();

        assertTrue(overview.contains("Run not started: sign in first on the Setup page. Missing: verifone_login"),
                overview);
        assertEquals(0, engines.get(), "nothing was launched");
        assertTrue(shell.toastsSince(0).stream().anyMatch(t -> t.kind().equals("error")));
    }

    @Test
    void startRunIsRefusedWhileAnInstallHoldsTheRig() throws Exception {
        signedIn = true;
        shell.busy().begin(RigBusy.INSTALL);
        String overview = post("/run/start", "", true).body();
        assertTrue(overview.contains("An install is already in progress."), overview);
        assertEquals(0, engines.get());
        shell.busy().end(RigBusy.INSTALL);
    }

    @Test
    void aWebStartTakesThePagesOwnChoicesNotASavedFile() throws Exception {
        // A saved new_install elsewhere must never reach a web Start: the page's Downloads and Execution do.
        assertEquals(200, post("/downloads/set?field=mode", "value=skip", true).statusCode());
        assertEquals(200, post("/config/set", "name=execMode&value=BDD", true).statusCode());
        assertEquals(200, post("/config/set", "name=tpBranch&value=PETROCORE-1", true).statusCode());

        var plan = com.verifone.cat.runservice.RunStart.from(form, run.startContext());
        assertTrue(plan.startable(), plan.refusal());
        assertEquals(com.verifone.cat.launch.UpgradeMode.SKIP, plan.config().upgradeMode());
        assertEquals("BDD", plan.config().executionMode());
        assertEquals("PETROCORE-1", plan.config().testPlanBranch());

        assertEquals(200, post("/downloads/set?field=mode", "value=new_install", true).statusCode());
        assertEquals(com.verifone.cat.launch.UpgradeMode.NEW_INSTALL,
                com.verifone.cat.runservice.RunStart.from(form, run.startContext()).config().upgradeMode());
        String overview = get("/run").body();
        assertTrue(overview.contains("Upgrade:       new_install"), overview);
    }

    @Test
    void theRunHoldsTheSharedFlagForTheOtherPages() throws Exception {
        assertEquals("", shell.busy().current());
        assertEquals("", shell.holding());
    }

    @Test
    void theConfigSectionsSaveEveryChoiceUnderThePythonsKeys() throws Exception {
        String controls = get(ConfigSections.PATH).body();
        assertTrue(controls.contains("Test plan branch"), controls);
        assertTrue(controls.contains("Commander Connect"), controls);
        assertTrue(controls.contains("Report to Xray"), controls);
        assertTrue(controls.contains("id=\"downloads-panel\""), "the shared Downloads panel");

        assertEquals(200, post("/config/set", "name=xray&value=off", true).statusCode());
        assertEquals(200, post("/config/set", "name=ccOnboard&value=on&value=off", true).statusCode());
        assertEquals(200, post("/config/set", "name=ccLab&value=QAT", true).statusCode());
        assertEquals(200, post("/config/set", "name=tpBranch&value=PETROCORE-141728", true).statusCode());
        assertEquals(400, post("/config/set", "name=ccLab&value=Nowhere", true).statusCode());
        assertEquals(400, post("/config/set", "name=rig&value=x", true).statusCode());
        assertEquals(403, post("/config/set", "name=xray&value=on", false).statusCode());
        assertEquals(200, post("/downloads/set?field=mode", "value=new_install", true).statusCode());

        Map<String, Object> saved = SavedChoices.read(form.savedPath());
        assertEquals(false, saved.get("config-xray"));
        assertEquals(true, saved.get("config-cc-onboard"));
        assertEquals("QAT", saved.get("config-cc-lab"));
        assertEquals("PETROCORE-141728", saved.get("config-tp-branch"));
        assertEquals("new_install", saved.get("config-upgrade"), "the Downloads choice is saved with the form");
        assertEquals("buypass", saved.get("config-dist"));
        assertTrue(post("/config/refresh", "", true).body().contains("Refresh from Confluence"));
    }

    // ---- serve ---------------------------------------------------------------------------------

    /** A host whose port, browser and app are fakes; records what it was asked. */
    private static final class FakeHost implements WebServe.Host {
        boolean inUse;
        boolean cat;
        final List<String> opened = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        Javalin built;

        @Override
        public boolean portInUse(int port) {
            return inUse;
        }

        @Override
        public boolean catServing(int port) {
            return cat;
        }

        @Override
        public void openBrowser(String url) {
            opened.add(url);
        }

        @Override
        public Javalin app(WebServe.Options options, Shell shell, Runnable exit) {
            calls.add("app " + options.rig + " setup=" + options.setup);
            built = Javalin.create(config -> config.showJavalinBanner = false);
            built.post("/stop", ctx -> exit.run());
            return built;
        }

        @Override
        public void started(WebServe.Options options, Shell shell) {
            calls.add("started");
            built.stop();
        }

        @Override
        public void logToFile() {
            calls.add("log");
        }
    }

    @Test
    void aSecondStartOpensTheRunningCatAndAPortHeldByAnotherProgramIsRefused() {
        FakeHost host = new FakeHost();
        host.inUse = true;
        host.cat = true;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        assertEquals(0, WebServe.serve(WebServe.Options.parse(List.of("--port", "8123")), host, out));
        assertEquals(List.of("http://127.0.0.1:8123/"), host.opened);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("CAT is already running at http://127.0.0.1:8123/"));
        assertTrue(host.calls.isEmpty(), "no second server");

        host.cat = false;
        assertEquals(2, WebServe.serve(WebServe.Options.parse(List.of("--port", "8123")), host, out));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(
                "Port 8123 is in use by another program; start CAT with --port N."));
    }

    @Test
    void serveStartsTheAppChecksSetupAndWithNoBrowserNeverLogsToFileOrOpensOne() {
        FakeHost host = new FakeHost();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int free;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            free = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        int code = WebServe.serve(WebServe.Options.parse(List.of("--port", String.valueOf(free), "--no-browser",
                "--no-setup")), host, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertEquals(List.of("app lab setup=false", "started"), host.calls, "lab is the default rig");
        assertTrue(host.opened.isEmpty());
    }

    @Test
    void theFlagsParseAndTheRigIsNotRequired() {
        WebServe.Options options = WebServe.Options.parse(List.of("--rig", "other", "--stay", "--shim-port", "9000"));
        assertEquals("other", options.rig);
        assertTrue(options.stay);
        assertTrue(options.browser);
        assertTrue(options.setup);
        assertEquals(9000, options.shimPort);
        assertEquals(WebServe.DEFAULT_RIG, WebServe.Options.parse(List.of()).rig);
        assertEquals(2, WebServe.run(new String[] {"--bogus"}));
    }
}
