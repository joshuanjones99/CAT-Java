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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.desktop.DesktopHost;
import com.verifone.cat.desktop.Shortcuts;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.prefs.WebSettings.OpenIn;
import com.verifone.cat.posshim.PosShimClient;

import io.javalin.Javalin;

/**
 * The Settings page over HTTP, with its preferences file in a temp folder. Nothing here reads or
 * writes the operator's {@code ~/.cat}, and nothing reaches a device: the shim is a {@link FakeShim}.
 */
class SettingsRoutesTest {

    @TempDir
    Path tmp;

    private FakeShim shim;
    private Path settingsFile;
    private Javalin app;
    private final HttpClient http = HttpClient.newBuilder().build();

    /** The desktop, faked: it records what it was asked to open and never opens or writes anything. */
    private final List<String> opened = new ArrayList<>();
    private int shortcutsAdded;
    private final DesktopHost desktop = new DesktopHost() {
        @Override
        public String open(String url, OpenIn how) {
            opened.add(how.value() + " " + url);
            return "opened " + url;
        }

        @Override
        public String openInBrowser(String url) {
            opened.add("browser " + url);
            return "opened the browser on " + url;
        }

        @Override
        public Shortcuts.Outcome addShortcuts() {
            shortcutsAdded++;
            return new Shortcuts.Outcome(true, List.of("CAT-Java shortcut: D:\\Desktop"));
        }
    };

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        settingsFile = tmp.resolve("java").resolve("web-settings.json");
        app = WebApp.createWithSettings(client, "CAT-Java: test / topaz101", settingsFile, desktop)
                .start(WebApp.BIND_HOST, 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        shim.close();
    }

    @Test
    void theSettingsPageShowsTheThreeAppearanceChoicesWithMatchWindowsChecked() throws Exception {
        HttpResponse<String> response = get("/settings");
        String page = response.body();

        assertEquals(200, response.statusCode());
        assertTrue(page.contains("<h1>Settings</h1>"), page);
        assertTrue(page.contains("value=\"auto\" checked"), page);
        assertTrue(page.contains("<span>Match Windows</span>"), page);
        assertTrue(page.contains("value=\"light\">"), page);
        assertTrue(page.contains("value=\"dark\">"), page);
        assertTrue(page.contains("href=\"/settings\" class=\"active\" aria-current=\"page\""), page);
    }

    @Test
    void theSettingsPageListsWhereCatKeepsThingsWithTheirEnvironmentNames() throws Exception {
        String page = get("/settings").body();

        assertTrue(page.contains("<h2>Where CAT keeps things</h2>"), page);
        for (String name : List.of(
                "Run results", "Results index", "Downloaded builds", "Simulators and tools",
                "petroapps checkout", "Config choices", "Web settings", "Rig files", "Plan catalogue cache")) {
            assertTrue(page.contains("<dt>" + name + "</dt>"), name + ": " + page);
        }
        assertTrue(page.contains("<code>$CAT_WEB_SETTINGS</code>"), page);
        assertTrue(page.contains("<code>$CAT_RESULTS_DIR</code>"), page);
    }

    @Test
    void shutDownCatAsksFirstAndSaysWhatItStops() throws Exception {
        String page = get("/settings").body();

        assertTrue(page.contains("Shut down CAT?"), page);
        assertTrue(page.contains("hx-post=\"/_cat/shutdown\""), page);
        assertTrue(page.contains("Every open CAT page stops working until CAT is started again."), page);
    }

