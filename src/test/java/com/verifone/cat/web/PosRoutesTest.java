package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.shell.RigBusy;

import io.javalin.Javalin;

/**
 * The POS page's check before its screen: not connected, no shim (the Push shim to register button), live. Over a
 * fake {@link PosReach} and the Tools page's fake tools; nothing here reaches a register. Evidence about this
 * repository only.
 */
class PosRoutesTest {

    private static final PosPage.Target REG = new PosPage.Target("Reg101", "register.invalid", 8765);
    private static final String CDP = "http://register.invalid:9222";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String host = InetAddress.getLoopbackAddress().getHostAddress();
    private final AtomicReference<PosReach.State> state = new AtomicReference<>(PosReach.State.NOT_CONNECTED);
    private ToolsWebTest.FakeTools fake;
    private RigBusy busy;
    private ToolsService tools;
    private Javalin app;

    @BeforeEach
    void setUp() {
        fake = new ToolsWebTest.FakeTools();
        busy = new RigBusy();
        fake.busy = busy;
        tools = new ToolsService(() -> "lab", busy, fake,
                () -> new CommanderConnect("QAT", "op@example.com", "1234567", "secret"), Map.of(), Runnable::run);
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private void serve(PosPage.Target target) {
        app = Javalin.create(config -> config.showJavalinBanner = false);
        PosRoutes.register(app, CDP, target, "Rig 'lab' did not load: No rig file", (h, p) -> state.get(), tools);
        app.start(host, 0);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + app.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, boolean htmx) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + app.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRegisterThatDoesNotAnswerIsNotConnectedAndHasNoButton() throws Exception {
        serve(REG);

        String page = get("/pos").body();

        assertTrue(page.contains("Register not connected: Reg101 (register.invalid) does not answer"), page);
        assertFalse(page.contains("Push shim to register"), page);
        assertFalse(page.contains("<canvas"), page);
        assertTrue(page.contains("hx-get=\"" + PosPage.PANEL_PATH + "\""), page);
    }

    @Test
    void aRegisterWithItsShimClosedOffersThePush() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);

        String page = get("/pos").body();

        assertTrue(page.contains("answers, but its pos-shim (port 8765) is not open"), page);
        assertTrue(page.contains(">Push shim to register</button>"), page);
        assertTrue(page.contains("hx-post=\"" + PosPage.PUSH_PATH + "?register=Reg101\""), page);
        assertTrue(page.contains("hx-confirm=\"Push the pos-shim to Reg101 on rig lab?"), page);
        assertFalse(page.contains("<canvas"), page);
    }

    @Test
    void thePushProvisionsThatRegisterUnderTheBusyFlag() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);

        String panel = post(PosPage.PUSH_PATH + "?register=Reg101", true).body();

        assertEquals(List.of("push-shim lab Reg101"), fake.calls);
        assertEquals(RigBusy.SHIM_PUSH, fake.during.get());
        assertEquals("", busy.current());
        assertTrue(panel.contains("Push shim to register (Reg101) done."), panel);
    }

    @Test
    void aFailedPushShowsWhyAndItsLog() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        fake.result = false;
        serve(REG);

        String panel = post(PosPage.PUSH_PATH + "?register=Reg101", true).body();

        assertTrue(panel.contains("Push shim to register (Reg101) failed: push-shim lab Reg101 went wrong"), panel);
        assertTrue(panel.contains("working on push-shim lab Reg101"), panel);
    }

    @Test
    void thePushIsRefusedWhileSomethingHoldsTheRig() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);
        busy.begin(RigBusy.INSTALL);

        String page = get("/pos").body();
        String panel = post(PosPage.PUSH_PATH + "?register=Reg101", true).body();

        assertTrue(page.contains(" disabled>Push shim to register</button>"), page);
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
        assertEquals(1, panel.split("An install is already in progress", -1).length - 1, panel);
    }

    @Test
    void thePushComesFromThePageOnlyAndForItsRegisterOnly() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);

        assertEquals(403, post(PosPage.PUSH_PATH + "?register=Reg101", false).statusCode());
        String other = post(PosPage.PUSH_PATH + "?register=Reg102", true).body();

        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
        assertTrue(other.contains("nothing was pushed"), other);
    }

    @Test
    void aLiveShimShowsTheScreenAndThePanelReloadsIntoIt() throws Exception {
        state.set(PosReach.State.LIVE);
        serve(REG);

        String page = get("/pos").body();
        HttpResponse<String> panel = get(PosPage.PANEL_PATH);

        assertTrue(page.contains("<canvas id=\"pos-screen\""), page);
        assertEquals("true", panel.headers().firstValue("HX-Refresh").orElse(""));
    }

    @Test
    void withNoRegisterThePageSaysWhyItIsNotConnected() throws Exception {
        app = Javalin.create(config -> config.showJavalinBanner = false);
        PosRoutes.register(app, null, null, "Rig 'lab' did not load: No rig file", (h, p) -> {
            throw new AssertionError("nothing to probe");
        }, tools);
        app.start(host, 0);

        String page = get("/pos").body();

        assertTrue(page.contains("Can't check the register"), page);
        assertTrue(page.contains("Rig &#39;lab&#39; did not load: No rig file") || page.contains("Rig 'lab' did not load"),
                page);
        assertFalse(page.contains("Push shim to register"), page);
        // Nothing was probed: the register may be up, so the page never calls it not connected.
        assertFalse(page.contains("not connected"), page);
    }
}
