package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.tools.RegisterTarget;
import com.verifone.cat.tools.RigTools;

import io.javalin.Javalin;

/**
 * The Tools page over a fake {@link RigTools}: what each button starts, the shared busy flag, the refusals, and the
 * routes' cross-site guard. Evidence about this repository only; nothing here reaches a rig.
 */
class ToolsWebTest {

    private static final CommanderConnect GOOD = new CommanderConnect("QAT", "op@example.com", "1234567", "secret");

    /** Records each call; {@link #result} is what every tool returns, {@link #during} what it sees while running. */
    static final class FakeTools implements RigTools {
        final List<String> calls = new CopyOnWriteArrayList<>();
        boolean result = true;
        RuntimeException failure;
        RigBusy busy;
        final AtomicReference<String> during = new AtomicReference<>("");
        List<String> registers = List.of("Reg101");

        private boolean run(String call, Say say) {
            calls.add(call);
            if (busy != null) {
                during.set(busy.current());
            }
            say.out("working on " + call);
            if (failure != null) {
                throw failure;
            }
            if (!result) {
                say.err("first problem");
                say.err(call + " went wrong");
            }
            return result;
        }

        @Override
        public List<String> registers(String rigName) {
            return registers;
        }

        @Override
        public boolean pair(String rigName, Say say) {
            return run("pair " + rigName, say);
        }

        @Override
        public boolean pairLab(Say say) {
            return run("pair-lab", say);
        }

        @Override
        public boolean onboard(String rigName, CommanderConnect settings, Say say) {
            return run("onboard " + rigName + " " + settings.lab() + " " + settings.password(), say);
        }

        @Override
        public boolean rebootRegister(String rigName, String registerId, Say say) {
            return run("reboot-c18 " + rigName + " " + registerId, say);
        }

        @Override
        public boolean rebootCommander(String rigName, Say say) {
            return run("reboot-commander " + rigName, say);
        }

        @Override
        public boolean rebootCommanderAndRegisters(String rigName, Say say) {
            return run("reboot-commander-c18 " + rigName, say);
        }

