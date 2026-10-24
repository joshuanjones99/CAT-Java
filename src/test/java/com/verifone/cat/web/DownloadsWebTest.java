package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.FakeArtifactory;

import io.javalin.Javalin;

/**
 * The Downloads fragment and its POSTs over HTTP, against {@link FakeArtifactory} and a temp builds
 * root. Nothing reaches the real Artifactory or C:\CICD. Evidence about this repository only.
 */
class DownloadsWebTest {

    private static final String DATE = "2026-10-07T09:00:00.000Z";
    private static final byte[] OVERLAY = "overlay-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INSTALLER = "installer-jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUTO = "auto-jar".getBytes(StandardCharsets.UTF_8);
    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path root;

    private FakeArtifactory fake;
    private DownloadsService downloads;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String host = InetAddress.getLoopbackAddress().getHostAddress();

    @BeforeEach
    void start() {
        fake = new FakeArtifactory(tree());
        downloads = new DownloadsService(() -> fake.client(), root);
        app = Javalin.create(config -> config.showJavalinBanner = false);
        DownloadsRoutes.register(app, downloads);
        app.start(host, 0);
    }

    @AfterEach
    void stop() {
        app.stop();
        fake.close();
    }

    /** Solutions 1082 holds buypass and a viper-only folder; 1083 holds only viper-buypass; master 268 holds buypass. */
    static Map<String, Object> tree() {
        return Map.of("RMS_Nightly_CLW", FakeArtifactory.dir(DATE, "Petro", FakeArtifactory.dir(DATE,
                "Solutions", FakeArtifactory.dir(DATE,
                        "1082", FakeArtifactory.dir(DATE,
                                "buypass", FakeArtifactory.dir(DATE,
                                        "overlay.zip", OVERLAY,
                                        "Petro_Suite_Installer.jar", INSTALLER,
                                        "Auto_Upgrade.jar", AUTO),
                                "viper-buypass", FakeArtifactory.dir(DATE, "overlay.zip", OVERLAY)),
                        "1083", FakeArtifactory.dir(DATE,
                                "viper-buypass", FakeArtifactory.dir(DATE, "overlay.zip", OVERLAY))),
                "master", FakeArtifactory.dir(DATE,
                        "268", FakeArtifactory.dir(DATE,
                                "buypass", FakeArtifactory.dir(DATE,
                                        "overlay.zip", OVERLAY,
                                        "Petro_Suite_Installer.jar", INSTALLER,
                                        "Auto_Upgrade.jar", AUTO))))));
    }