    @Test
    void theChoiceIsSavedToTheFileAndAnnouncedWithAPrefsCookie() throws Exception {
        HttpResponse<String> response = post("/settings/appearance", "appearance=dark", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Saved. Every page now shows Dark."), response.body());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("cat_appearance=dark")), response.headers().toString());
        assertTrue(Files.readString(settingsFile, StandardCharsets.UTF_8).contains("\"appearance\" : \"dark\""));
    }

    @Test
    void theSavedChoiceIsCheckedWhenThePageIsOpenedAgain() throws Exception {
        post("/settings/appearance", "appearance=light", true);

        String page = get("/settings").body();

        assertTrue(page.contains("value=\"light\" checked"), page);
        assertFalse(page.contains("value=\"auto\" checked"), page);
    }

    @Test
    void aSettingsPageVisitRemembersTheChoiceFromTheFile() throws Exception {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{\"appearance\": \"dark\"}", StandardCharsets.UTF_8);

        HttpResponse<String> response = get("/settings");

        assertTrue(response.body().contains("value=\"dark\" checked"), response.body());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("cat_appearance=dark")), response.headers().toString());
    }

    @Test
    void aSaveKeepsTheOtherKeysAlreadyInTheFile() throws Exception {
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{\"sidebar_collapsed\": true}", StandardCharsets.UTF_8);

        post("/settings/appearance", "appearance=auto", true);

        String saved = Files.readString(settingsFile, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"sidebar_collapsed\" : true"), saved);
        assertTrue(saved.contains("\"appearance\" : \"auto\""), saved);
    }

    @Test
    void aChangeThatDoesNotComeFromThePageIsRefusedAndNotSaved() throws Exception {
        HttpResponse<String> response = post("/settings/appearance", "appearance=dark", false);

        assertEquals(403, response.statusCode());
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void anUnknownAppearanceIsRefusedAndTheFileIsUntouched() throws Exception {
        HttpResponse<String> response = post("/settings/appearance", "appearance=neon", true);

        assertEquals(400, response.statusCode());
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void aSaveThatCannotWriteSaysSoAndStillAppliesInThisBrowser() throws Exception {
        // The file's parent is a regular file, so the folder cannot be created.
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);
        app.stop();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        app = WebApp.createWithSettings(client, "CAT-Java: test", blocker.resolve("web-settings.json"), desktop)
                .start(WebApp.BIND_HOST, 0);

        HttpResponse<String> response = post("/settings/appearance", "appearance=dark", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Could not write the settings file"), response.body());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("cat_appearance=dark")), response.headers().toString());
    }

    @Test
    void openingCatOffersItsOwnWindowCheckedByDefaultAndTheBrowser() throws Exception {
        String page = get("/settings").body();

        assertTrue(page.contains("<h2>Opening CAT</h2>"), page);
        assertTrue(page.contains("name=\"open_in\" value=\"window\" checked"), page);
        assertTrue(page.contains("<span>Its own window (recommended)</span>"), page);
        assertTrue(page.contains("name=\"open_in\" value=\"browser\">"), page);
        assertTrue(page.contains("hx-post=\"/settings/open-browser\""), page);
        assertTrue(page.contains("hx-post=\"/settings/shortcut\""), page);
        assertTrue(page.contains("runs run.cmd web with no console window"), page);
    }

    @Test
    void theOpenInChoiceIsSavedBesideTheAppearanceAndCheckedNextTime() throws Exception {
        post("/settings/appearance", "appearance=dark", true);
        HttpResponse<String> response = post("/settings/open-in", "open_in=browser", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("CAT opens in the browser from its next start"), response.body());
        String saved = Files.readString(settingsFile, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"open_in\" : \"browser\""), saved);
        assertTrue(saved.contains("\"appearance\" : \"dark\""), saved);
        assertTrue(get("/settings").body().contains("name=\"open_in\" value=\"browser\" checked"));
        assertTrue(opened.isEmpty(), "saving opens nothing: " + opened);
    }

    @Test
    void anUnknownOpenInOrOneNotFromThePageIsRefused() throws Exception {
        assertEquals(400, post("/settings/open-in", "open_in=tray", true).statusCode());
        assertEquals(403, post("/settings/open-in", "open_in=browser", false).statusCode());
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void openInTheBrowserNowOpensTheDefaultBrowserOnThisSettingsPage() throws Exception {
        HttpResponse<String> response = post("/settings/open-browser", "", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Opened CAT in the browser."), response.body());
        assertEquals(List.of("browser http://" + WebApp.BIND_HOST + ":" + app.port() + "/settings"), opened);
        assertEquals(403, post("/settings/open-browser", "", false).statusCode());
        assertEquals(1, opened.size());
    }

    @Test
    void addTheShortcutAsksTheDesktopOnceAndShowsWhatItDid() throws Exception {
        assertEquals(403, post("/settings/shortcut", "", false).statusCode());
        assertEquals(0, shortcutsAdded);

        HttpResponse<String> response = post("/settings/shortcut", "", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("CAT-Java shortcut: D:\\Desktop"), response.body());
        assertEquals(1, shortcutsAdded);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form, boolean htmx)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + WebApp.BIND_HOST + ":" + app.port() + path);
    }
}
