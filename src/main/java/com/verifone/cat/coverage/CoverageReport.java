package com.verifone.cat.coverage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a run's exec files into reports: per JVM, HTML and XML, summarised, compared, bundled. The Python's
 * {@code petrotest.coverage.report}.
 *
 * <p>Input: {@code <coverage dir>/exec/*.exec} ({@link CoverageDevice#collect}). Output, beside it:
 * <ul>
 *   <li>{@code html/<jvm>/} and {@code xml/<jvm>.xml}: one {@code jacococli report} per exec file, against the class
 *       files {@link ClassFileExtractor} pulled from the build's overlay (and, with a petroapps checkout, the source
 *       roots {@link SourceResolver} resolves);</li>
 *   <li>{@code summary.json} / {@code summary.md} ({@link CoverageSummary}): each JVM's line, branch, method and
 *       class coverage, the line change from the baseline run, and the build's running total;</li>
 *   <li>{@code coverage-<run id>.zip}: everything above but the XML, and the exec files: what is attached to the
 *       run's Jira ticket.</li>
 * </ul>
 *
 * <p><b>The running total</b> lives under {@code <results root>/coverage/<build>/<dist>/}: each JVM's exec merged
 * across every run on that build ({@code jacococli merge}), and {@code runs.json} naming the runs in it per JVM, so
 * reporting a run twice does not count it twice. A Tomcat dump is per register and merges under
 * {@code pos-tomcat}.
 *
 * <p>Everything is bounded ({@link Jacoco.CliRunner}), and a JVM that cannot be reported is a note in the summary,
 * not a stop: the other JVMs' reports still stand.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): only a stub {@code jacococli} has run in this port's tests.
 */
public final class CoverageReport {

    /** A coresvcs report against its 300 source roots took a minute in the petroapps skill. */
    static final Duration REPORT_TIMEOUT = Duration.ofSeconds(900);
    static final Duration MERGE_TIMEOUT = Duration.ofSeconds(300);
    static final String CUMULATIVE_DIR = "coverage";

    /** The summary's file name, beside the exec files. */
    public static final String SUMMARY_FILE = CoverageSummary.SUMMARY_JSON;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CoverageReport() {}

    /**
     * What a report is built from.
     *
     * @param coverageDir the run's {@code coverage} folder, which holds {@code exec/}
     * @param overlay     the build's {@code overlay.zip} (the class files' source)
     * @param dist        the run's distribution, lower-cased as the jar names spell it
     * @param sources     a petroapps checkout for the line-by-line view; null reports numbers only
     * @param buildCommit the build's {@code Commit_Hash}, to say whether the sources match it
     * @param resultsRoot where earlier runs live (baseline) and the running totals are kept; null skips both
     */
    public record ReportInputs(Path coverageDir, Path overlay, String dist, String runId, String build,
            List<String> plans, Path sources, String buildCommit, Path resultsRoot) {

        public ReportInputs {
            dist = dist == null ? "" : dist.toLowerCase(Locale.ROOT);
            runId = runId == null ? "" : runId;
            build = build == null ? "" : build;
            plans = List.copyOf(plans == null ? List.of() : plans);
            buildCommit = buildCommit == null ? "" : buildCommit;
        }

        /** The Python's positional {@code ReportInputs(coverage_dir, overlay, dist, run_id)}. */
        public ReportInputs(Path coverageDir, Path overlay, String dist, String runId) {
            this(coverageDir, overlay, dist, runId, "", List.of(), null, "", null);
        }
    }

    /** The bundle's name for a run. */
    public static String zipName(String runId) {
        return "coverage-" + runId + ".zip";
    }

    /**
     * Writes the reports and the summary; returns the summary.
     *
     * @param tools      the JaCoCo tools, or null to find them (the CLI must be at least 0.8.7)
     * @param cli        runs {@code jacococli}; null for the real process runner
     * @param classfiles the class files, or null to extract them from {@code inputs.overlay()}
     * @throws JacocoUnavailable when {@code tools} is null and no usable CLI is found
     */
    public static CoverageSummary buildReport(ReportInputs inputs, JacocoTools tools, Jacoco.CliRunner cli,
            ClassFiles classfiles, Consumer<String> log) {
        // The tools first, as the Python's: a controller without a usable CLI says so even with nothing collected.
        JacocoTools found = tools != null ? tools
                : Jacoco.findTools(Jacoco.Locations.fromEnvironment(System.getenv()), false);
        Jacoco.CliRunner runner = cli != null ? cli : Jacoco.processRunner();
        Path execDir = inputs.coverageDir().resolve("exec");
        CoverageSummary summary = new CoverageSummary(inputs.runId(), inputs.build(), inputs.dist(), inputs.plans());
        List<Path> execs = listExecs(execDir);
        if (execs.isEmpty()) {
            summary.notes.add("no exec files in " + execDir + ": nothing was collected");
            finish(inputs, summary);
            return summary;
        }

        ClassFiles classes = classfiles;
        if (classes == null) {
            log.accept("      class files from " + inputs.overlay() + " (cached after the first run on a build)");
            classes = ClassFileExtractor.extract(inputs.overlay(), inputs.dist(), log);
        }
        SourceResolver resolver = null;
        if (inputs.sources() != null && Files.isDirectory(inputs.sources())) {
            resolver = new SourceResolver(inputs.sources());
            String note = SourceResolver.sourceCommitNote(inputs.sources(), inputs.buildCommit());
            if (!note.isEmpty()) {
                summary.notes.add(note);
            }
        } else {
            summary.notes.add("no petroapps checkout: reports show counts, not highlighted source");
        }

        Map<String, List<Path>> byComponent = new LinkedHashMap<>();
        for (Path exec : execs) {
            Optional<Component> component = Components.componentFor(exec.getFileName().toString());
            if (component.isEmpty()) {
                summary.notes.add(exec.getFileName() + ": no JVM by that name in the table; not reported");
                continue;
            }
            byComponent.computeIfAbsent(component.get().name(), k -> new ArrayList<>()).add(exec);
            reportOne(inputs, summary, component.get(), exec, classes, resolver, found, runner, log);
        }

        for (Map.Entry<String, List<String>> missing : classes.missing().entrySet()) {
            if (byComponent.containsKey(missing.getKey())) {
                summary.notes.add(missing.getKey() + ": not in this build's overlay, so not measured: "
                        + String.join(", ", missing.getValue()));
            }
        }

        if (inputs.resultsRoot() != null) {
            CoverageSummary baseline = CoverageSummary.baselineFor(inputs.resultsRoot(), inputs.runId(),
                    inputs.plans());
            if (baseline != null) {
                summary.baselineRun = baseline.runId;
                summary.baselineBuild = baseline.build;
                baseline.components.forEach((name, component) -> {
                    Double pct = component.percent("LINE");
                    if (pct != null) {
                        summary.baselineLine.put(name, pct);
                    }
                });
            }
            if (!inputs.build().isEmpty()) {
                mergeCumulative(inputs, summary, byComponent, classes, found, runner, log);
            }
        }
        finish(inputs, summary);
        return summary;
    }

    private static void reportOne(ReportInputs inputs, CoverageSummary summary, Component component, Path exec,
            ClassFiles classfiles, SourceResolver resolver, JacocoTools tools, Jacoco.CliRunner cli,
            Consumer<String> log) {
        // One report per exec file: a register's Tomcat is pos-tomcat-<register>.
        String name = stem(exec);
        List<Path> classes = classfiles.found().getOrDefault(component.name(), List.of());
        if (classes.isEmpty()) {
            summary.notes.add(name + ": none of its class files are in this build's overlay");
            log.accept("SKIP  " + name + ": no class files");
            return;
        }
        List<Path> sourcefiles = resolver == null ? List.of()
                : resolver.rootsFor(component.patterns(inputs.dist()), inputs.dist());
        Path html = inputs.coverageDir().resolve("html").resolve(name);
        Path xml = inputs.coverageDir().resolve("xml").resolve(name + ".xml");
        createDirectories(xml.getParent());
        Jacoco.CliResult result = cli.run(tools,
                Jacoco.reportArgs(List.of(exec), classes, name, html, xml, sourcefiles), REPORT_TIMEOUT);
        if (!result.ok() || !Files.isRegularFile(xml)) {
            summary.notes.add(name + ": jacococli report failed (" + result.detail() + ")");
            log.accept("FAIL  " + name + ": jacococli report failed (" + result.detail() + ")");
            return;
        }
        ComponentCoverage coverage = new ComponentCoverage(name, CoverageSummary.readCounters(xml),
                List.of(exec.getFileName().toString()));
        summary.components.put(name, coverage);
        Double line = coverage.percent("LINE");
        log.accept("OK    " + name + ": " + (line == null ? "-" : CoverageSummary.fixed1(line) + "%") + " of lines"
                + (sourcefiles.isEmpty() ? "" : ", " + sourcefiles.size() + " source root(s)"));
    }

    /** The build's running total: see the class comment. The Python's {@code _merge_cumulative}. */
    static void mergeCumulative(ReportInputs inputs, CoverageSummary summary, Map<String, List<Path>> byComponent,
            ClassFiles classfiles, JacocoTools tools, Jacoco.CliRunner cli, Consumer<String> log) {
        Path store = inputs.resultsRoot().resolve(CUMULATIVE_DIR).resolve(inputs.build()).resolve(inputs.dist());
        createDirectories(store);
        Path runsFile = store.resolve("runs.json");
        Map<String, List<String>> mergedRuns = readRuns(runsFile, store);
        for (Map.Entry<String, List<Path>> entry : byComponent.entrySet()) {
            String componentName = entry.getKey();
            Path total = store.resolve(componentName + ".exec");
            // Per JVM, not per run: a run whose Commander files arrived late adds them to a total its Tomcats are in.
            List<String> runs = mergedRuns.computeIfAbsent(componentName, k -> new ArrayList<>());
            if (!runs.contains(inputs.runId())) {
                List<Path> sources = new ArrayList<>();
                if (Files.isRegularFile(total)) {
                    sources.add(total);
                }
                sources.addAll(entry.getValue());
                Path merged = store.resolve(componentName + ".exec.new");
                Jacoco.CliResult result = cli.run(tools, Jacoco.mergeArgs(sources, merged), MERGE_TIMEOUT);
                if (!result.ok() || !Files.isRegularFile(merged)) {
                    summary.notes.add(componentName + ": not added to the build's total (" + result.detail() + ")");
                    continue;
                }
                try {
                    Files.move(merged, total, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    summary.notes.add(componentName + ": not added to the build's total (" + e.getMessage() + ")");
                    continue;
                }
                runs.add(inputs.runId());
            }
            List<Path> classes = classfiles.found().getOrDefault(componentName, List.of());
            if (classes.isEmpty() || !Files.isRegularFile(total)) {
                continue;
            }
            Path xml = store.resolve(componentName + ".xml");
            Jacoco.CliResult result = cli.run(tools,
                    Jacoco.reportArgs(List.of(total), classes, componentName, null, xml, List.of()), REPORT_TIMEOUT);
            if (result.ok() && Files.isRegularFile(xml)) {
                Double pct = new ComponentCoverage(componentName, CoverageSummary.readCounters(xml)).percent("LINE");
                // Tens of MB per JVM, and only its totals are wanted.
                deleteQuietly(xml);
                if (pct != null) {
                    for (String name : summary.components.keySet()) {
                        if (name.equals(componentName) || (componentName.equals(Components.POS_TOMCAT)
                                && name.startsWith(Components.POS_TOMCAT + "-"))) {
                            summary.cumulativeLine.put(name, pct);
                        }
                    }
                }
            }
        }
        try {
            Files.writeString(runsFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(mergedRuns),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> all = new LinkedHashSet<>();
        mergedRuns.values().forEach(all::addAll);
        log.accept("OK    " + inputs.build() + " total: " + all.size() + " run(s) merged, under " + store);
    }

    /**
     * JVM to the runs merged into its total. A list (the first format, one per run) is read as every JVM already in
     * the store having all of those runs.
     */
    static Map<String, List<String>> readRuns(Path runsFile, Path store) {
        JsonNode data;
        try {
            data = JSON.readTree(runsFile.toFile());
        } catch (IOException | RuntimeException e) {
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (data != null && data.isArray()) {
            List<String> ids = new ArrayList<>();
            data.forEach(node -> ids.add(node.asText()));
            try (DirectoryStream<Path> execs = Files.newDirectoryStream(store, "*.exec")) {
                for (Path exec : execs) {
                    out.put(stem(exec), new ArrayList<>(ids));
                }
            } catch (IOException e) {
                return new LinkedHashMap<>();
            }
            return out;
        }
        if (data != null && data.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = data.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> entry = it.next();
                List<String> ids = new ArrayList<>();
                entry.getValue().forEach(node -> ids.add(node.asText()));
                out.put(entry.getKey(), ids);
            }
        }
        return out;
    }

    /**
     * Writes the summary, then the bundle: the HTML, the exec files and the summary. The XML stays out of the bundle
     * ({@code summary.json} carries its totals; on Solutions1082 it was 100 MB of a 160 MB bundle) and stays in the
     * run directory for any tool that reads JaCoCo XML.
     */
    private static void finish(ReportInputs inputs, CoverageSummary summary) {
        Path dir = inputs.coverageDir();
        createDirectories(dir);
        summary.write(dir);
        Path bundle = dir.resolve(zipName(inputs.runId()));
        try {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            try (OutputStream out = Files.newOutputStream(bundle);
                    ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.setLevel(Deflater.BEST_SPEED);
                for (Path path : files) {
                    Path relative = dir.relativize(path);
                    if (path.equals(bundle) || relative.getName(0).toString().equals("xml")) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> listExecs(Path execDir) {
        if (!Files.isDirectory(execDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(execDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".exec") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stem(Path exec) {
        String name = exec.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // A leftover XML costs disk, not correctness.
        }
    }
}
