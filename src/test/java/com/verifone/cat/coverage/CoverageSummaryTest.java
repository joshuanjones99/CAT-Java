package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The summary's numbers, table, markdown, JSON and baseline, against the Python's {@code summary.py}. */
class CoverageSummaryTest {

    @TempDir
    Path tmp;

    private static CoverageSummary summary() {
        CoverageSummary summary = new CoverageSummary("run1", "Solutions1082", "buypass", List.of("PETROCORE-1"));
        summary.components.put("fcc", new ComponentCoverage("fcc", Map.of("LINE", new long[] {25, 75},
                "BRANCH", new long[] {1, 1}), List.of("fcc.exec")));
        summary.components.put("cdm", new ComponentCoverage("cdm", Map.of("LINE", new long[] {0, 0}), List.of()));
        return summary;
    }

    @Test
    void eachRowCarriesThePercentagesTheDeltaAndTheBuildTotal() {
        CoverageSummary summary = summary();
        summary.baselineLine.put("fcc", 20.0);
        summary.cumulativeLine.put("fcc", 31.25);

        List<List<String>> rows = summary.rows();

        assertEquals(List.of("cdm", "-", "-", "-", "-", "-", "-"), rows.get(0), "sorted by name; no lines is -");
        assertEquals(List.of("fcc", "25.0% (25/100)", "50.0% (1/2)", "-", "-", "+5.0", "31.2%"), rows.get(1));
        assertEquals(List.of("Component", "Line", "Branch", "Method", "Class", "Line Δ", "Solutions1082 total"),
                summary.header());
    }

    @Test
    void numbersAreFormattedAsPythonFormatsThem() {
        // Half-even on the double's exact value: Python's f"{0.25:.1f}" is "0.2", f"{0.35:.1f}" is "0.3".
        assertEquals("0.2", CoverageSummary.fixed1(0.25));
        assertEquals("0.3", CoverageSummary.fixed1(0.35));
        assertEquals("33.3", CoverageSummary.fixed1(100.0 / 3));
        assertEquals("+0.0", CoverageSummary.signed1(0.0));
        assertEquals("-0.0", CoverageSummary.signed1(-0.04));
        assertEquals("-2.5", CoverageSummary.signed1(-2.5));
    }

    @Test
    void theMarkdownNamesTheRunTheBuildTheBaselineAndTheNotes() {
        CoverageSummary summary = summary();
        summary.baselineRun = "a";
        summary.baselineBuild = "Solutions1081";
        summary.notes.add("no petroapps checkout: reports show counts, not highlighted source");

        String markdown = summary.markdown();

        assertTrue(markdown.startsWith("# JaCoCo coverage — run run1\n\nBuild Solutions1082 (buypass); plans "
                + "PETROCORE-1.\nLine Δ is against run a (Solutions1081).\n\n| Component | Line |"), markdown);
        assertTrue(markdown.contains("|---|---|---|---|---|---|---|\n"), markdown);
        assertTrue(markdown.endsWith("Notes:\n- no petroapps checkout: reports show counts, not highlighted source\n"),
                markdown);
        assertTrue(new CoverageSummary().markdown().contains("Build unknown (); plans none."));
    }

    @Test
    void theJsonRoundTripsWithThePythonsKeys() throws IOException {
        CoverageSummary summary = summary();
        summary.baselineLine.put("fcc", 20.0);
        summary.write(tmp);

        String json = Files.readString(tmp.resolve(CoverageSummary.SUMMARY_JSON));
        assertTrue(json.contains("\"run_id\"") && json.contains("\"exec_files\"") && json.contains("\"baseline_line\""),
                json);
        assertTrue(Files.isRegularFile(tmp.resolve(CoverageSummary.SUMMARY_MD)));
        CoverageSummary back = CoverageSummary.load(tmp.resolve(CoverageSummary.SUMMARY_JSON));
        assertEquals(summary.rows(), back.rows());
        assertEquals(List.of("fcc.exec"), back.components.get("fcc").execFiles());
    }

    @Test
    void aPythonWrittenSummaryLoads() throws IOException {
        Path file = tmp.resolve("summary.json");
        Files.writeString(file, "{\"run_id\": \"run1\", \"build\": \"Solutions1082\", \"dist\": \"buypass\", "
                + "\"plans\": [\"PETROCORE-1\"], \"components\": {\"fcc\": {\"name\": \"fcc\", \"counters\": "
                + "{\"LINE\": [25, 75]}, \"exec_files\": [\"fcc.exec\"]}}, \"notes\": []}");
        CoverageSummary summary = CoverageSummary.load(file);
        assertEquals(25.0, summary.components.get("fcc").percent("LINE"), 1e-9);
        assertNull(CoverageSummary.load(tmp.resolve("absent.json")));
        Files.writeString(tmp.resolve("bad.json"), "[1, 2]");
        assertNull(CoverageSummary.load(tmp.resolve("bad.json")));
    }

    @Test
    void theCountersAreTheReportsOwnNotItsPackagesAndTheDoctypeIsAllowed() throws IOException {
        Path xml = tmp.resolve("fcc.xml");
        Files.writeString(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">"
                + "<report name=\"fcc\"><package name=\"p\"><counter type=\"LINE\" missed=\"9\" covered=\"9\"/>"
                + "</package><counter type=\"INSTRUCTION\" missed=\"5\" covered=\"6\"/>"
                + "<counter type=\"LINE\" missed=\"3\" covered=\"1\"/><counter type=\"CLASS\" missed=\"2\" covered=\"0\"/>"
                + "</report>");

        Map<String, long[]> counters = CoverageSummary.readCounters(xml);

        assertEquals(List.of("INSTRUCTION", "LINE", "CLASS"), List.copyOf(counters.keySet()));
        assertArrayEquals(new long[] {1, 3}, counters.get("LINE"));
        assertEquals(25.0, new ComponentCoverage("fcc", counters).percent(), 1e-9);
    }

    @Test
    void theBaselineIsTheNewestOtherRunOfTheSamePlans() throws IOException {
        write("a", List.of("PETROCORE-1"), Instant.parse("2026-10-01T00:00:00Z"));
        write("b", List.of("PETROCORE-1"), Instant.parse("2026-10-02T00:00:00Z"));
        write("other", List.of("PETROCORE-2"), Instant.parse("2026-10-03T00:00:00Z"));
        write("now", List.of("PETROCORE-1"), Instant.parse("2026-10-04T00:00:00Z"));

        assertEquals("b", CoverageSummary.baselineFor(tmp, "now", List.of("PETROCORE-1")).runId);
        assertNull(CoverageSummary.baselineFor(tmp, "now", List.of()));
        assertNull(CoverageSummary.baselineFor(tmp.resolve("absent"), "now", List.of("PETROCORE-1")));
        assertNull(CoverageSummary.baselineFor(tmp, "now", List.of("PETROCORE-9")));
    }

    private void write(String runId, List<String> plans, Instant at) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(runId).resolve("coverage"));
        CoverageSummary summary = new CoverageSummary(runId, "Solutions1082", "buypass", plans);
        summary.components.put("fcc", new ComponentCoverage("fcc", Map.of("LINE", new long[] {1, 1})));
        summary.write(dir);
        Files.setLastModifiedTime(dir.resolve(CoverageSummary.SUMMARY_JSON), FileTime.from(at));
    }
}
