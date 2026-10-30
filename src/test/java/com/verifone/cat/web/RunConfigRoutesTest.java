package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import com.verifone.cat.runconfig.ConfigFixture;
import com.verifone.cat.runconfig.ConfigForm;

import io.javalin.Javalin;

/**
 * The Config tab over HTTP, in front of a fake plan seed in a temp folder. Evidence about this
 * repository only; nothing here reaches the rig, Confluence or the home directory.
 */
class RunConfigRoutesTest {

    @TempDir
    Path tmp;

    private FakeShim shim;
    private ConfigForm form;
    private Javalin app;
    private final HttpClient http = HttpClient.newBuilder().build();

    @BeforeEach
    void setUp() throws IOException {
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        form = ConfigFixture.form(tmp);
        app = WebApp.create(client, "CAT-Java: test / topaz101", form).start(WebApp.BIND_HOST, 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        shim.close();
    }

    @Test
    void theConfigTabRendersThePlanPickerAndTheNotBuiltSections() throws Exception {
        String page = get("/run/config").body();

        assertTrue(page.contains("href=\"/run/config\" aria-current=\"page\""), page);
        assertTrue(page.contains("Filter plans"), page);
        assertTrue(page.contains("Charity Donations"), page);
        assertTrue(page.contains("Automated"), page);
        // Wave 16: the branch, Downloads, Commander Connect, Execution and Refresh load from ConfigSections.
        assertTrue(page.contains("hx-get=\"/fragments/config/refresh\""), page);
        assertTrue(page.contains("hx-get=\"/fragments/config/controls\""), page);
    }

    @Test
    void aPlanNameFromTheCatalogueIsEscaped() throws Exception {
        String page = get("/run/config").body();

        assertTrue(page.contains("&lt;b&gt;Old&lt;/b&gt; plan"), page);
        assertFalse(page.contains("<b>Old</b>"), page);
    }

    @Test
    void aMissingPlanSaysWhatStopsARun() throws Exception {
        String page = get("/run/config").body();
        assertTrue(page.contains("Nothing to run yet: pick a test plan."), page);
    }

    @Test
    void aChangeWithoutTheHtmxHeaderIsRefused() throws Exception {
        HttpResponse<String> response = post("/config/plan", "key=" + ConfigFixture.PLAN, false);

        assertEquals(403, response.statusCode());
        assertTrue(form.plans().isEmpty());
    }

    @Test
    void tickingAPlanAnswersWithThePanelAndSavesIt() throws Exception {
        HttpResponse<String> response = post("/config/plan", "key=" + ConfigFixture.PLAN, true);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("checked hx-post=\"/config/plan\" hx-vals=\"{&quot;key&quot;:&quot;"
                + ConfigFixture.PLAN + "&quot;}\""), response.body());
        assertEquals(List.of(ConfigFixture.PLAN), form.plans());
        String saved = Files.readString(tmp.resolve("cfg").resolve("last-config.json"), StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"config-plans\""), saved);
        assertTrue(saved.contains(ConfigFixture.PLAN), saved);
    }

    @Test
    void theCasesScopeListsTheCasesAndItsPrerequisiteIsAddedOnTick() throws Exception {
        post("/config/plan", "key=" + ConfigFixture.PLAN, true);

        HttpResponse<String> scoped = post("/config/scope", "mode=cases&prerequisites=on", true);
        assertTrue(scoped.body().contains("value=\"cases\" checked"), scoped.body());
        assertTrue(scoped.body().contains("hx-vals=\"{&quot;key&quot;:&quot;" + ConfigFixture.PLAIN + "&quot;}\""),
                scoped.body());

        HttpResponse<String> ticked = post("/config/case", "key=" + ConfigFixture.PLAIN, true);
        assertTrue(ticked.body().contains("+ " + ConfigFixture.IMPORTER), ticked.body());
        assertTrue(ticked.body().contains("is added"), ticked.body());
    }

    @Test
    void theFilterSwapsOnlyTheMatchingPlans() throws Exception {
        HttpResponse<String> response = get("/fragments/config/plans?filter=donations");

        assertTrue(response.body().contains("Charity Donations"), response.body());
        assertFalse(response.body().contains("Deprecated"), response.body());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, boolean htmx)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(body)));
        if (htmx) {
            request.header("HX-Request", "true");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** {@code key=PETROCORE-1&mode=cases}: each value URL-encoded, as a browser sends it. */
    private static String encodeForm(String pairs) {
        StringBuilder out = new StringBuilder();
        for (String pair : pairs.split("&")) {
            int eq = pair.indexOf('=');
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(URLEncoder.encode(pair.substring(0, eq), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private URI uri(String path) {
        return URI.create("http://" + WebApp.BIND_HOST + ":" + app.port() + path);
    }
}
