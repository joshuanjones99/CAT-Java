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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.prefs.WebSettings;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.tools.RegisterTarget;

import io.javalin.Javalin;

/**
 * The POS page's check before its screen: no register, not connected, no shim (the Push shim to register button),
 * live; the register from a rig or from the Settings page's address. Over a fake {@link PosReach} and the Tools page's
 * fake tools, with the settings file in a temporary folder; nothing here reaches a register. Evidence about this
 * repository only.
 */
class PosRoutesTest {

    private static final RegisterTarget REG = new RegisterTarget("Reg101", "register.invalid", 8765, 9222);
    private static final String PUSH = PosPage.PUSH_PATH + "?host=register.invalid";

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String host = InetAddress.getLoopbackAddress().getHostAddress();
    private final AtomicReference<PosReach.State> state = new AtomicReference<>(PosReach.State.NOT_CONNECTED);
    private final AtomicReference<String> probed = new AtomicReference<>("");
    private ToolsWebTest.FakeTools fake;
    private RigBusy busy;
    private ToolsService tools;
    private Javalin app;

    @BeforeEach
    void setUp() {
        fake = new ToolsWebTest.FakeTools();
        busy = new RigBusy();
        fake.busy = busy;
        // No rig at all: a push needs none.
        tools = new ToolsService(() -> "", busy, fake,
                () -> new CommanderConnect("QAT", "op@example.com", "1234567", "secret"), Map.of(), Runnable::run);
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private void serve(PosRegister registers) {
        app = Javalin.create(config -> config.showJavalinBanner = false);
        PosRoutes.register(app, registers, null, (h, p) -> {
            probed.set(h + ":" + p);
            return state.get();
        }, tools);
        app.start(host, 0);
    }

    private void serve(RegisterTarget target) {
        serve(() -> new PosRegister.Resolved(target, ""));
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
        assertEquals("register.invalid:8765", probed.get());
    }

    @Test
    void aRegisterWithItsShimClosedOffersThePush() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);

        String page = get("/pos").body();

        assertTrue(page.contains("answers, but its pos-shim (port 8765) is not open"), page);
        assertTrue(page.contains(">Push shim to register</button>"), page);
        assertTrue(page.contains("hx-post=\"" + PUSH + "\""), page);
        assertTrue(page.contains("hx-confirm=\"Push the pos-shim to Reg101 (register.invalid)? Over SSH as root"),
                page);
        assertFalse(page.contains("<canvas"), page);
    }

    @Test
    void thePushProvisionsThatRegisterUnderTheBusyFlagWithNoRig() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);

        String panel = post(PUSH, true).body();

        assertEquals(List.of("push-shim Reg101 (register.invalid)"), fake.calls);
        assertEquals(RigBusy.SHIM_PUSH, fake.during.get());
        assertEquals("", busy.current());
        assertTrue(panel.contains("Push shim to register (Reg101) done."), panel);
    }

    @Test
    void aFailedPushShowsWhyAndItsLog() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        fake.result = false;
        serve(REG);

        String panel = post(PUSH, true).body();

        assertTrue(panel.contains("Push shim to register (Reg101) failed: push-shim Reg101 (register.invalid) went "
                + "wrong"), panel);
        assertTrue(panel.contains("working on push-shim Reg101 (register.invalid)"), panel);
    }

    @Test
    void thePushIsRefusedWhileSomethingHoldsTheRig() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);
        busy.begin(RigBusy.INSTALL);

        String page = get("/pos").body();
        String panel = post(PUSH, true).body();

        assertTrue(page.contains(" disabled>Push shim to register</button>"), page);
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
        assertEquals(1, panel.split("An install is already in progress", -1).length - 1, panel);
    }

    @Test
    void thePushComesFromThePageOnlyAndForTheRegisterItShowed() throws Exception {
        state.set(PosReach.State.NO_SHIM);
        serve(REG);

        assertEquals(403, post(PUSH, false).statusCode());
        String other = post(PosPage.PUSH_PATH + "?host=other.invalid", true).body();

        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
        assertTrue(other.contains("not other.invalid; nothing was pushed"), other);
    }

    @Test
    void theToolsPageCannotStartThePush() {
        assertEquals("Push shim to register starts from the POS page.",
                tools.start(ToolsService.Tool.PUSH_SHIM, "Reg101"));
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void aLiveShimShowsTheScreenAndThePanelReloadsIntoIt() throws Exception {
        state.set(PosReach.State.LIVE);
        serve(REG);

        String page = get("/pos").body();
        HttpResponse<String> panel = get(PosPage.PANEL_PATH);

        assertTrue(page.contains("<canvas id=\"pos-screen\""), page);
        assertTrue(page.contains("title=\"http://register.invalid:9222\""), page);
        assertEquals("true", panel.headers().firstValue("HX-Refresh").orElse(""));
    }

    @Test
    void withNoRegisterThePageSaysItCannotCheckAndPointsAtSettings() throws Exception {
        serve(PosRegister.none("Rig 'lab' did not load: No rig file"));

        String page = get("/pos").body();

        assertTrue(page.contains("Can't check the register: CAT doesn't know its address."), page);
        assertTrue(page.contains("did not load: No rig file"), page);
        assertTrue(page.contains("href=\"/settings#register\""), page);
        assertTrue(page.contains("hx-get=\"" + PosPage.PANEL_PATH + "\""), page);
        assertFalse(page.contains("Push shim to register"), page);
        // Nothing was probed: the register may be up, so the page never calls it not connected.
        assertFalse(page.contains("not connected"), page);
        assertEquals("", probed.get());
    }

    @Test
    void theSettingsAddressIsTheRegisterWhenNoRigNamesOne() throws Exception {
        Path settings = dir.resolve("web-settings.json");
        state.set(PosReach.State.NO_SHIM);
        serve(PosRegister.of(null, "Rig 'lab' did not load.", settings, 8801));

        String before = get("/pos").body();
        assertTrue(WebSettings.update(settings, WebSettings.REGISTER_HOST_KEY, "register.invalid"));
        String panel = get(PosPage.PANEL_PATH).body();
        String pushed = post(PUSH, true).body();

        assertTrue(before.contains("Rig &#39;lab&#39; did not load. Set the register&#39;s address on the Settings "
                + "page.") || before.contains("Set the register"), before);
        assertTrue(panel.contains("register.invalid answers, but its pos-shim (port 8801) is not open"), panel);
        assertEquals("register.invalid:8801", probed.get());
        assertEquals(List.of("push-shim register.invalid"), fake.calls);
        assertTrue(pushed.contains("Push shim to register (register.invalid) done."), pushed);
    }

    @Test
    void anUnusableSettingsAddressIsSaidNotProbed() throws Exception {
        Path settings = dir.resolve("web-settings.json");
        WebSettings.update(settings, WebSettings.REGISTER_HOST_KEY, "http://register.invalid/");
        serve(PosRegister.of(null, "", settings, 8801));

        String page = get("/pos").body();

        assertTrue(page.contains("is not usable"), page);
        assertEquals("", probed.get());
    }

    @Test
    void aRigRegisterWinsOverTheSettingsAddress() {
        Path settings = dir.resolve("web-settings.json");
        WebSettings.update(settings, WebSettings.REGISTER_HOST_KEY, "other.invalid");

        PosRegister.Resolved now = PosRegister.of(REG, "", settings, 8801).current();

        assertEquals(REG, now.target());
    }
}
