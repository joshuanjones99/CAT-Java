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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The report, summary, running total and bundle, with a stub {@code jacococli} that writes JaCoCo's XML (and a merged
 * exec for {@code merge}). The real CLI is never run. The Python's {@code TestReport}, and the earlier Java tests.
 */
class CoverageReportTest {

    private static final JacocoTools TOOLS = new JacocoTools(Path.of("java.exe"), Path.of("jacococli.jar"), null);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    /** The stub CLI: {@code {covered, missed}} for the LINE counter by report name; {@code failing} names refuse. */
    private static Jacoco.CliRunner stub(Map<String, long[]> lines, List<List<String>> calls, Set<String> failing) {
        return (tools, args, timeout) -> {
            calls.add(args);
            try {
                if (args.get(0).equals("merge")) {
                    Files.writeString(Path.of(args.get(args.indexOf("--destfile") + 1)), "merged");
                    return new Jacoco.CliResult(true, "");
                }
                String name = args.get(args.indexOf("--name") + 1);
                if (failing.contains(name)) {
                    return new Jacoco.CliResult(false, "Can't add different class with same name: A");
                }
                long[] counts = lines.getOrDefault(name, new long[] {0, 10});
                Path xml = Path.of(args.get(args.indexOf("--xml") + 1));
                Files.createDirectories(xml.getParent());
                Files.writeString(xml, xmlReport(name, counts[0], counts[1]), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Jacoco.CliResult(true, "");
        };
    }

    private static Jacoco.CliRunner stub(Map<String, long[]> lines, List<List<String>> calls) {
        return stub(lines, calls, Set.of());
    }

    private static String xmlReport(String name, long covered, long missed) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">"
                + "<report name=\"" + name + "\">"
                + "<package name=\"x\"><counter type=\"LINE\" missed=\"0\" covered=\"1\"/></package>"
                + "<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"9\"/>"
                + "<counter type=\"LINE\" missed=\"" + missed + "\" covered=\"" + covered + "\"/>"
                + "<counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/>"
                + "</report>";
    }

    /** The Python's {@code _run}: a run folder under {@code results/} with these exec files. */
    private Path run(String runId, String... execs) throws IOException {
        Path coverage = tmp.resolve("results").resolve(runId).resolve("coverage");
        Files.createDirectories(coverage.resolve("exec"));
        for (String name : execs) {
            Files.writeString(coverage.resolve("exec").resolve(name), "data");
        }
        return coverage;
    }

    /** The Python's {@code _classfiles}: every component's class files are one jar. */
    private ClassFiles allClasses() throws IOException {
        Path jar = tmp.resolve("classes").resolve("x.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        Map<String, List<Path>> found = new LinkedHashMap<>();
        Components.COMPONENTS.forEach(c -> found.put(c.name(), List.of(jar)));
        return new ClassFiles(tmp.resolve("classes"), found, Map.of());
    }

    private static ClassFiles classes(String... names) {
        Map<String, List<Path>> found = new LinkedHashMap<>();
        for (String name : names) {
            found.put(name, List.of(Path.of(name + ".jar")));
        }
        return new ClassFiles(Path.of("."), found, Map.of());
    }

    private CoverageReport.ReportInputs inputs(Path coverage, String runId, String build, List<String> plans) {
        return new CoverageReport.ReportInputs(coverage, tmp.resolve("overlay.zip"), "buypass", runId, build, plans,
                null, "", tmp.resolve("results"));
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
        Path coverage = run("run1", "fcc.exec", "pos-tomcat-topaz101.exec", "odd.exec");
        List<List<String>> calls = new ArrayList<>();

        CoverageSummary result = CoverageReport.buildReport(inputs(coverage, "run1", "Solutions1082",
                List.of("PETROCORE-1")), TOOLS, stub(Map.of("fcc", new long[] {30, 70}), calls), allClasses(),
                line -> { });

        assertEquals(Set.of("fcc", "pos-tomcat-topaz101"), result.components.keySet());
        assertEquals(30.0, result.components.get("fcc").percent("LINE"), 1e-9);
        assertTrue(result.notes.stream().anyMatch(n -> n.contains("odd.exec")), result.notes.toString());
        assertTrue(result.notes.stream().anyMatch(n -> n.contains("no petroapps checkout")), result.notes.toString());
        // The bundle: HTML, exec files and the summary; not the XML.
        List<String> names = zipEntries(coverage.resolve("coverage-run1.zip"));
        assertTrue(names.contains("summary.md") && names.contains("exec/fcc.exec"), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.startsWith("xml/")), names.toString());
        // The build's running total: one merge per JVM, and the run recorded once.
        Path store = tmp.resolve("results").resolve("coverage").resolve("Solutions1082").resolve("buypass");
        assertEquals(Map.of("fcc", List.of("run1"), "pos-tomcat", List.of("run1")),
                JSON.readValue(store.resolve("runs.json").toFile(), Map.class));
        assertEquals(30.0, result.cumulativeLine.get("fcc"), 1e-9);
        try (Stream<Path> files = Files.list(store)) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".xml")), "read for its totals, then removed");
        }
    }

    @Test
    void reportingARunTwiceDoesNotCountItTwice() throws IOException {
        Path coverage = run("run1", "fcc.exec");
        List<List<String>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.clear();
            CoverageReport.buildReport(inputs(coverage, "run1", "Solutions1082", List.of()), TOOLS,
                    stub(Map.of(), calls), allClasses(), line -> { });
        }
        assertFalse(calls.stream().anyMatch(args -> args.get(0).equals("merge")), "the second time");
    }

    @Test
    void aJvmsFilesThatArriveLaterStillJoinTheTotal() throws IOException {
        // Run da4bfa334bd9: its Tomcats were merged, its Commander files recovered later.
        Path coverage = run("run1", "commander-tomcat.exec");
        assertEquals(1, merges(coverage).size());
        Files.writeString(coverage.resolve("exec").resolve("fcc.exec"), "recovered");
        List<List<String>> merges = merges(coverage);
        assertEquals(List.of(tmp.resolve("results").resolve("coverage").resolve("Solutions1082").resolve("buypass")
                .resolve("fcc.exec.new").toString()), merges.stream().map(a -> a.get(a.indexOf("--destfile") + 1))
                .toList());
    }

    private List<List<String>> merges(Path coverage) throws IOException {
        List<List<String>> calls = new ArrayList<>();
        CoverageReport.buildReport(inputs(coverage, "run1", "Solutions1082", List.of()), TOOLS, stub(Map.of(), calls),
                allClasses(), line -> { });
        return calls.stream().filter(args -> args.get(0).equals("merge")).toList();
    }

    @Test
    void theLineChangeIsAgainstTheLastRunOfTheSamePlans() throws IOException {
        report("a", List.of("PETROCORE-1"), 20);
        report("other-plan", List.of("PETROCORE-2"), 90);
        CoverageSummary latest = report("b", List.of("PETROCORE-1"), 25);

        assertEquals("a", latest.baselineRun);
        List<String> row = latest.rows().stream().filter(r -> r.get(0).equals("fcc")).findFirst().orElseThrow();
        assertEquals("+5.0", row.get(5));
        assertTrue(latest.markdown().contains("Line Δ is against run a"), latest.markdown());
    }

    private CoverageSummary report(String runId, List<String> plans, long covered) throws IOException {
        return CoverageReport.buildReport(inputs(run(runId, "fcc.exec"), runId, "Solutions1082", plans), TOOLS,
                stub(Map.of("fcc", new long[] {covered, 100 - covered}), new ArrayList<>()), allClasses(), line -> { });
    }

    @Test
    void nothingCollectedIsSaidNotHiddenAndStillBundled() throws IOException {
        Path coverage = run("run1");
        CoverageSummary result = CoverageReport.buildReport(new CoverageReport.ReportInputs(coverage,
                tmp.resolve("overlay.zip"), "buypass", "run1"), TOOLS, stub(Map.of(), new ArrayList<>()),
                allClasses(), line -> { });
        assertTrue(result.components.isEmpty());
        assertTrue(result.notes.get(0).contains("nothing was collected"), result.notes.toString());
        assertTrue(Files.isRegularFile(coverage.resolve(CoverageReport.zipName("run1"))));
        assertTrue(Files.isRegularFile(coverage.resolve(CoverageSummary.SUMMARY_MD)));
    }

    @Test
    void aFailedReportIsANoteAndTheOtherJvmsStillStand() throws IOException {
        Path coverage = run("run1", "fcc.exec", "pmc.exec");
        List<String> log = new ArrayList<>();

        CoverageSummary result = CoverageReport.buildReport(new CoverageReport.ReportInputs(coverage,
                tmp.resolve("overlay.zip"), "Buypass", "run1"), TOOLS,
                stub(Map.of("pmc", new long[] {2, 2}), new ArrayList<>(), Set.of("fcc")), classes("fcc", "pmc"),
                log::add);

        assertFalse(result.components.containsKey("fcc"));
        assertEquals(50.0, result.components.get("pmc").percent(), 1e-9);
        assertTrue(result.notes.stream().anyMatch(n -> n.startsWith("fcc: jacococli report failed")),
                result.notes.toString());
        assertTrue(log.contains("OK    pmc: 50.0% of lines"), log.toString());
        assertTrue(log.contains("FAIL  fcc: jacococli report failed (Can't add different class with same name: A)"),
                log.toString());
    }

    @Test
    void aJvmWithNoClassFilesInTheBuildIsSkippedWithoutACliCall() throws IOException {
        Path coverage = run("run1", "kiosk.exec");
        List<List<String>> calls = new ArrayList<>();
        Map<String, List<String>> missing = Map.of("kiosk", List.of("isdApps.psc.kiosk.foodserviceupdater.app-V1.jar"));

        CoverageSummary result = CoverageReport.buildReport(new CoverageReport.ReportInputs(coverage,
                tmp.resolve("overlay.zip"), "buypass", "run1"), TOOLS, stub(Map.of(), calls),
                new ClassFiles(tmp, Map.of(), missing), line -> { });

        assertTrue(calls.isEmpty());
        assertTrue(result.notes.stream().anyMatch(n -> n.contains("none of its class files")), result.notes.toString());
        assertTrue(result.notes.stream().anyMatch(n -> n.startsWith("kiosk: not in this build's overlay")),
                result.notes.toString());
    }

    @Test
    void sourceRootsArePassedToTheReportAndTheCommitIsNoted() throws IOException {
        Path petroapps = tmp.resolve("petroapps");
        Files.createDirectories(petroapps.resolve("isdApps/psc/fcc/app/src"));
        Path coverage = run("run1", "fcc.exec");
        List<List<String>> calls = new ArrayList<>();
        List<String> log = new ArrayList<>();

        CoverageSummary result = CoverageReport.buildReport(new CoverageReport.ReportInputs(coverage,
                tmp.resolve("overlay.zip"), "buypass", "run1", "", List.of(), petroapps, "abc", null), TOOLS,
                stub(Map.of("fcc", new long[] {1, 1}), calls), classes("fcc"), log::add);

        List<String> args = calls.get(0);
        assertEquals(petroapps.resolve("isdApps/psc/fcc/app/src").toRealPath().toString(),
                args.get(args.indexOf("--sourcefiles") + 1));
        assertTrue(log.contains("OK    fcc: 50.0% of lines, 1 source root(s)"), log.toString());
        assertTrue(result.notes.stream().anyMatch(n -> n.startsWith("sources from " + petroapps)),
                result.notes.toString());
    }

    @Test
    void aJvmReportIsNamedByItsExecStem() throws IOException {
        Path coverage = run("run1", "pos-tomcat-topaz101.exec");
        CoverageSummary result = CoverageReport.buildReport(new CoverageReport.ReportInputs(coverage,
                tmp.resolve("overlay.zip"), "buypass", "run1"), TOOLS,
                stub(Map.of("pos-tomcat-topaz101", new long[] {1, 1}), new ArrayList<>()), classes("pos-tomcat"),
                line -> { });
        assertTrue(result.components.containsKey("pos-tomcat-topaz101"));
        assertTrue(Files.isRegularFile(coverage.resolve("xml").resolve("pos-tomcat-topaz101.xml")));
    }

    @Test
    void theFirstRunsJsonFormatIsReadAsEveryJvmHavingThoseRuns() throws IOException {
        Path store = Files.createDirectories(tmp.resolve("store"));
        Files.writeString(store.resolve("fcc.exec"), "x");
        Files.writeString(store.resolve("pmc.exec"), "x");
        Files.writeString(store.resolve("runs.json"), "[\"r1\", \"r2\"]");
        assertEquals(Map.of("fcc", List.of("r1", "r2"), "pmc", List.of("r1", "r2")),
                CoverageReport.readRuns(store.resolve("runs.json"), store));
        assertEquals(Map.of(), CoverageReport.readRuns(store.resolve("absent.json"), store));
    }

    @Test
    void aFailedMergeIsANoteAndTheRunIsNotRecorded() throws IOException {
        Path coverage = run("run1", "fcc.exec");
        Jacoco.CliRunner fcc = stub(Map.of("fcc", new long[] {1, 1}), new ArrayList<>());
        Jacoco.CliRunner refusesMerge = (tools, args, timeout) -> args.get(0).equals("merge")
                ? new Jacoco.CliResult(false, "disk full") : fcc.run(tools, args, timeout);

        CoverageSummary result = CoverageReport.buildReport(inputs(coverage, "run1", "Solutions1082", List.of()),
                TOOLS, refusesMerge, allClasses(), line -> { });

        assertTrue(result.notes.contains("fcc: not added to the build's total (disk full)"), result.notes.toString());
        assertNull(result.cumulativeLine.get("fcc"));
    }

    @Test
    void theZipNameCarriesTheRunIdAndTheDistributionIsLowerCased() {
        assertEquals("coverage-20261024T153000Z.zip", CoverageReport.zipName("20261024T153000Z"));
        assertEquals("buypass", new CoverageReport.ReportInputs(tmp, tmp, "Buypass", "r").dist());
    }

    @Test
    void theMergeArgsNameEveryInputAndTheDestination() {
        assertEquals(List.of("merge", Path.of("a.exec").toString(), Path.of("b.exec").toString(), "--destfile",
                Path.of("t.exec").toString(), "--quiet"),
                Jacoco.mergeArgs(List.of(Path.of("a.exec"), Path.of("b.exec")), Path.of("t.exec")));
    }
}
