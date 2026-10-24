package com.verifone.cat.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The report and bundle step, with a stub {@code jacococli} that writes JaCoCo's XML. The real CLI is never run.
 */
class CoverageReportTest {

    private static final JacocoTools TOOLS = new JacocoTools(Path.of("java.exe"), Path.of("jacococli.jar"), null);

    @TempDir
    Path tmp;

    /** The counters a stub report carries, by JVM name: {@code {covered, missed}} for the LINE counter. */
    private static Jacoco.CliRunner stub(Map<String, long[]> lines, List<List<String>> calls, Set<String> failing) {
        return (tools, args, timeout) -> {
            calls.add(args);
            String name = args.get(args.indexOf("--name") + 1);
            if (failing.contains(name)) {
                return new Jacoco.CliResult(false, "Can't add different class with same name: A");
            }
            long[] counts = lines.getOrDefault(name, new long[] {0, 0});
            Path xml = Path.of(args.get(args.indexOf("--xml") + 1));
            try {
                Files.createDirectories(xml.getParent());
                Files.writeString(xml, xmlReport(name, counts[0], counts[1]), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Jacoco.CliResult(true, "");
        };
    }

    private static String xmlReport(String name, long covered, long missed) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<report name=\"" + name + "\">"
                + "<package name=\"x\"><counter type=\"LINE\" missed=\"0\" covered=\"1\"/></package>"
                + "<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"9\"/>"
                + "<counter type=\"LINE\" missed=\"" + missed + "\" covered=\"" + covered + "\"/>"
                + "</report>";
    }

    private Path exec(Path coverage, String name) throws IOException {
        Path file = coverage.resolve("exec").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, name, StandardCharsets.UTF_8);
        return file;
    }

    private static CoverageReport.ClassFiles classes(String... names) {
        Map<String, List<Path>> found = new HashMap<>();
        for (String name : names) {
            found.put(name, List.of(Path.of(name + ".jar")));
        }
        return new CoverageReport.ClassFiles(found, Map.of());
    }

    private static CoverageReport.ReportInputs inputs(Path coverage) {
        return new CoverageReport.ReportInputs(coverage, "Buypass", "20261024T153000Z", "Solutions90",
                List.of("PETROCORE-135127"));
    }