        @Override
        public boolean pushShim(RegisterTarget register, Say say) {
            return run("push-shim " + register.describe(), say);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final String host = InetAddress.getLoopbackAddress().getHostAddress();
    private FakeTools fake;
    private RigBusy busy;
    private CommanderConnect connect;
    private Javalin app;

    private ToolsService service(String rig, Map<String, String> env) {
        return new ToolsService(() -> rig, busy, fake, () -> connect, env, Runnable::run);
    }

    @BeforeEach
    void setUp() {
        fake = new FakeTools();
        busy = new RigBusy();
        fake.busy = busy;
        connect = GOOD;
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private void serve(ToolsService tools) {
        app = Javalin.create(config -> config.showJavalinBanner = false);
        ToolsRoutes.register(app, tools);
        app.start(host, 0);
    }

    private HttpResponse<String> post(String path, boolean htmx) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + app.port() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + app.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePanelShowsTheToolsInOrder() {
        String html = ToolsPage.panel(service("lab", Map.of()));

        int last = -1;
        for (String title : List.of("POS Pairing", "Onboard to Commander Connect", "Reboot C18", "Reboot Commander",
                "Reboot Commander + C18")) {
            int at = html.indexOf(">" + title + "</button>");
            assertTrue(at > last, title + " is missing or out of order: " + html);
            last = at;
        }
        assertTrue(html.contains("hx-post=\"/tools/reboot-c18?register=Reg101\""), html);
        assertTrue(html.contains("hx-confirm=\"Reboot Reg101 on rig lab?"), html);
        assertTrue(html.contains("<ul class=\"tool-list\">"), "one vertical list: " + html);
        assertFalse(html.contains("<p>"), "no descriptions: " + html);
        assertFalse(html.contains("class=\"warn\""), "no warning when the setup is right: " + html);
        assertFalse(html.contains(" disabled"), "nothing blocks a tool: " + html);
        assertFalse(html.contains("secret"), "the password is never shown: " + html);
    }

    @Test
    void eachRegisterGetsItsOwnRebootButtonWhenThereAreSeveral() {
        fake.registers = List.of("Reg101", "Reg102");

        String html = ToolsPage.panel(service("lab", Map.of()));

        assertTrue(html.contains(">Reboot C18 (Reg101)</button>"), html);
        assertTrue(html.contains(">Reboot C18 (Reg102)</button>"), html);
        assertTrue(html.contains("hx-post=\"/tools/reboot-commander-c18\""), "one button reboots them all: " + html);
        assertTrue(html.contains("hx-confirm=\"Reboot the Commander and Reg101, Reg102 on rig lab together?"), html);
    }

    @Test
    void withNoRigOnlyPosPairingRunsAndItPairsTheLabRegisters() {
        ToolsService tools = service("", Map.of());

        String html = ToolsPage.panel(tools);

        assertTrue(html.contains("No rig: POS Pairing pairs the lab&#39;s registers 101 and 102")
                || html.contains("No rig: POS Pairing pairs the lab"), html);
        // Onboard and the three reboots stay disabled; POS Pairing does not.
        assertEquals(4, html.split(" disabled>", -1).length - 1, html);
        assertTrue(html.contains(" disabled>Reboot Commander + C18</button>"), html);
        assertTrue(tools.start(ToolsService.Tool.REBOOT_ALL, "").startsWith("No rig"));
        assertFalse(html.contains(" disabled>POS Pairing</button>"), html);
        assertTrue(html.contains("Pair the lab&#39;s registers (Reg101, Reg102: those that answer)")
                || html.contains("Pair the lab"), html);
        assertTrue(tools.start(ToolsService.Tool.REBOOT_COMMANDER, "").startsWith("No rig"));
        assertEquals(List.of(), fake.calls);

        assertEquals("", tools.start(ToolsService.Tool.PAIR, ""));

        assertEquals(List.of("pair-lab"), fake.calls);
        assertEquals(RigBusy.PAIRING, fake.during.get());
        assertEquals("", busy.current());
        assertEquals("done", tools.outcome(ToolsService.Tool.PAIR).state());
        assertTrue(tools.outcome(ToolsService.Tool.PAIR).message().contains("POS Pairing (lab) done."));
    }

    @Test
    void aNamedRigThatDoesNotLoadPairsTheLabRegistersToo() {
        // run.cmd always names "lab"; on a PC with no lab.yaml it does not load (user, 2026-11-10: "cannot load rig
        // lab: No rig file at C:\CICD\Tools\rigs\lab.yaml").
        fake.registers = List.of();
        ToolsService tools = service("lab", Map.of());

        String html = ToolsPage.panel(tools);

        assertTrue(html.contains("Rig lab does not load: POS Pairing pairs the lab"), html);
        assertFalse(html.contains(" disabled>POS Pairing</button>"), html);
        assertTrue(tools.start(ToolsService.Tool.REBOOT_COMMANDER, "").startsWith("Rig lab does not load"));
        assertEquals("", tools.start(ToolsService.Tool.PAIR, ""));
        assertEquals(List.of("pair-lab"), fake.calls);
    }

    @Test
    void withNoRigPosPairingStillWaitsForTheSharedFlag() {
        ToolsService tools = service("", Map.of());
        busy.begin(RigBusy.INSTALL);

        assertTrue(ToolsPage.panel(tools).contains(" disabled>POS Pairing</button>"));
        assertTrue(tools.start(ToolsService.Tool.PAIR, "").contains("install"));
        assertEquals(List.of(), fake.calls);
    }

    @Test
    void eachButtonStartsItsToolHoldingTheRigAndFreesItAfter() throws Exception {
        serve(service("lab", Map.of()));

        assertEquals(200, post("/tools/pair", true).statusCode());
        assertEquals(RigBusy.PAIRING, fake.during.get());
        post("/tools/onboard", true);
        assertEquals(RigBusy.ONBOARDING, fake.during.get());
        post("/tools/reboot-c18?register=Reg101", true);
        assertEquals(RigBusy.REBOOT, fake.during.get());
        post("/tools/reboot-commander", true);
        assertEquals(RigBusy.REBOOT, fake.during.get());
        fake.during.set("");
        String panel = post("/tools/reboot-commander-c18", true).body();
        assertEquals(RigBusy.REBOOT, fake.during.get());

        assertEquals(List.of("pair lab", "onboard lab QAT secret", "reboot-c18 lab Reg101", "reboot-commander lab",
                "reboot-commander-c18 lab"), fake.calls);
        assertTrue(panel.contains("Reboot Commander + C18 done."), panel);
        assertEquals("", busy.current(), "the flag is given back");
        assertTrue(panel.contains("Reboot Commander done."), panel);
        assertTrue(panel.contains("POS Pairing done."), panel);
        assertTrue(panel.contains("working on reboot-commander-c18 lab"), "the log shows the tool's lines: " + panel);
    }

    @Test
    void aFailureIsReportedWithItsLastErrorLine() {
        fake.result = false;
        ToolsService tools = service("lab", Map.of());

        tools.start(ToolsService.Tool.REBOOT_COMMANDER, "");

        ToolsService.Outcome outcome = tools.outcome(ToolsService.Tool.REBOOT_COMMANDER);
        assertEquals("failed", outcome.state());
        assertEquals("Reboot Commander failed: reboot-commander lab went wrong", outcome.message());
        assertEquals("", busy.current());
    }

    @Test
    void aToolThatThrowsStillFreesTheRig() {
        fake.failure = new IllegalStateException("boom");
        ToolsService tools = service("lab", Map.of());

        tools.start(ToolsService.Tool.PAIR, "");

        assertEquals("failed", tools.outcome(ToolsService.Tool.PAIR).state());
        assertTrue(tools.outcome(ToolsService.Tool.PAIR).message().contains("boom"));
        assertEquals("", busy.current());
        assertNull(tools.running());
    }

    @Test
    void aToolIsRefusedWhileSomethingElseHoldsTheRig() {
        busy.begin(RigBusy.INSTALL);
        ToolsService tools = service("lab", Map.of());

        assertEquals("An install is already in progress.", tools.start(ToolsService.Tool.REBOOT_COMMANDER, ""));
        assertEquals(List.of(), fake.calls);
        assertTrue(ToolsPage.panel(tools).contains("An install is already in progress."));
    }

    @Test
    void aRunIsRefusedWhileAToolHoldsTheRig() {
        busy.begin(RigBusy.REBOOT);

        assertEquals("A reboot is already in progress.", busy.refusal());
    }

    @Test
    void anUnknownRegisterIsRefused() {
        ToolsService tools = service("lab", Map.of());

        assertEquals("Rig lab has no register Reg999.", tools.start(ToolsService.Tool.REBOOT_C18, "Reg999"));
        assertEquals("No register named to reboot.", tools.start(ToolsService.Tool.REBOOT_C18, ""));
        assertEquals(List.of(), fake.calls);
    }

    @Test
    void onboardingIsRefusedUntilTheConfigTabIsSetUp() {
        connect = new CommanderConnect("Prod", "", "", "");
        ToolsService tools = service("lab", Map.of());

        String why = tools.start(ToolsService.Tool.ONBOARD, "");

        assertTrue(why.startsWith("Commander Connect is not set up on the Config tab"), why);
        assertEquals(List.of(), fake.calls);
        String html = ToolsPage.panel(tools);
        assertTrue(html.contains(" disabled>Onboard to Commander Connect</button>"), html);
        assertTrue(html.contains("<span class=\"warn\">Set on the <a href=\"/run/config\">Config tab</a> first: "
                + "email is empty or not an address"), "the setup warning stays: " + html);
    }

    @Test
    void onboardingTakesThePasswordFromTheEnvironmentWhenTheConfigTabHasNone() {
        connect = new CommanderConnect("CST", "op@example.com", "1234567", "");
        ToolsService tools = service("lab", Map.of(CommanderConnect.PASSWORD_ENV, "from-env"));

        assertEquals("", tools.start(ToolsService.Tool.ONBOARD, ""));
        assertEquals(List.of("onboard lab CST from-env"), fake.calls);
    }

    @Test
    void aPostWithoutHtmxIsRefusedAndRunsNothing() throws Exception {
        serve(service("lab", Map.of()));

        HttpResponse<String> response = post("/tools/reboot-commander", false);

        assertEquals(403, response.statusCode());
        assertEquals(List.of(), fake.calls);
    }

    @Test
    void thePanelFragmentIsServed() throws Exception {
        serve(service("lab", Map.of()));

        HttpResponse<String> response = get(ToolsPage.PATH);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("<div id=\"tools-panel\""), response.body());
    }
}
