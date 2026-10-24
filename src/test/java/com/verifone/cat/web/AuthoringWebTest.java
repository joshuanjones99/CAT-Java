package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.authoring.StepLibrary;
import com.verifone.cat.posshim.PosShimClient;

import io.javalin.Javalin;

/**
 * The Authoring page over HTTP, against a temp session folder and a loopback Jira that answers one issue. Nothing
 * touches the real Jira, {@code ~/.cat}, or a rig: no action here captures, replays or drafts.
 *
 * <p>Evidence about this repository only.
 */
class AuthoringWebTest {

    private static final String ISSUE = """
            {"key":"PETROCORE-5","names":{},"fields":{"summary":"Round up for charity on cash",
              "issuetype":{"name":"Story"},"status":{"name":"In Progress"},"labels":["charity"]}}
            """;

    @TempDir
    Path tmp;

    private Path root;
    private HttpServer jira;
    private Javalin app;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        root = tmp.resolve("sessions");
        jira = startJira();
        String jiraBase = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + jira.getAddress().getPort();
        AuthoringService service = new AuthoringService(root, () -> new AtlassianClient(
                URI.create(jiraBase), URI.create(jiraBase + "/api/v2"),
                () -> new AtlassianCredentials.Jira("tester@verifone.com", "not-a-real-token"), () -> null,
                Duration.ofSeconds(5)));
        PosShimClient client = new PosShimClient("127.0.0.1", 1, null, Duration.ofSeconds(1));
        app = WebApp.createWithAuthoring(client, "CAT-Java: test", service).start(WebApp.BIND_HOST, 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        try {
            app.stop();
        } finally {
            jira.stop(0);
        }
    }

    @Test
    void openingThePageCreatesNothingAndNamesWhatIsNotBuilt() throws Exception {
        String html = get("/authoring");

        assertTrue(html.contains("no session yet"), html);
        assertFalse(Files.exists(root), "opening the page must not create a session folder");
        assertTrue(html.contains("Not built yet: cat/authoring/draft.py"), html);
        assertTrue(html.contains("not built yet — cat/authoring/replay.py"), html);
        assertFalse(html.contains("cat/web/pages/authoring.py"), "the placeholder is gone");
    }

