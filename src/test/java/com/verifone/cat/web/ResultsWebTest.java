package com.verifone.cat.web;

import static com.verifone.cat.results.RunFixtures.finished;
import static com.verifone.cat.results.RunFixtures.logLine;
import static com.verifone.cat.results.RunFixtures.planResolved;
import static com.verifone.cat.results.RunFixtures.started;
import static com.verifone.cat.results.RunFixtures.stepEntered;
import static com.verifone.cat.results.RunFixtures.stepFinished;
import static com.verifone.cat.results.RunFixtures.test;
import static com.verifone.cat.results.RunFixtures.writeRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * The Results page, its view and its run pages, over HTTP, against a temporary results root
 * with its index beside it. Evidence about this repository only.
 */
class ResultsWebTest {

    @TempDir
    Path root;

    private FakeShim shim;
    private Javalin app;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void setUp() throws Exception {
        writeRun(root, "lab-run", List.of(
                started("lab", false, "abc123def456", List.of("PETROCORE-1"), List.of("<b>Cadence</b>")),
                planResolved("PETROCORE-1", "tests/a.py", "First case", "PETROCORE-2", null, "Not migrated"),
                stepEntered("Tests Run"),
                stepFinished("Tests Run", "complete", 30.0),
                test("tests/a.py::one", "passed", 1.0, ""),
                test("tests/a.py::two", "failed", 2.0, "AssertionError"),
                logLine("<script>alert(1)</script> line one"),
                finished("failed", 1, 1, 0, false, 40.0, List.of())));
        writeRun(root, "fake-run", List.of(
                started("lab", true, "", List.of(), List.of()),
                test("tests/a.py::one", "passed", 1.0, ""),
                finished("passed", 1, 0, 0, false, 5.0, List.of())));
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        app = WebApp.create(client, "CAT-Java: test", root).start("127.0.0.1", 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        shim.close();
    }

    @Test
    void thePageIsAShellThatReadsNothingUntilTheViewLoads() throws Exception {
        HttpResponse<String> response = get("/results");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<h1>Results</h1>"), response.body());
        assertTrue(response.body().contains("hx-get=\"/results/view?segment=rig&amp;tab=runs\""), response.body());
        assertFalse(Files.exists(root.resolve("results-index.json")),
                "opening the page must not build the index");
    }

    @Test
    void theViewListsOnlyTheSegmentItIsAskedFor() throws Exception {
        String rig = get("/results/view?segment=rig&tab=runs").body();
        String fake = get("/results/view?segment=fake_pos&tab=runs").body();

        assertTrue(rig.contains("/results/run/lab-run"), rig);
        assertFalse(rig.contains("/results/run/fake-run"), rig);
        assertTrue(fake.contains("/results/run/fake-run"), fake);
        assertFalse(fake.contains("/results/run/lab-run"), fake);
        assertTrue(Files.exists(root.resolve("results-index.json")), "the view builds the index beside the root");
    }

    @Test
    void everyRecordedValueIsEscaped() throws Exception {
        String html = get("/results/view?segment=rig&tab=runs").body();

        assertTrue(html.contains("&lt;b&gt;Cadence&lt;/b&gt;"), html);
        assertFalse(html.contains("<b>Cadence</b>"), html);
    }

    @Test
    void theFilterNarrowsTheRows() throws Exception {
        String none = get("/results/view?segment=rig&tab=runs&q=nothing-matches").body();
        String byRig = get("/results/view?segment=rig&tab=runs&q=lab").body();

        assertTrue(none.contains("No run matches the filter."), none);
        assertTrue(byRig.contains("/results/run/lab-run"), byRig);
    }

    @Test
    void theStatisticsTabDrawsTheTrend() throws Exception {
        String html = get("/results/view?segment=rig&tab=stats").body();

        assertTrue(html.contains("<svg"), html);
        assertTrue(html.contains("Pass rate"), html);
        assertTrue(html.contains("AssertionError"), html);
    }

    @Test
    void aFakePosSegmentSaysItIsNotARig() throws Exception {
        String html = get("/results/view?segment=fake_pos&tab=stats").body();

        assertTrue(html.contains("FAKE POS: evidence about this repository, not about a rig."), html);
    }

    @Test
    void aRunPageShowsItsStepsAndTests() throws Exception {
        HttpResponse<String> response = get("/results/run/lab-run");

        assertEquals(200, response.statusCode());
        String html = response.body();
        assertTrue(html.contains("Tests Run"), html);
        assertTrue(html.contains("tests/a.py::two"), html);
        assertTrue(html.contains("AssertionError"), html);
        assertTrue(html.contains("PETROCORE-2"), "a case with no test file is listed as not run");
    }

    @Test
    void aRunThatIsNotThereIsNotFound() throws Exception {
        assertEquals(404, get("/results/run/no-such-run").statusCode());
    }

    @Test
    void theLogPageShowsTheLinesEscaped() throws Exception {
        String html = get("/results/run/lab-run/log").body();

        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; line one"), html);
        assertFalse(html.contains("<script>alert"), html);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
