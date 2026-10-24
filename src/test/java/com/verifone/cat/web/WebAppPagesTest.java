package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;

import io.javalin.Javalin;

/**
 * The site shell: every top-level page, the Run tabs, the placeholders and the redirects,
 * served over HTTP. Evidence about this repository only; nothing here touches a rig.
 */
class WebAppPagesTest {

    private static final String DASH = "—";

    private FakeShim shim;
    private Javalin app;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() {
        // The shell pages never call the shim; FakeShim only stands in for the POS routes.
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        app = WebApp.start(client, "CAT-Java: test / topaz101", 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        shim.close();
    }

    @Test
    void theWebUiBindsLoopbackOnly() {
        assertEquals("127.0.0.1", WebApp.BIND_HOST);
    }

    @Test
    void rootRedirectsToRun() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(302, response.statusCode());
        assertEquals("/run", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void manualRedirectsToTools() throws Exception {
        HttpResponse<String> response = get("/manual");

        assertEquals(302, response.statusCode());
        assertEquals("/tools", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void everyTopLevelPageRendersInTheShell() throws Exception {
        List<String[]> pages = List.of(
                new String[] {"/run", "Run"},
                new String[] {"/results", "Results"},
                new String[] {"/authoring", "Authoring"},
                new String[] {"/upgrade", "Install Upgrade"},
                new String[] {"/simulators", "Launch Simulators"},
                new String[] {"/setup", "Setup"},
                new String[] {"/settings", "Settings"},
                new String[] {"/tools", "Tools"},
                new String[] {"/pos", "POS"});
        for (String[] page : pages) {
            HttpResponse<String> response = get(page[0]);
            String html = response.body();

            assertEquals(200, response.statusCode(), page[0]);
            assertTrue(html.contains("<aside class=\"sidebar\">"), page[0]);
            assertTrue(html.contains("<h1>" + page[1] + "</h1>"), page[0] + ": " + html);
            assertTrue(html.contains("/static/cat.css"), page[0]);
        }
    }

    @Test
    void sidebarListsTheSectionsInTheDocumentedOrder() throws Exception {
        String html = get("/setup").body();

        int last = -1;
        for (String label : List.of("Run", "Results", "Authoring", "Install Upgrade", "Launch Simulators",
                "Setup", "Settings", "Tools", "POS")) {
            int at = html.indexOf("<span class=\"label\">" + label + "</span>");
            assertTrue(at > last, label + " is missing or out of order");
            last = at;
        }
    }

    @Test
    void sidebarMarksOnlyTheCurrentSection() throws Exception {
        String html = get("/setup").body();

        assertEquals(1, count(html, "class=\"active\" aria-current=\"page\""), html);
        assertTrue(html.contains("href=\"/setup\" class=\"active\""), html);
    }

    @Test
    void unbuiltPagesNameThePythonModuleTheyWillBePortedFrom() throws Exception {
        assertPlaceholder("/results", "cat/web/pages/results.py");
        assertPlaceholder("/authoring", "cat/web/pages/authoring.py");
        assertPlaceholder("/upgrade", "cat/web/pages/upgrade.py");
        assertPlaceholder("/simulators", "cat/web/pages/simulators.py");
        assertPlaceholder("/setup", "cat/web/pages/setup.py");
        assertPlaceholder("/settings", "cat/web/pages/settings.py");
    }

    @Test
    void runTabsRenderTheirOwnPlaceholders() throws Exception {
        String overview = get("/run").body();
        String config = get("/run/config").body();
        String log = get("/run/log").body();

        assertTrue(overview.contains("href=\"/run/config\""), overview);
        assertTrue(overview.contains("href=\"/run/log\""), overview);
        assertTrue(overview.contains("Ported from cat/web/pages/run.py " + DASH + " not built yet"), overview);
        assertTrue(config.contains("Ported from cat/web/pages/config.py " + DASH + " not built yet"), config);
        assertTrue(log.contains("Ported from cat/web/pages/run.py " + DASH + " not built yet"), log);
        assertTrue(overview.contains("href=\"/run\" aria-current=\"page\""), overview);
        assertTrue(config.contains("href=\"/run/config\" aria-current=\"page\""), config);
        assertTrue(log.contains("href=\"/run/log\" aria-current=\"page\""), log);
    }

    @Test
    void toolsIsEmptyByDesignAndIsNotAPlaceholder() throws Exception {
        String html = get("/tools").body();

        assertTrue(html.contains("Nothing here yet"), html);
        assertFalse(html.contains("Not built yet"), html);
    }

    @Test
    void posPageKeepsItsCardsInsideTheShell() throws Exception {
        String html = get("/pos").body();

        assertTrue(html.contains("hx-get=\"/fragments/health\""), html);
        assertTrue(html.contains("hx-get=\"/fragments/screen\""), html);
        assertTrue(html.contains("hx-get=\"/fragments/keys\""), html);
        assertTrue(html.contains("CAT-Java: test / topaz101"), html);
        assertTrue(html.contains("/static/htmx.min.js"), html);
    }

    @Test
    void staticAssetsAreServedLocally() throws Exception {
        HttpResponse<String> js = get("/static/cat.js");
        HttpResponse<String> css = get("/static/cat.css");

        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("cat.rail"));
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("prefers-color-scheme: dark"));
        // The POS page prints the shim's URI as text; only attributes would load anything.
        String pos = get("/pos").body();
        assertFalse(pos.contains("src=\"http") || pos.contains("href=\"http"), "the shell must not link a CDN");
    }

    @Test
    void headingsAndSubtitlesAreEscaped() {
        String layout = Layout.page(Section.RUN, "<script>alert(1)</script>", "");
        String placeholder = Placeholder.html("<b>x</b>", "a<b>c</b>");
        String pos = PosPage.body("<img src=x onerror=1>", "http://\"><svg>");

        assertFalse(layout.contains("<script>alert"), layout);
        assertTrue(layout.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), layout);
        assertFalse(placeholder.contains("<b>x</b>"), placeholder);
        assertFalse(placeholder.contains("<b>c</b>"), placeholder);
        assertFalse(pos.contains("<img"), pos);
        assertFalse(pos.contains("<svg"), pos);
    }

    @Test
    void theShellsNavigationCoversEverySection() {
        assertEquals(9, Section.values().length);
        for (Section s : Section.values()) {
            assertTrue(s.path().startsWith("/"), s.path());
        }
    }

    private void assertPlaceholder(String path, String module) throws Exception {
        String html = get(path).body();

        assertTrue(html.contains("Not built yet"), path + ": " + html);
        assertTrue(html.contains("Ported from " + module + " " + DASH + " not built yet"), path + ": " + html);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + WebApp.BIND_HOST + ":" + app.port() + path);
    }
}