    @Test
    void postsWithoutHtmxAreRefusedAndChangeNothing() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/authoring/case/new"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString("name=sneaky")).build(), BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
        assertFalse(Files.exists(root));
    }

    @Test
    void aNewCaseIsWrittenListedAndOpenedInTheEditor() throws Exception {
        String html = post("/authoring/case/new", "name", "cash sale");

        assertTrue(html.contains("added cash sale as case 1"), html);
        assertTrue(html.contains("cash sale"), html);
        Path draft = onlyDraft();
        String text = Files.readString(draft, StandardCharsets.UTF_8);
        assertTrue(text.contains("Scenario: cash sale"), text);
        assertTrue(html.contains("Given the register is idle"), html);
        assertTrue(html.contains("id=\"authoring-case\" name=\"case\" value=\"" + caseId(html) + "\""), html);
    }

    @Test
    void theEditorSavesCrlfTextAndAnswersWithItsDiagnosticsOutOfBand() throws Exception {
        String id = caseId(post("/authoring/case/new", "name", "cash sale"));
        String typed = "@PETROCORE-1\r\nFeature: x\r\n\r\n  Scenario: <b>bad</b>\r\n"
                + "    Given the register is idle\r\n    Given flibbertigibbet happens <script>\r\n";

        HttpResponse<String> response = postRaw("/authoring/feature", "case", id, "text", typed);

        assertEquals(200, response.statusCode());
        String html = response.body();
        assertTrue(html.contains("hx-swap-oob=\"true\""), html);
        assertTrue(html.contains("<div class=\"bad\">line "), html);
        assertFalse(html.contains("<script>"), "the typed text is escaped, never echoed raw");
        String saved = Files.readString(onlyDraft(), StandardCharsets.UTF_8);
        assertFalse(saved.contains("\r"), "the file keeps LF line endings");
        assertTrue(saved.contains("flibbertigibbet happens <script>"), saved);
    }

    @Test
    void aStaleCaseIdCannotOverwriteAnotherCase() throws Exception {
        String id = caseId(post("/authoring/case/new", "name", "cash sale"));
        Path draft = onlyDraft();
        String before = Files.readString(draft, StandardCharsets.UTF_8);

        postRaw("/authoring/feature", "case", "case-deadbeef", "text", "Feature: overwritten\n");

        assertEquals(before, Files.readString(draft, StandardCharsets.UTF_8));
        assertFalse(id.isEmpty());
    }

    @Test
    void aPhraseInsertsItsExampleUnderTheLastLine() throws Exception {
        String id = caseId(post("/authoring/case/new", "name", "cash sale"));
        String example = StepLibrary.steps().get(0).example();
        String before = Files.readString(onlyDraft(), StandardCharsets.UTF_8);

        post("/authoring/insert?example=" + URLEncoder.encode(example, StandardCharsets.UTF_8),
                "case", id, "text", before);

        String after = Files.readString(onlyDraft(), StandardCharsets.UTF_8);
        assertTrue(after.contains("    Given the register is idle\n    " + example + "\n"), after);
    }

    @Test
    void theStepFilterListsMatchingPhrasesAndNothingForANonsenseWord() throws Exception {
        post("/authoring/case/new", "name", "cash sale");
        String example = StepLibrary.steps().get(0).example();
        String word = example.split("\\s+")[1];

        String found = postRaw("/authoring/steps", "filter", word).body();
        String none = postRaw("/authoring/steps", "filter", "zzqqxxyy").body();

        assertTrue(found.contains("class=\"step\""), found);
        assertTrue(none.contains("No phrase matches that filter."), none);
    }

    @Test
    void ingestReadsTheTicketIntoTheSessionAndShowsItsSpec() throws Exception {
        post("/authoring/case/new", "name", "cash sale");

        String html = post("/authoring/ingest", "spec", "PETROCORE-5");

        assertTrue(html.contains("ingested PETROCORE-5: Round up for charity on cash"), html);
        assertTrue(Files.isRegularFile(onlySession().resolve("spec-PETROCORE-5.md")));
        assertTrue(html.contains("Round up for charity on cash"), html);
    }

    @Test
    void aBadTicketKeyIsAMessageNotAnError() throws Exception {
        String html = post("/authoring/ingest", "spec", "nope");

        assertTrue(html.contains("is not a Jira key like PETROCORE-12345"), html);
    }

    @Test
    void theStoryTicketIsCheckedAndSaved() throws Exception {
        String refused = post("/authoring/ticket", "ticket", "PETROCORE-x");
        String saved = post("/authoring/ticket", "ticket", "petrocore-42");

        assertTrue(refused.contains("is not a ticket like PETROCORE-12345"), refused);
        assertTrue(saved.contains("story ticket PETROCORE-42"), saved);
        assertTrue(saved.contains("story PETROCORE-42"), saved);
    }

    @Test
    void approveWithNoCapturedBaselineSaysSo() throws Exception {
        post("/authoring/case/new", "name", "cash sale");

        String html = post("/authoring/approve");

        assertTrue(html.contains("no captured baseline to approve"), html);
    }

    @Test
    void thePublishDryRunSendsNothingAndNamesWhatWouldRefuseIt() throws Exception {
        post("/authoring/case/new", "name", "cash sale");

        String html = post("/authoring/publish");

        assertTrue(html.contains("DRY RUN"), html);
        assertTrue(html.contains("nothing was sent"), html);
        assertTrue(html.contains("no story ticket (D17)"), html);
        assertTrue(html.contains("the replay gate is not built yet (cat/authoring/replay.py)"), html);
        assertTrue(html.contains("aria-label=\"Review\">"), html);
    }

    @Test
    void casesCanBeMovedAndRemoved() throws Exception {
        post("/authoring/case/new", "name", "alpha");
        post("/authoring/case/new", "name", "beta");
        post("/authoring/select?index=0");

        String moved = post("/authoring/case/move?delta=1");
        assertTrue(moved.indexOf(">beta<") < moved.indexOf(">alpha<"), moved);

        String removed = post("/authoring/case/remove");
        assertEquals(1, count(removed, "cat-case-link"), removed);
        assertTrue(removed.contains(">beta<"), removed);
    }

    @Test
    void theTabsShowOnlyTheirOwnPanel() throws Exception {
        post("/authoring/case/new", "name", "cash sale");

        String spec = post("/authoring/tab?name=spec");

        assertTrue(spec.contains("id=\"authoring-panel-spec\" aria-label=\"Spec\">"), spec);
        assertTrue(spec.contains("aria-label=\"Feature\" hidden>"), spec);
    }

    @Test
    void theDraftCaptureAndReplayButtonsAreDisabledAndNameTheirModules() throws Exception {
        String html = get("/authoring");

        assertTrue(html.contains("disabled title=\"Not built yet: cat/authoring/draft.py\""), html);
        assertTrue(html.contains("disabled title=\"Not built yet: cat/authoring/replay.py\""), html);
    }

    @Test
    void aCaseNameIsEscapedWhereItIsShown() throws Exception {
        String html = post("/authoring/case/new", "name", "<img src=x onerror=alert(1)>");

        assertFalse(html.contains("<img src=x"), html);
        assertTrue(html.contains("&lt;img src=x"), html);
    }

    // ------------------------------------------------------------ helpers

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                BodyHandlers.ofString()).body();
    }

    private String post(String path, String... pairs) throws Exception {
        return postRaw(path, pairs).body();
    }

    private HttpResponse<String> postRaw(String path, String... pairs) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (i > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("HX-Request", "true")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body.toString())).build(), BodyHandlers.ofString());
    }

    private static final Pattern CASE_FIELD = Pattern.compile("id=\"authoring-case\" name=\"case\" value=\"([^\"]*)\"");

    private static String caseId(String html) {
        Matcher found = CASE_FIELD.matcher(html);
        if (!found.find()) {
            throw new AssertionError("no case field in: " + html);
        }
        return found.group(1);
    }

    private Path onlySession() throws IOException {
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> found = dirs.filter(Files::isDirectory).toList();
            assertEquals(1, found.size(), "one session folder");
            return found.get(0);
        }
    }

    private Path onlyDraft() throws IOException {
        try (Stream<Path> files = Files.list(onlySession())) {
            List<Path> found = files.filter(p -> p.getFileName().toString().endsWith(".feature")).toList();
            assertEquals(1, found.size(), "one draft");
            return found.get(0);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static HttpServer startJira() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            boolean known = "/rest/api/3/issue/PETROCORE-5".equals(exchange.getRequestURI().getPath());
            byte[] bytes = (known ? ISSUE : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(known ? 200 : 404, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
