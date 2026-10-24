package com.verifone.cat.report.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.report.ReportPaths;

/**
 * The HTML report against the Python CAT's {@code tests/unit/test_petrotest_report.py}:
 * the same assertions, on the same events. Fake-backed evidence only.
 */
class HtmlReportTest {

    @TempDir
    Path dir;

    /** Writes {@code lines} as an events file in a fresh folder, builds the report and returns its HTML. */
    private String render(Path folder, String... lines) throws Exception {
        Path events = folder.resolve(ReportPaths.EVENTS_FILE);
        Files.createDirectories(folder);
        Files.writeString(events, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        HtmlReport.fromEvents(events).write(folder);
        return Files.readString(folder.resolve(HtmlReport.SUMMARY_FILE), StandardCharsets.UTF_8);
    }

    /** The CAT-produced run in {@code cat-run-events.ndjson}, as lines. */
    private String catRun(Path folder) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/report/html/cat-run-events.ndjson")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return render(folder, text.split("\n"));
        }
    }

    /** The shared sample run in {@code report/sample-events.ndjson}, as lines. */
    private String sampleRun(Path folder) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/report/sample-events.ndjson")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return render(folder, text.split("\n"));
        }
    }

    private static void assertContains(String html, String expected) {
        assertTrue(html.contains(expected), () -> "expected the report to contain: " + expected);
    }

    // ---- the sample run -----------------------------------------------------------------

    @Test
    void buildsAReportFromTheSampleFixture() throws Exception {
        Path folder = dir.resolve("sample");
        String html = sampleRun(folder);

        assertTrue(Files.isRegularFile(folder.resolve(HtmlReport.SUMMARY_FILE)));
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertContains(html, "CAT Run Report");
        assertContains(html, "run-20260804-001");
        assertContains(html, "test-rig");
        assertContains(html, "34.5s");
        // Passed 1, Failed 2, Warned 0.
        assertContains(html, "<div class=\"summary-number\">1</div>");
        assertContains(html, "<div class=\"summary-number\">2</div>");
        assertContains(html, "<div class=\"summary-number\">0</div>");
        // The sample is a real run, not a fake-POS one, so no banner.
        assertFalse(html.contains("<div class=\"fake-pos-banner\">"));
        // Its plan cases with no migrated file, and the preflight and recovery sections.
        assertContains(html, "Not Run (2)");
        assertContains(html, "PETROCORE-61391");
        assertContains(html, "<h2>Preflight Checks</h2>");
        assertContains(html, "<h2>Recovery Actions</h2>");
        assertContains(html, "Rung 0: PASS");
    }

    @Test
    void theSampleRunHasNoExternalResources() throws Exception {
        String html = sampleRun(dir.resolve("sample"));

        assertFalse(html.contains("://cdn."));
        assertFalse(html.contains("://fonts.googleapis.com"));
        assertFalse(html.contains("://unpkg.com"));
        assertFalse(html.contains("://cdnjs.cloudflare.com"));
        assertFalse(html.contains("<link"));
        assertFalse(html.contains(" src="));
    }

    // ---- the Python's assertions --------------------------------------------------------

    @Test
    void writesHeaderAndCounts() throws Exception {
        String html = catRun(dir.resolve("counts"));

        assertContains(html, "CAT Run Report");
        assertContains(html, "deadbeef");
        assertContains(html, "<div class=\"metadata-value\">lab</div>");
        assertContains(html, "12.7s");
        assertContains(html, "<div class=\"summary-number\">1</div>");
        assertContains(html, "<div class=\"summary-number\">2</div>");
    }

    @Test
    void failuresComeBeforePassedTests() throws Exception {
        String html = render(dir.resolve("order"), sampleLines());

        int failed = html.indexOf("<h2>Failed Tests</h2>");
        int preflight = html.indexOf("<h2>Preflight Checks</h2>");
        int passed = html.indexOf("<h2>Passed & Skipped Tests</h2>");
        assertTrue(failed != -1 && preflight != -1 && passed != -1);
        assertTrue(failed < passed, "failures should come before passed tests");
        assertTrue(html.indexOf("<h2>Not Run") > html.indexOf("<h2>Recovery Actions</h2>"));
    }

    @Test
    void everyOutcomeHasAGlyphAndAWordThatReadsWithoutColour() throws Exception {
        String html = sampleRun(dir.resolve("glyphs"));

        assertContains(html, "outcome-glyph");
        assertContains(html, "Passed");
        assertContains(html, "Failed");
        assertContains(html, "Skipped");
        assertContains(html, "ERROR");
        assertContains(html, "TIMEOUT");
    }

    @Test
    void preflightRendersAllSixHarnessOutcomesDistinctly() throws Exception {
        String html = render(dir.resolve("six"), sampleLines(
                preflight("fail_check", "verify", "fail", "Check failed"),
                preflight("timeout_check", "repair", "timeout", "Check timed out"),
                preflight("unrepairable_check", "repair", "unrepairable", "Cannot repair this condition")));

        for (String outcome : List.of("pass", "fail", "error", "timeout", "repaired", "unrepairable")) {
            assertContains(html, "class=\"preflight-check " + outcome + "\"");
        }
        assertContains(html, "UNREPAIRABLE");
        assertContains(html, "REPAIRED");
    }

    @Test
    void theCatRunListsEveryFailedNodeidWithItsReason() throws Exception {
        String html = catRun(dir.resolve("reasons"));

        assertContains(html, "<h2>Failed Tests</h2>");
        assertContains(html, "tests/x/test_a.py::test_receipt");
        assertContains(html, "tests/x/test_b.py::test_stub");
        assertContains(html, "AssertionError");
        assertContains(html, "receipt line 3 differs");
        assertContains(html, "SKIPPED: no ported body");
    }

    @Test
    void theCatRunNamesThePlanCasesThatDidNotRun() throws Exception {
        String html = catRun(dir.resolve("notrun"));

        assertContains(html, "Not Run (2)");
        assertContains(html, "PETROCORE-61391");
        assertContains(html, "PETROCORE-37043");
        assertFalse(html.contains("PETROCORE-61334</div>"), "a migrated case is not Not Run");
    }

    @Test
    void theCatRunSaysLoudlyThatItUsedTheFakePos() throws Exception {
        String html = catRun(dir.resolve("fakepos"));

        assertContains(html, "FAKE POS");
        assertContains(html, "not about a rig");
        assertContains(html, "to rig \"lab\"");
    }

    @Test
    void harnessSpellingsLandInTheSameBucketsAsTheWire() throws Exception {
        String html = render(dir.resolve("harness"),
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::one\",\"outcome\":\"fail\",\"duration\":0.5}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::two\",\"outcome\":\"pass\",\"duration\":0.1}");

        assertContains(html, "t/a.py::one");
        assertContains(html, "<h2>Failed Tests</h2>");
        assertContains(html, "t/a.py::two");
        assertContains(html, "<h2>Passed & Skipped Tests</h2>");
    }

    @Test
    void aTestOutcomeNeitherVocabularyKnowsIsDropped() throws Exception {
        String html = render(dir.resolve("unknown-test"),
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::odd\",\"outcome\":\"xfail\",\"duration\":0.1}");

        assertFalse(html.contains("t/a.py::odd"));
        assertFalse(html.contains("<h2>Failed Tests</h2>"));
    }

    @Test
    void aFailureWithoutAReasonShowsNoPlaceholder() throws Exception {
        String html = render(dir.resolve("noreason"),
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::bare\",\"outcome\":\"failed\",\"duration\":0}");

        assertContains(html, "t/a.py::bare");
        assertFalse(html.contains("<div class=\"test-reason\">"));
    }

    @Test
    void theWriterCreatesTheFolderItIsGiven() throws Exception {
        // The events live beside the test, not in the folder the report is written to, so
        // the folder can only exist if write() created it.
        Path events = dir.resolve("elsewhere.ndjson");
        Files.writeString(events, String.join("\n", sampleLines()) + "\n", StandardCharsets.UTF_8);
        Path nested = dir.resolve("a").resolve("b").resolve("run");

        HtmlReport.fromEvents(events).write(nested);

        String html = Files.readString(nested.resolve(HtmlReport.SUMMARY_FILE), StandardCharsets.UTF_8);
        assertContains(html, "run-20260804-001");
    }

    // ---- escaping -----------------------------------------------------------------------

    @Test
    void everyDynamicValueIsEscaped() throws Exception {
        String html = render(dir.resolve("escape"),
                "{\"type\":\"run.started\",\"run_id\":\"<i>run</i>\",\"rig\":\"<script>alert(1)</script>\","
                        + "\"fake_pos\":true,\"test_plans\":[\"P\"]}",
                "{\"type\":\"plan.resolved\",\"cases\":[{\"key\":\"K-1\",\"summary\":\"<b>s</b>\",\"node\":null}]}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::<b>x</b>\",\"outcome\":\"failed\","
                        + "\"duration\":0.2,\"exc_type\":\"<em>E</em>\",\"detail\":\"<img src=x onerror=1>\"}",
                "{\"type\":\"preflight.check\",\"name\":\"<u>n</u>\",\"phase\":\"verify\",\"outcome\":\"pass\","
                        + "\"detail\":\"<s>d</s>\"}",
                "{\"type\":\"recovery.rung\",\"nodeid\":\"t/a.py::<q>\",\"rung\":0,\"outcome\":\"pass\"}");

        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("<b>x</b>"));
        assertFalse(html.contains("<i>run</i>"));
        assertFalse(html.contains("<u>n</u>"));
        assertFalse(html.contains("<q>"));
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt;");
        assertContains(html, "&lt;i&gt;run&lt;/i&gt;");
        assertContains(html, "&lt;img src=x onerror=1&gt;");
        assertContains(html, "t/a.py::&lt;b&gt;x&lt;/b&gt;");
    }

    @Test
    void escapeCoversTheFiveCharactersAndNullIsEmpty() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlReport.esc("&<>\"'"));
        assertEquals("", HtmlReport.esc(null));
        assertEquals("plain", HtmlReport.esc("plain"));
    }

    // ---- the outcome table --------------------------------------------------------------

    @Test
    void everyHarnessOutcomeHasAGlyphColourAndLabel() {
        for (Outcome outcome : Outcome.values()) {
            String glyph = OutcomeInfo.glyph(outcome.value());
            assertFalse(glyph.isEmpty());
            assertTrue(OutcomeInfo.color(outcome.value()).matches("#[0-9a-f]{6}"));
            assertEquals(outcome.value().toUpperCase(java.util.Locale.ROOT), OutcomeInfo.label(outcome.value()));
        }
    }

    @Test
    void harnessGlyphsAreDistinct() {
        List<String> glyphs = java.util.Arrays.stream(Outcome.values())
                .map(o -> OutcomeInfo.glyph(o.value()))
                .toList();
        assertEquals(glyphs.size(), glyphs.stream().distinct().count(), "glyphs must be unique");
    }

    @Test
    void theGlyphsAreTheHarnessOnes() {
        assertEquals("✔", OutcomeInfo.glyph("pass"));
        assertEquals("✖", OutcomeInfo.glyph("fail"));
        assertEquals("⊘", OutcomeInfo.glyph("error"));
        assertEquals("⟲", OutcomeInfo.glyph("timeout"));
        assertEquals("⟳", OutcomeInfo.glyph("repaired"));
        assertEquals("⊗", OutcomeInfo.glyph("unrepairable"));
        assertEquals("·", OutcomeInfo.glyph("skipped"));
    }

    @Test
    void anUnknownOutcomeFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> OutcomeInfo.glyph("unknown_outcome"));
        assertThrows(IllegalArgumentException.class, () -> OutcomeInfo.color("unknown_outcome"));
        assertThrows(IllegalArgumentException.class, () -> OutcomeInfo.label(null));
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String[] sampleLines(String... extra) {
        List<String> lines = new java.util.ArrayList<>(List.of(
                "{\"type\":\"run.started\",\"run_id\":\"run-20260804-001\",\"rig\":\"test-rig\",\"v\":1,\"seq\":0}",
                "{\"type\":\"plan.resolved\",\"cases\":[{\"key\":\"PETROCORE-61391\",\"node\":null,\"summary\":\"Charity: receipt check\"},{\"key\":\"PETROCORE-37043\",\"node\":null}]}",
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/suites/test/test_pass.py::test_pass\",\"outcome\":\"passed\",\"duration\":1.23}",
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/suites/test/test_fail.py::test_fail\",\"outcome\":\"failed\",\"duration\":2.45,\"exc_type\":\"AssertionError\",\"detail\":\"receipt line 3 differs\"}",
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/suites/test/test_error.py::test_error\",\"outcome\":\"error\",\"duration\":0.5,\"exc_type\":\"Failed\",\"detail\":\"setup failed\"}",
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/suites/test/test_timeout.py::test_timeout\",\"outcome\":\"timeout\",\"duration\":30.0}",
                "{\"type\":\"test.finished\",\"nodeid\":\"tests/suites/test/test_skip.py::test_skip\",\"outcome\":\"skipped\",\"duration\":0.1}",
                "{\"type\":\"preflight.check\",\"name\":\"system_checks\",\"phase\":\"verify\",\"outcome\":\"pass\",\"detail\":\"All checks passed\"}",
                "{\"type\":\"preflight.check\",\"name\":\"commander_connection\",\"phase\":\"verify\",\"outcome\":\"error\",\"detail\":\"SSH key not found\"}",
                "{\"type\":\"preflight.check\",\"name\":\"rig_lease\",\"phase\":\"repair\",\"outcome\":\"repaired\",\"detail\":\"Rig was busy, now available\"}",
                "{\"type\":\"recovery.rung\",\"nodeid\":\"tests/suites/test/test_fail.py::test_fail\",\"rung\":0,\"outcome\":\"pass\"}",
                "{\"type\":\"run.finished\",\"run_id\":\"run-20260804-001\",\"outcome\":\"failed\",\"passed\":1,\"failed\":2,\"warned\":0,\"elapsed\":34.5}"));
        // The run.finished line stays last, so extra events go before it.
        lines.addAll(lines.size() - 1, List.of(extra));
        return lines.toArray(String[]::new);
    }

    private static String preflight(String name, String phase, String outcome, String detail) {
        return "{\"type\":\"preflight.check\",\"name\":\"" + name + "\",\"phase\":\"" + phase
                + "\",\"outcome\":\"" + outcome + "\",\"detail\":\"" + detail + "\"}";
    }
}
