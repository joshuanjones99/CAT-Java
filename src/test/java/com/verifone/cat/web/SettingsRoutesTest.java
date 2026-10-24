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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.posshim.FakeShim;
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

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        settingsFile = tmp.resolve("java").resolve("web-settings.json");
        app = WebApp.createWithSettings(client, "CAT-Java: test / topaz101", settingsFile)
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
    void theNotBuiltPartsNameThePythonModule() throws Exception {
        String page = get("/settings").body();

        assertTrue(page.contains("Ported from cat/web/pages/settings.py — not built yet"), page);
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
        app = WebApp.createWithSettings(client, "CAT-Java: test", blocker.resolve("web-settings.json"))
                .start(WebApp.BIND_HOST, 0);

        HttpResponse<String> response = post("/settings/appearance", "appearance=dark", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Could not write the settings file"), response.body());
        assertTrue(response.headers().allValues("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.startsWith("cat_appearance=dark")), response.headers().toString());
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
