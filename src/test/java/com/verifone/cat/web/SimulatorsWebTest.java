package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.verifone.cat.sims.SimTestSupport.fakeArgs;
import static com.verifone.cat.sims.SimTestSupport.freePort;
import static com.verifone.cat.sims.SimTestSupport.testJava;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.sims.FakeProcessOps;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.Supervisor;

import io.javalin.Javalin;

/**
 * The Launch Simulators page over HTTP. Every process is either a {@link RecordingOps} answer
 * (which kills nothing) or the test JVM running {@code FakeSimulator}; no PATS simulator is started,
 * and no PID a test did not spawn is passed to a kill. Evidence about this repository only.
 */
class SimulatorsWebTest {

    private static final Duration REAP_WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tools;

    private FakeShim shim;
    private Javalin app;
    private FakeProcessOps ops;
    private SimulatorsService sims;
    private final List<Runnable> queued = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        ops = new FakeProcessOps();
    }

    /** Stops the page's own children and reaps any fake simulator a test left running. */
    @AfterEach
    void tearDown() throws Exception {
        try {
            if (app != null) {
                app.stop();
            }
        } finally {
            shim.close();
            reapFakeSimulators();
        }
    }

    @Test
    void pageListsEveryRowEscapedAndOffersStartNotStop() throws Exception {
        sims = service(Runnable::run, inert("Odd <b>Row</b>", "cat-web-odd-token"));
        serve();

        HttpResponse<String> response = get("/simulators");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Odd &lt;b&gt;Row&lt;/b&gt;"));
        assertFalse(response.body().contains("<b>Row</b>"), "a row name is escaped, never raw");
        assertTrue(response.body().contains("hx-post=\"/simulators/start\""));
        assertFalse(response.body().contains("/simulators/stop"), "no simulator is up, so there is no Stop");
        assertTrue(response.body().contains("Launch Simulators"));
    }

    @Test
    void postsWithoutTheHtmxHeaderAreRefusedAndDoNothing() throws Exception {
        sims = service(Runnable::run, inert("Inert", "cat-web-refused-token"));
        serve();

        assertEquals(403, post("/simulators/start", false).statusCode());
        assertEquals(403, post("/simulators/stop", false).statusCode());
        assertEquals(403, post("/simulators/toggle?name=Inert", false).statusCode());
        assertEquals(403, post("/simulators/select?mode=none", false).statusCode());

        assertTrue(ops.killRequests.isEmpty(), "a refused stop kills nothing");
        assertTrue(sims.isTicked("Inert"), "a refused toggle changes nothing");
        assertEquals(SimulatorsService.Busy.IDLE, sims.busy());
        assertEquals("", sims.message());
    }

    @Test
    void startLaunchesTheTickedRowsAndTheLogFollowsItsOutput() throws Exception {
        assumeTrue(testJava().isPresent());
        int port = freePort();
        String token = token();
        sims = service(Runnable::run, fakeReady(port, token));
        serve();

        HttpResponse<String> started = post("/simulators/start", true);

        assertEquals(200, started.statusCode());
        assertTrue(started.body().contains("hx-post=\"/simulators/stop\""), "all ticked rows up: red Stop");
        assertTrue(started.body().contains("whoever started them"), "the Stop asks, naming the scope");
        assertTrue(started.body().contains("ready"));
        assertTrue(ops.killRequests.isEmpty(), "a start kills nothing");

        String log = "";
        for (int i = 0; i < 100 && !log.contains("READY"); i++) {
            log = get("/fragments/simulators/log?name=Fake%20Ready").body();
            pause();
        }
        assertTrue(log.contains("READY"), "the simulator's first line reaches the Log: " + log);

        HttpResponse<String> stopped = post("/simulators/stop", true);
        assertTrue(stopped.body().contains("Stop finished"), stopped.body());
        assertFalse(stopped.body().contains("hx-post=\"/simulators/stop\""), stopped.body());
    }

    @Test
    void stopAsksStopRunningForEveryLaunchableRowAndKillsOnlyTheMatchingPids() throws Exception {
        String token = "cat-web-stop-token";
        ops.listingText = "P\t987654\tjava -cp x " + token + "\n";
        sims = service(Runnable::run, inert("Inert", token));
        serve();

        HttpResponse<String> response = post("/simulators/stop", true);

        assertEquals(200, response.statusCode());
        assertEquals(List.of(List.of(987654L)), ops.killRequests, "only the PID that matched the token");
        assertTrue(response.body().contains("Stop finished: nothing left running."), response.body());
    }

    @Test
    void aRowThatNeedsItsButtonsShowsTheSupervisorsReason() throws Exception {
        sims = service(Runnable::run, clicked("Clicked"));
        serve();

        HttpResponse<String> response = post("/simulators/start", true);

        assertTrue(response.body().contains("needs attention"), response.body());
        assertTrue(response.body().contains("Access Bridge not ported"), response.body());
    }

    @Test
    void aSecondStartWhileOneRunsIsRefused() throws Exception {
        sims = service(queuing(), clicked("Clicked"));
        serve();

        post("/simulators/start", true);
        assertEquals(SimulatorsService.Busy.STARTING, sims.busy());
        HttpResponse<String> second = post("/simulators/start", true);

        assertTrue(second.body().contains("A launch is still running; wait for it."), second.body());
        queued.forEach(Runnable::run);
        assertEquals(SimulatorsService.Busy.IDLE, sims.busy());
        assertTrue(sims.message().startsWith("Launch finished"), sims.message());
    }

    @Test
    void ticksToggleAndAnEmptySelectionLaunchesNothing() throws Exception {
        sims = service(Runnable::run, clicked("Clicked"));
        serve();

        assertEquals(200, post("/simulators/toggle?name=Clicked", true).statusCode());
        assertFalse(sims.isTicked("Clicked"));
        assertEquals(200, post("/simulators/toggle?name=nope", true).statusCode());
        assertFalse(sims.isTicked("nope"), "an unknown name is ignored");

        post("/simulators/start", true);
        assertEquals("Nothing to launch: tick a simulator.", sims.message());

        post("/simulators/select?mode=all", true);
        assertTrue(sims.isTicked("Clicked"));
    }

    @Test
    void theLogRouteRefusesAnUnknownNameWithoutEchoingIt() throws Exception {
        sims = service(Runnable::run, clicked("Clicked"));
        serve();

        HttpResponse<String> unknown = get("/fragments/simulators/log?name=%3Cscript%3E");
        assertEquals(404, unknown.statusCode());
        assertFalse(unknown.body().contains("<script>"));

        HttpResponse<String> known = get("/fragments/simulators/log?name=Clicked");
        assertEquals(200, known.statusCode());
        assertTrue(known.body().contains("No output yet."));
    }

    // ----- helpers ----------------------------------------------------------------------

    private SimulatorsService service(Executor executor, SimEntry... rows) {
        return new SimulatorsService(new Supervisor(ops, Duration.ofMillis(300)), SimContext.of(tools, ""),
                List.of(rows), executor);
    }

    private Executor queuing() {
        return queued::add;
    }

    private void serve() {
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        app = WebApp.create(client, "CAT-Java: test", sims).start(WebApp.BIND_HOST, 0);
    }

    /** A row with no process and no port: it can be listed, stopped by token, and never spawned here. */
    private static SimEntry inert(String name, String token) {
        return SimEntry.exeRow(name).exe("missing-simulator.exe").match(token).build();
    }

    /** A row that needs its Swing buttons: the supervisor refuses it before any process is spawned. */
    private static SimEntry clicked(String name) {
        return SimEntry.exeRow(name).exe("missing-simulator.exe").click("Start Host").build();
    }

    /** The test JVM running FakeSimulator in ready mode on {@code port}. */
    private static SimEntry fakeReady(int port, String token) {
        return SimEntry.exeRow("Fake Ready").exe(testJava().orElseThrow().toString())
                .argv(fakeArgs(port, token, "ready").toArray(String[]::new))
                .match(token).tcp(port).build();
    }

    private static String token() {
        return "cat-web-sims-test-" + UUID.randomUUID();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
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

    private static void pause() {
        try {
            new CountDownLatch(1).await(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Destroys, and waits for, the fake simulators this JVM still has running. Only children whose
     * image is the test JDK's {@code java} are touched; each was started by a test in this class.
     */
    private static void reapFakeSimulators() throws InterruptedException {
        Optional<Path> java = testJava();
        if (java.isEmpty()) {
            return;
        }
        String image = java.get().toString();
        List<ProcessHandle> leftovers = ProcessHandle.current().descendants()
                .filter(ProcessHandle::isAlive)
                .filter(handle -> handle.info().command().map(image::equalsIgnoreCase).orElse(false))
                .toList();
        leftovers.forEach(ProcessHandle::destroyForcibly);
        for (ProcessHandle handle : leftovers) {
            try {
                handle.onExit().get(REAP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError("fake simulator pid " + handle.pid() + " did not exit", e);
            }
        }
    }
}