    private String uri(String path) {
        return "http://" + host + ":" + app.port() + path;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(uri(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** An htmx POST, with its form value; {@code htmx} false leaves the HX-Request header off. */
    private HttpResponse<String> post(String path, String value, boolean htmx)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("value="
                        + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> set(String field, String value) throws IOException, InterruptedException {
        HttpResponse<String> response = post("/downloads/set?field=" + field, value, true);
        downloads.awaitIdle(WAIT);
        return response;
    }

    /** The panel once its listings have answered: the first GET starts them, so it is asked for twice. */
    private String panelAfterListings() throws IOException, InterruptedException {
        get(DownloadsPanel.PATH);
        downloads.awaitIdle(WAIT);
        return get(DownloadsPanel.PATH).body();
    }

    @Test
    void panelListsBranchesFromArtifactory() throws Exception {
        String html = panelAfterListings();

        assertTrue(html.contains("id=\"downloads-panel\""), html);
        assertTrue(html.contains("<option value=\"Solutions\">Solutions</option>"), html);
        assertTrue(html.contains("<option value=\"master\">master</option>"), html);
        assertTrue(html.contains("2 branches on Artifactory"), html);
    }

    @Test
    void chosenBranchListsItsBuilds() throws Exception {
        HttpResponse<String> response = set("commanderBranch", "Solutions");

        assertEquals(200, response.statusCode());
        String html = panelAfterListings();
        assertTrue(html.contains("value=\"1082\""), html);
        assertTrue(html.contains("value=\"1083\""), html);
        assertFalse(html.contains("value=\"268\""), html);
    }

    @Test
    void chosenBuildListsItsDistFoldersOnly() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");

        String html = panelAfterListings();

        assertTrue(html.contains("<option value=\"buypass\" selected>buypass</option>"), html);
        assertFalse(html.contains("viper-buypass"), "a flavour folder is not a Distribution: " + html);
        assertTrue(html.contains("1082 holds buypass"), html);
    }

    @Test
    void downloadFetchesOverlayAndPsiJarsToTheBuildsRoot() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1082");
        set("mode", "new_install");

        HttpResponse<String> response = post("/downloads/fetch", "", true);
        downloads.awaitIdle(WAIT);

        assertEquals(200, response.statusCode());
        Path dir = root.resolve("Solutions1082").resolve("buypass");
        assertEquals(new String(OVERLAY, StandardCharsets.UTF_8),
                Files.readString(dir.resolve("overlay.zip"), StandardCharsets.UTF_8));
        assertEquals(new String(INSTALLER, StandardCharsets.UTF_8),
                Files.readString(dir.resolve("Petro_Suite_Installer.jar"), StandardCharsets.UTF_8));
        assertEquals(new String(AUTO, StandardCharsets.UTF_8),
                Files.readString(dir.resolve("Auto_Upgrade.jar"), StandardCharsets.UTF_8));
        assertTrue(fake.downloads().stream().anyMatch(p -> p.endsWith("/1082/buypass/overlay.zip")), fake.downloads().toString());
        assertEquals("", downloads.busy());
        assertEquals("done", downloads.job().state());
    }

    @Test
    void downloadWithAFlavourOnlyBuildIsNotBuiltYet() throws Exception {
        set("commanderBranch", "Solutions");
        set("commanderBuild", "1083");

        post("/downloads/fetch", "", true);
        downloads.awaitIdle(WAIT);

        assertEquals("failed", downloads.job().state());
        assertTrue(downloads.job().message().contains("overlay_merge"), downloads.job().message());
        assertTrue(downloads.job().message().contains("not built yet"), downloads.job().message());
        assertFalse(Files.exists(root.resolve("Solutions1083")), "nothing is written for a refused build");
    }

    @Test
    void postWithoutHtmxHeaderIsRefusedAndChangesNothing() throws Exception {
        HttpResponse<String> set = post("/downloads/set?field=commanderBranch", "Solutions", false);
        HttpResponse<String> fetch = post("/downloads/fetch", "", false);
        HttpResponse<String> refresh = post("/downloads/refresh", "", false);
        downloads.awaitIdle(WAIT);

        assertEquals(403, set.statusCode());
        assertEquals(403, fetch.statusCode());
        assertEquals(403, refresh.statusCode());
        assertEquals("", downloads.choice().commanderBranch());
        assertTrue(fake.requests.isEmpty(), fake.requests.toString());
    }

    @Test
    void unknownChoiceFieldIsRefusedWithoutEcho() throws Exception {
        HttpResponse<String> response = post("/downloads/set?field=evilField", "zzqq", true);

        assertEquals(400, response.statusCode());
        assertFalse(response.body().contains("evilField"), response.body());
        assertFalse(response.body().contains("zzqq"), response.body());
    }

    @Test
    void invalidValuesAreRefusedAndNotStored() throws Exception {
        HttpResponse<String> response = set("dist", "buy<b>pass");

        assertTrue(response.body().contains("a Distribution is one word"), response.body());
        assertFalse(response.body().contains("<b>"), response.body());
        assertEquals("buypass", downloads.choice().dist());
    }

    @Test
    void aBranchNameIsEscapedWhenShown() throws Exception {
        set("commanderBranch", "<script>x");

        String html = panelAfterListings();

        assertFalse(html.contains("<script>x"), html);
        assertTrue(html.contains("&lt;script&gt;x"), html);
    }

    @Test
    void fetchWithoutABuildSaysWhatToPick() throws Exception {
        HttpResponse<String> response = post("/downloads/fetch", "", true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Pick a Commander Build on Artifactory"), response.body());
        assertEquals("", downloads.busy());
    }

    @Test
    void dontDownloadListsTheBuildsAlreadyOnThisPc() throws Exception {
        Path local = root.resolve("Solutions1082").resolve("buypass");
        Files.createDirectories(local);
        Files.write(local.resolve("overlay.zip"), OVERLAY);

        String html = panelAfterListings();

        assertTrue(html.contains("Newest on this PC"), html);
        assertTrue(html.contains("value=\"Solutions1082\""), html);
        assertTrue(html.contains("<option value=\"buypass\" selected>buypass</option>"), html);
    }

    @Test
    void refreshAsksForTheBranchesAgain() throws Exception {
        panelAfterListings();
        int before = fake.requests.size();

        HttpResponse<String> response = post("/downloads/refresh", "", true);
        downloads.awaitIdle(WAIT);

        assertEquals(200, response.statusCode());
        assertTrue(fake.requests.size() > before, "refresh asked Artifactory again");
    }

    @Test
    void theChoiceIsHeldOnTheServer() throws Exception {
        set("commanderBranch", "master");
        set("commanderBuild", "268");

        assertEquals(new DownloadsService.Choice("master", "268", "", "", "", downloads.choice().mode(),
                "buypass"), downloads.choice());
    }

    @Test
    void theFragmentPollsOnlyWhileAnActionRuns() throws Exception {
        String html = panelAfterListings();

        assertFalse(html.contains("hx-trigger=\"every 2s\""), html);
    }
}