    private static List<String> zipEntries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            archive.stream().forEach(entry -> names.add(entry.getName()));
        }
        return names;
    }

    @Test
    void eachExecFileIsReportedSummarisedAndBundled() throws IOException {
        Path coverage = tmp.resolve("coverage");
        exec(coverage, "fcc.exec");
        exec(coverage, "pmc.exec");
        List<List<String>> calls = new ArrayList<>();
        List<String> log = new ArrayList<>();

        CoverageReport.Summary summary = CoverageReport.buildReport(inputs(coverage),
                classes("fcc", "pmc"), TOOLS,
                stub(Map.of("fcc", new long[] {3, 1}), calls, Set.of()), log::add);

        assertEquals(2, calls.size());
        assertEquals(75.0, summary.components().get("fcc"), 0.001);
        assertNull(summary.components().get("pmc"), "a JVM with no lines in the stub has no percentage");
        assertTrue(Files.isRegularFile(coverage.resolve("summary.json")));
        List<String> entries = zipEntries(coverage.resolve(CoverageReport.zipName("20261024T153000Z")));
        assertTrue(entries.contains("exec/fcc.exec"), entries.toString());
        assertTrue(entries.contains("summary.json"), entries.toString());
        assertFalse(entries.stream().anyMatch(e -> e.startsWith("xml/")), "the XML stays out of the bundle");
    }

    @Test
    void aJvmReportIsNamedByItsExecStem() throws IOException {
        Path coverage = tmp.resolve("coverage");
        exec(coverage, "pos-tomcat-topaz101.exec");
        List<List<String>> calls = new ArrayList<>();

        CoverageReport.Summary summary = CoverageReport.buildReport(inputs(coverage),
                classes("pos-tomcat"), TOOLS, stub(Map.of("pos-tomcat-topaz101", new long[] {1, 1}), calls,
                        Set.of()), line -> { });

        assertTrue(summary.components().containsKey("pos-tomcat-topaz101"));
        assertTrue(Files.isRegularFile(coverage.resolve("xml").resolve("pos-tomcat-topaz101.xml")));
    }

    @Test
    void noExecFilesIsSaidNotHiddenAndStillBundled() throws IOException {
        Path coverage = tmp.resolve("coverage");
        Files.createDirectories(coverage);

        CoverageReport.Summary summary = CoverageReport.buildReport(inputs(coverage),
                CoverageReport.ClassFiles.none(), TOOLS, stub(Map.of(), new ArrayList<>(), Set.of()), line -> { });

        assertTrue(summary.notes().stream().anyMatch(n -> n.contains("nothing was collected")), summary.notes().toString());
        assertTrue(Files.isRegularFile(coverage.resolve(CoverageReport.zipName("20261024T153000Z"))));
    }

    @Test
    void anExecFileNothingNamesIsNotedAndNotReported() throws IOException {
        Path coverage = tmp.resolve("coverage");
        exec(coverage, "mystery.exec");
        List<List<String>> calls = new ArrayList<>();

        CoverageReport.Summary summary = CoverageReport.buildReport(inputs(coverage),
                classes("fcc"), TOOLS, stub(Map.of(), calls, Set.of()), line -> { });

        assertTrue(calls.isEmpty());
        assertTrue(summary.notes().stream().anyMatch(n -> n.startsWith("mystery.exec: no JVM by that name")),
                summary.notes().toString());
    }

    @Test
    void aFailedReportIsANoteAndTheOtherJvmsStillStand() throws IOException {
        Path coverage = tmp.resolve("coverage");
        exec(coverage, "fcc.exec");
        exec(coverage, "pmc.exec");

        CoverageReport.Summary summary = CoverageReport.buildReport(inputs(coverage),
                classes("fcc", "pmc"), TOOLS, stub(Map.of("pmc", new long[] {2, 2}), new ArrayList<>(),
                        Set.of("fcc")), line -> { });

        assertFalse(summary.components().containsKey("fcc"));
        assertEquals(50.0, summary.components().get("pmc"), 0.001);
        assertTrue(summary.notes().stream().anyMatch(n -> n.startsWith("fcc: jacococli report failed")),
                summary.notes().toString());
    }

    @Test
    void aJvmWithNoClassFilesInTheBuildIsSkippedWithoutACliCall() throws IOException {
        Path coverage = tmp.resolve("coverage");
        exec(coverage, "kiosk.exec");
        List<List<String>> calls = new ArrayList<>();

        CoverageReport.Summary summary = CoverageReport.buildReport(inputs(coverage),
                CoverageReport.ClassFiles.none(), TOOLS, stub(Map.of(), calls, Set.of()), line -> { });

        assertTrue(calls.isEmpty());
        assertTrue(summary.notes().stream().anyMatch(n -> n.contains("none of its class files")),
                summary.notes().toString());
    }

    @Test
    void theLineCounterIsReadFromTheReportLevelCountersOnly() throws IOException {
        Path xml = tmp.resolve("fcc.xml");
        Files.writeString(xml, xmlReport("fcc", 3, 1), StandardCharsets.UTF_8);

        assertEquals(75.0, CoverageReport.linePercent(xml), 0.001);
    }

    @Test
    void aReportWithNoLinesHasNoPercentage() throws IOException {
        Path xml = tmp.resolve("empty.xml");
        Files.writeString(xml, xmlReport("empty", 0, 0), StandardCharsets.UTF_8);

        assertNull(CoverageReport.linePercent(xml));
    }

    @Test
    void theZipNameCarriesTheRunId() {
        assertEquals("coverage-20261024T153000Z.zip", CoverageReport.zipName("20261024T153000Z"));
    }

    @Test
    void theDistributionIsLowerCasedAsTheJarNamesSpellIt() {
        assertEquals("buypass", inputs(tmp).dist());
    }
}
