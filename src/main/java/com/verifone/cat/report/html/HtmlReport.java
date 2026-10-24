package com.verifone.cat.report.html;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.NdjsonReader;
import com.verifone.cat.report.RunFacts;

/**
 * Writes {@code summary.html}, the run's self-contained HTML report, from its events.
 *
 * <p>The Python CAT's {@code petrotest.report.html.HtmlSummaryWriter}. The layout is the
 * same and failures come first: a header with the run's id, rig and elapsed time; a
 * FAKE POS banner when the run used the fake POS; the passed, failed, warned counters;
 * then Failed Tests (failed, error and timeout), Preflight Checks, Recovery Actions, Not
 * Run (plan cases with no migrated test file) and Passed &amp; Skipped Tests. The CSS is
 * inline, so the file needs no network.
 *
 * <p>Two differences from the Python, both deliberate:
 * <ul>
 *   <li>Every dynamic value is escaped. The Python wrote the run id and the rig name into
 *       the header unescaped, and a rig name or node id is text this report did not
 *       author.</li>
 *   <li>The test outcomes are read through the same table the Python uses, so the
 *       harness spellings ({@code pass}, {@code fail}) land in the same buckets as the
 *       wire's ({@code passed}, {@code failed}). Bucketing on the harness spelling once
 *       hid every test in every real run.</li>
 * </ul>
 *
 * <p>An outcome that {@link OutcomeInfo} does not know throws, as the Python does, rather
 * than being drawn as nothing; a test outcome is only ever bucketed from the known table.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only. No rig run has written a report
 * with this port yet.
 */
public final class HtmlReport {

    /** The file's name in a run folder. */
    public static final String SUMMARY_FILE = "summary.html";

    /** What a dynamic value shows when the events did not carry it. */
    static final String UNKNOWN = "unknown";

    /** Wire and harness test outcomes, mapped to the bucket key the report groups them under. */
    private static final Map<String, String> TEST_OUTCOMES = Map.of(
            "passed", "passed",
            "failed", "failed",
            "error", "error",
            "timeout", "timeout",
            "skipped", "skipped",
            "pass", "passed",
            "fail", "failed");

    /** One test's row in a bucket. */
    record TestRow(String nodeid, String outcome, double duration, String excType, String detail) {}

    private final RunFacts facts = new RunFacts();
    private final Map<String, List<TestRow>> testsByOutcome = new LinkedHashMap<>();
    private double elapsed;
    private long passed;
    private long failed;
    private long warned;

    public HtmlReport() {
        for (String bucket : List.of("failed", "error", "timeout", "passed", "skipped")) {
            testsByOutcome.put(bucket, new ArrayList<>());
        }
    }

    /** Folds one event into this report. Events it does not use are ignored. */
    public void processEvent(JsonNode event) {
        if (facts.fold(event)) {
            return;
        }
        String type = text(event, "type");
        if ("run.finished".equals(type)) {
            elapsed = number(event, "elapsed");
            passed = whole(event, "passed");
            failed = whole(event, "failed");
            warned = whole(event, "warned");
        } else if ("test.finished".equals(type)) {
            foldTest(event);
        }
    }

    private void foldTest(JsonNode event) {
        String outcome = text(event, "outcome");
        String bucket = outcome == null ? null : TEST_OUTCOMES.get(outcome);
        String nodeid = text(event, "nodeid");
        if (nodeid == null || nodeid.isEmpty() || bucket == null) {
            return;
        }
        testsByOutcome.get(bucket).add(new TestRow(
                nodeid,
                bucket,
                number(event, "duration"),
                orEmpty(text(event, "exc_type")),
                orEmpty(text(event, "detail"))));
    }

    /** The folded state the common events left, for a caller that reports on it too. */
    public RunFacts facts() {
        return facts;
    }

    /**
     * Writes {@code <artifactsDir>/summary.html}, creating the folder if needed.
     *
     * @throws IOException when the file cannot be written
     */
    public void write(Path artifactsDir) throws IOException {
        Files.createDirectories(artifactsDir);
        Files.writeString(artifactsDir.resolve(SUMMARY_FILE), render(), StandardCharsets.UTF_8);
    }

    /**
     * Reads {@code events} (a run's {@code events.ndjson}) and returns a report holding its
     * state, ready for {@link #write}.
     *
     * @throws IOException when the file cannot be read as UTF-8
     */
    public static HtmlReport fromEvents(Path events) throws IOException {
        HtmlReport report = new HtmlReport();
        NdjsonReader.forEach(events, report::processEvent);
        return report;
    }

