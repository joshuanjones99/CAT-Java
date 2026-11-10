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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.tools.LabRegisters;
import com.verifone.cat.tools.RegisterTarget;

import io.javalin.Javalin;

/**
 * The POS page's check before its screen, over two registers: not connected, no shim (the Push shim to register
 * button), live; the first live one shown. Over a fake {@link PosReach} and the Tools page's fake tools; nothing here
 * reaches a register. Evidence about this repository only.
 */
class PosRoutesTest {

    private static final RegisterTarget ONE = new RegisterTarget("Reg101", "one.invalid", 8801, 9222);
    private static final RegisterTarget TWO = new RegisterTarget("Reg102", "two.invalid", 8801, 9222);

    private final HttpClient http = HttpClient.newHttpClient();
    private final String host = InetAddress.getLoopbackAddress().getHostAddress();
    /** Each host's state; a host not here does not answer. */
    private final Map<String, PosReach.State> states = new ConcurrentHashMap<>();
    private final Set<String> probed = ConcurrentHashMap.newKeySet();
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
            probed.add(h + ":" + p);
            return states.getOrDefault(h, PosReach.State.NOT_CONNECTED);
        }, tools);
        app.start(host, 0);
    }

    private void serveBoth() {
        serve(PosRegister.fixed(List.of(ONE, TWO)));
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
    void withNoRigTheLabsRegisters101And102AreChecked() {
        assertEquals(List.of("Reg101", "Reg102"),
                PosRegister.of(List.of()).current().targets().stream().map(RegisterTarget::id).toList());
        assertEquals(LabRegisters.ALL, PosRegister.of(null).current().targets());
    }

    @Test
    void aRigsRegistersWinOverTheLabs() {
        assertEquals(List.of(ONE), PosRegister.of(List.of(ONE)).current().targets());
    }

    @Test
    void bothCheckedAndNeitherAnsweringIsNotConnectedForEach() throws Exception {
        serveBoth();

        String page = get("/pos").body();

        assertTrue(page.contains("Register not connected: Reg101 (one.invalid) does not answer."), page);
        assertTrue(page.contains("Register not connected: Reg102 (two.invalid) does not answer."), page);
        assertFalse(page.contains("Push shim to register"), page);
        assertFalse(page.contains("<canvas"), page);
        assertTrue(page.contains("hx-get=\"" + PosPage.PANEL_PATH + "\""), page);
        assertEquals(Set.of("one.invalid:8801", "two.invalid:8801"), probed);
    }

    @Test
    void aRegisterWithItsShimClosedGetsThePushAndTheOtherSaysItsOwnState() throws Exception {
        states.put("one.invalid", PosReach.State.NO_SHIM);
        serveBoth();

        String page = get("/pos").body();

        assertTrue(page.contains("Reg101 (one.invalid) answers, but its pos-shim (port 8801) is not open."), page);
        assertTrue(page.contains("hx-post=\"" + PosPage.PUSH_PATH + "?host=one.invalid\""), page);
        assertEquals(1, page.split(">Push shim to register</button>", -1).length - 1, page);
        assertTrue(page.contains("hx-confirm=\"Push the pos-shim to Reg101 (one.invalid)? Over SSH as root"), page);
        assertTrue(page.contains("Register not connected: Reg102 (two.invalid) does not answer."), page);
    }

    @Test
    void theFirstLiveRegisterIsShownThroughItsOwnSocket() throws Exception {
        states.put("one.invalid", PosReach.State.NO_SHIM);
        states.put("two.invalid", PosReach.State.LIVE);
        serveBoth();

        String page = get("/pos").body();
        HttpResponse<String> panel = get(PosPage.PANEL_PATH);

        assertTrue(page.contains("<canvas id=\"pos-screen\""), page);
        assertTrue(page.contains("title=\"http://two.invalid:9222\""), page);
        assertTrue(page.contains("data-socket=\"" + PosScreenRoutes.PATH + "?host=two.invalid\""), page);
        assertEquals("true", panel.headers().firstValue("HX-Refresh").orElse(""));
    }

    @Test
    void thePushProvisionsThatRegisterUnderTheBusyFlagWithNoRig() throws Exception {
        states.put("two.invalid", PosReach.State.NO_SHIM);
        serveBoth();

        String panel = post(PosPage.PUSH_PATH + "?host=two.invalid", true).body();

        assertEquals(List.of("push-shim Reg102 (two.invalid)"), fake.calls);
        assertEquals(RigBusy.SHIM_PUSH, fake.during.get());
        assertEquals("", busy.current());
        assertTrue(panel.contains("Push shim to register (Reg102) done."), panel);
        // The outcome sits on Reg102's row, not Reg101's.
        String reg101 = panel.substring(panel.indexOf("pos-register-Reg101"), panel.indexOf("pos-register-Reg102"));
        assertFalse(reg101.contains("done."), reg101);
    }

    @Test
    void aFailedPushShowsWhyAndItsLog() throws Exception {
        states.put("one.invalid", PosReach.State.NO_SHIM);
        fake.result = false;
        serveBoth();

        String panel = post(PosPage.PUSH_PATH + "?host=one.invalid", true).body();

        assertTrue(panel.contains("Push shim to register (Reg101) failed: push-shim Reg101 (one.invalid) went wrong"),
                panel);
        assertTrue(panel.contains("working on push-shim Reg101 (one.invalid)"), panel);
    }

    @Test
    void thePushIsRefusedWhileSomethingHoldsTheRig() throws Exception {
        states.put("one.invalid", PosReach.State.NO_SHIM);
        serveBoth();
        busy.begin(RigBusy.INSTALL);

        String page = get("/pos").body();
        String panel = post(PosPage.PUSH_PATH + "?host=one.invalid", true).body();

        assertTrue(page.contains(" disabled>Push shim to register</button>"), page);
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
        assertEquals(1, panel.split("An install is already in progress", -1).length - 1, panel);
    }

    @Test
    void thePushComesFromThePageOnlyAndOnlyForOneOfItsRegisters() throws Exception {
        states.put("one.invalid", PosReach.State.NO_SHIM);
        serveBoth();

        assertEquals(403, post(PosPage.PUSH_PATH + "?host=one.invalid", false).statusCode());
        String other = post(PosPage.PUSH_PATH + "?host=elsewhere.invalid", true).body();

        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
        assertTrue(other.contains("elsewhere.invalid is not one of this page&#39;s registers; nothing was pushed")
                || other.contains("is not one of this page"), other);
    }

    @Test
    void theToolsPageCannotStartThePush() {
        assertEquals("Push shim to register starts from the POS page.",
                tools.start(ToolsService.Tool.PUSH_SHIM, "Reg101"));
        assertTrue(fake.calls.isEmpty(), fake.calls.toString());
    }

    @Test
    void theScreenSocketReachesOnlyARegisterOnTheList() {
        PosRegister.Resolved now = PosRegister.fixed(List.of(ONE, TWO)).current();

        assertEquals(TWO, now.byHost("two.invalid"));
        assertEquals(null, now.byHost("elsewhere.invalid"));
    }

    @Test
    void withNoRegisterThePageSaysItCannotCheckAndProbesNothing() throws Exception {
        serve(PosRegister.none("nothing to check here"));

        String page = get("/pos").body();

        assertTrue(page.contains("Can't check the register: CAT has none to check."), page);
        assertTrue(page.contains("nothing to check here"), page);
        assertFalse(page.contains("not connected"), page);
        assertTrue(probed.isEmpty(), probed.toString());
    }
}