    /** The whole document as a string. Package-private so tests can read it without a file. */
    String render() {
        StringBuilder html = new StringBuilder(32_768);
        html.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>CAT Run Report</title>
                    <style>
                """);
        html.append(HtmlStyle.CSS);
        html.append("""
                    </style>
                </head>
                <body>
                    <div class="container">
                        <header>
                            <h1>🛠 CAT Run Report</h1>
                            <div class="metadata">
                """);
        html.append(metadataItem("Run ID", orUnknown(facts.runId())));
        html.append(metadataItem("Rig", orUnknown(facts.rig())));
        html.append(metadataItem("Elapsed", String.format(Locale.ROOT, "%.1fs", elapsed)));
        html.append("""
                            </div>
                        </header>
                """);
        html.append(banner());
        html.append(summaryCards());
        html.append(sectionFailedTests());
        html.append(sectionPreflight());
        html.append(sectionRecovery());
        html.append(sectionNotRun());
        html.append(sectionPassedTests());
        html.append("""

                    <footer>
                        <p>Generated by CAT (Commander Automation Terminal)</p>
                    </footer>
                    </div>
                </body>
                </html>
                """);
        return html.toString();
    }

    private static String metadataItem(String label, String value) {
        return """
                                <div class="metadata-item">
                                    <div class="metadata-label">%s</div>
                                    <div class="metadata-value">%s</div>
                                </div>
                """.formatted(esc(label), esc(value));
    }

    /**
     * Says, above the counters, when this was not a rig run. CLAUDE.md: "Never quote a
     * {@code --fake-pos} pass as hardware verification." The rig name is still shown, and
     * without the banner the report reads as hardware evidence.
     */
    private String banner() {
        if (!facts.fakePos()) {
            return "";
        }
        return "<div class=\"fake-pos-banner\">FAKE POS — this run never opened a socket "
                + "to rig \"" + esc(orUnknown(facts.rig())) + "\". Every result below is evidence "
                + "about this repository, not about a rig.</div>\n";
    }

    private String summaryCards() {
        return """
                <div class="summary">
                    <div class="summary-card pass">
                        <div class="summary-number">%d</div>
                        <div class="summary-label">Passed</div>
                    </div>
                    <div class="summary-card fail">
                        <div class="summary-number">%d</div>
                        <div class="summary-label">Failed</div>
                    </div>
                    <div class="summary-card warned">
                        <div class="summary-number">%d</div>
                        <div class="summary-label">Warned</div>
                    </div>
                </div>
                """.formatted(passed, failed, warned);
    }

    /** Failed, error and timeout tests, failures first. Empty when there are none. */
    private String sectionFailedTests() {
        StringBuilder items = new StringBuilder();
        for (String bucket : List.of("failed", "error", "timeout")) {
            for (TestRow test : testsByOutcome.get(bucket)) {
                items.append("""
                            <li class="test-item %s">
                                <div class="test-header">
                                    <span class="outcome-glyph">%s</span>
                                    <span>%s</span>
                                </div>
                                <div class="test-nodeid">%s</div>
                                <div class="test-duration">%s</div>
                                %s
                            </li>
                        """.formatted(
                        test.outcome(),
                        OutcomeInfo.glyph(test.outcome()),
                        OutcomeInfo.label(test.outcome()),
                        esc(test.nodeid()),
                        String.format(Locale.ROOT, "%.2fs", test.duration()),
                        reasonHtml(test)));
            }
        }
        if (items.isEmpty()) {
            return "";
        }
        return section("Failed Tests", items);
    }

    /**
     * The failure reason, or nothing when the wire carried none. An empty reason is
     * deliberate: a run recorded before {@code TestFinished.detail} existed has no reason,
     * and a placeholder would read as "failed with no message".
     */
    private static String reasonHtml(TestRow test) {
        if (test.excType().isEmpty() && test.detail().isEmpty()) {
            return "";
        }
        String head = test.excType().isEmpty() ? "" : esc(test.excType());
        return "<div class=\"test-reason\"><strong>" + head + "</strong><pre>"
                + esc(test.detail()) + "</pre></div>";
    }

    private String sectionPreflight() {
        if (facts.preflightChecks().isEmpty()) {
            return "";
        }
        StringBuilder checks = new StringBuilder();
        for (RunFacts.PreflightCheck check : facts.preflightChecks()) {
            String outcome = lower(check.outcome());
            String label = OutcomeInfo.label(outcome);
            String detail = check.detail().isEmpty() ? "" : """
                                <div class="preflight-detail">%s</div>
                    """.formatted(esc(check.detail()));
            checks.append("""
                            <div class="preflight-check %s">
                                <div class="preflight-header">
                                    <span class="outcome-glyph" title="%s">%s</span>
                                    <span class="preflight-name">%s</span>
                                    <span class="preflight-phase">%s</span>
                                </div>
                                %s</div>
                        """.formatted(
                    outcome, label, OutcomeInfo.glyph(outcome),
                    esc(orUnknown(check.name())), esc(orUnknown(check.phase())), detail));
        }
        return """
                <div class="section">
                    <h2>Preflight Checks</h2>
                    <div class="preflight-grid">
                %s    </div>
                </div>
                """.formatted(checks);
    }

    private String sectionRecovery() {
        if (facts.recoveryRungs().isEmpty()) {
            return "";
        }
        StringBuilder rungs = new StringBuilder();
        for (Map.Entry<String, List<RunFacts.Rung>> entry : new TreeMap<>(facts.recoveryRungs()).entrySet()) {
            for (RunFacts.Rung rung : entry.getValue()) {
                String outcome = lower(rung.outcome());
                rungs.append("""
                            <li class="test-item %s">
                                <div class="test-header">
                                    <span class="outcome-glyph">%s</span>
                                    <span>Rung %s: %s</span>
                                </div>
                                <div class="test-nodeid">%s</div>
                            </li>
                        """.formatted(
                        outcome,
                        OutcomeInfo.glyph(outcome),
                        esc(orUnknown(rung.rung())),
                        OutcomeInfo.label(outcome),
                        esc(entry.getKey())));
            }
        }
        if (rungs.isEmpty()) {
            return "";
        }
        return section("Recovery Actions", rungs);
    }

    /**
     * The plan's cases with no migrated test file. Without this the report shrinks the plan
     * to the subset that exists, which is what Not Run was introduced to prevent.
     */
    private String sectionNotRun() {
        if (facts.notRunCases().isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(facts.notRunCases()).entrySet()) {
            items.append("""
                            <li class="test-item skipped">
                                <div class="test-header">
                                    <span class="outcome-glyph">·</span>
                                    <span>Not Run — not migrated</span>
                                </div>
                                <div class="test-nodeid">%s %s</div>
                            </li>
                        """.formatted(esc(entry.getKey()), esc(entry.getValue())));
        }
        return section("Not Run (" + facts.notRunCases().size() + ")", items);
    }

    private String sectionPassedTests() {
        StringBuilder items = new StringBuilder();
        for (TestRow test : testsByOutcome.get("passed")) {
            items.append("""
                            <li class="test-item pass">
                                <div class="test-header">
                                    <span class="outcome-glyph">%s</span>
                                    <span>Passed</span>
                                </div>
                                <div class="test-nodeid">%s</div>
                            </li>
                        """.formatted(OutcomeInfo.glyph("pass"), esc(test.nodeid())));
        }
        for (TestRow test : testsByOutcome.get("skipped")) {
            items.append("""
                            <li class="test-item skipped">
                                <div class="test-header">
                                    <span class="outcome-glyph">%s</span>
                                    <span>Skipped</span>
                                </div>
                                <div class="test-nodeid">%s</div>
                            </li>
                        """.formatted(OutcomeInfo.glyph("skipped"), esc(test.nodeid())));
        }
        if (items.isEmpty()) {
            return "";
        }
        return section("Passed & Skipped Tests", items);
    }

    /**
     * A {@code <div class="section">} with a heading and a list of rows. The heading is a
     * constant written here, so it is not escaped: "Passed &amp; Skipped" would change the
     * heading the Python report and its tests read.
     */
    private static String section(String heading, CharSequence items) {
        return """
                <div class="section">
                    <h2>%s</h2>
                    <ul class="test-list">
                %s    </ul>
                </div>
                """.formatted(heading, items);
    }

    /**
     * Escapes the five characters HTML cares about. Every dynamic value that reaches the
     * report goes through here, including the headings.
     */
    static String esc(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? UNKNOWN : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /** The field as text, or {@code null} when it is absent or JSON {@code null}. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** The field as a number, or {@code 0} when it is absent or JSON {@code null}. */
    private static double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0.0 : value.asDouble();
    }

    /** The field as a whole number, or {@code 0} when it is absent or JSON {@code null}. */
    private static long whole(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? 0L : value.asLong();
    }
}
