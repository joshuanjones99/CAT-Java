package com.verifone.cat.coverage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a run's exec files into reports: per JVM, HTML and XML, a summary, and the bundle attached to the
 * run's Jira ticket.
 *
 * <p>Input: {@code <coverage dir>/exec/*.exec}, as {@link CoverageDevice#collect} writes them. Output, beside
 * it: {@code html/<jvm>/} and {@code xml/<jvm>.xml} (one {@code jacococli report} per exec file, against the
 * class files the caller found), {@code summary.json} (each JVM's line coverage), and
 * {@code coverage-<run id>.zip} (everything but the XML, which is large and whose totals the summary already
 * carries).
 *
 * <p>A JVM that cannot be reported is a note in the summary, not a stop: the other JVMs' reports still stand.
 *
 * <p><b>Not ported yet</b> (the Python {@code petrotest.coverage.report}, {@code classes}, {@code sources} and
 * {@code summary}): extracting class files from the build's {@code overlay.zip} (the caller passes them in,
 * so {@link ClassFiles} is the seam); the petroapps source roots; the line change against the last run of the
 * same plans; the build's running total ({@code jacococli merge} into {@code <results>/coverage/<build>/});
 * and {@code summary.md}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CoverageReport {

    /** A coresvcs report against its source roots took a minute in the petroapps skill; this allows fifteen. */
    static final Duration REPORT_TIMEOUT = Duration.ofMinutes(15);

    /** The summary's file name, beside the exec files. */
    public static final String SUMMARY_FILE = "summary.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CoverageReport() {}

    /**
     * The class files each component's exec file is reported against.
     *
     * @param found   component name to the extracted jar and war files, in pattern order
     * @param missing component name to the patterns no package of the build matched
     */
    public record ClassFiles(Map<String, List<Path>> found, Map<String, List<String>> missing) {

        public ClassFiles {
            found = Map.copyOf(found);
            missing = Map.copyOf(missing);
        }

        public static ClassFiles none() {
            return new ClassFiles(Map.of(), Map.of());
        }
    }

    /**
     * Where a run's coverage lives.
     *
     * @param coverageDir the run's {@code coverage} folder, which holds {@code exec/}
     * @param dist        the run's distribution, lower-cased as the jar names spell it
     */
    public record ReportInputs(Path coverageDir, String dist, String runId, String build, List<String> plans) {

        public ReportInputs {
            dist = dist == null ? "" : dist.toLowerCase(Locale.ROOT);
            plans = List.copyOf(plans == null ? List.of() : plans);
        }
    }

    /**
     * The summary written as {@code summary.json}.
     *
     * @param components  JVM name to its line coverage in percent, or null when it has no lines
     * @param notes       what could not be measured, and why
     */
    public record Summary(String runId, String build, String dist, List<String> plans,
            Map<String, Double> components, List<String> notes) {}

    /** The bundle's name for a run. */
    public static String zipName(String runId) {
        return "coverage-" + runId + ".zip";
    }

    /**
     * Writes the reports, the summary and the bundle, and returns the summary.
     *
     * @param tools the JaCoCo tools, found by the caller (the CLI must be at least 0.8.7)
     */
    public static Summary buildReport(ReportInputs inputs, ClassFiles classfiles, JacocoTools tools,
            Jacoco.CliRunner cli, Consumer<String> log) {
        List<String> notes = new ArrayList<>();
        Map<String, Double> components = new LinkedHashMap<>();
        Path execDir = inputs.coverageDir().resolve("exec");
        List<Path> execs = listExecs(execDir);
        if (execs.isEmpty()) {
            notes.add("no exec files in " + execDir + ": nothing was collected");
            return finish(inputs, new Summary(inputs.runId(), inputs.build(), inputs.dist(), inputs.plans(),
                    components, notes));
        }
        notes.add("no petroapps checkout is read: reports show counts, not highlighted source");

        List<String> reported = new ArrayList<>();
        for (Path exec : execs) {
            var component = Components.componentFor(exec.getFileName().toString());
            if (component.isEmpty()) {
                notes.add(exec.getFileName() + ": no JVM by that name in the table; not reported");
                continue;
            }
            reported.add(component.get().name());
            reportOne(inputs, exec, component.get(), classfiles, tools, cli, components, notes, log);
        }

        for (Map.Entry<String, List<String>> missing : classfiles.missing().entrySet()) {
            if (reported.contains(missing.getKey())) {
                notes.add(missing.getKey() + ": not in this build's overlay, so not measured: "
                        + String.join(", ", missing.getValue()));
            }
        }
        return finish(inputs, new Summary(inputs.runId(), inputs.build(), inputs.dist(), inputs.plans(),
                components, notes));
    }

    private static void reportOne(ReportInputs inputs, Path exec, Component component, ClassFiles classfiles,
            JacocoTools tools, Jacoco.CliRunner cli, Map<String, Double> components, List<String> notes,
            Consumer<String> log) {
        // One report per exec file: a register's Tomcat is pos-tomcat-<register>.
        String name = stem(exec);
        List<Path> classes = classfiles.found().getOrDefault(component.name(), List.of());
        if (classes.isEmpty()) {
            notes.add(name + ": none of its class files are in this build's overlay");
            log.accept("SKIP  " + name + ": no class files");
            return;
        }
        Path html = inputs.coverageDir().resolve("html").resolve(name);
        Path xml = inputs.coverageDir().resolve("xml").resolve(name + ".xml");
        try {
            Files.createDirectories(xml.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Jacoco.CliResult result = cli.run(tools, Jacoco.reportArgs(List.of(exec), classes, name, html, xml, List.of()),
                REPORT_TIMEOUT);
        if (!result.ok() || !Files.isRegularFile(xml)) {
            notes.add(name + ": jacococli report failed (" + result.detail() + ")");
            log.accept("FAIL  " + name + ": jacococli report failed (" + result.detail() + ")");
            return;
        }
        Double line = linePercent(xml);
        components.put(name, line);
        log.accept("OK    " + name + ": " + (line == null ? "-" : String.format(Locale.ROOT, "%.1f%%", line))
                + " of lines");
    }

    /** The line coverage of a JaCoCo XML report, from its report-level {@code LINE} counter; null with no lines. */
    static Double linePercent(Path xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document document = factory.newDocumentBuilder().parse(xml.toFile());
            NodeList children = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element counter && "counter".equals(counter.getTagName())
                        && "LINE".equals(counter.getAttribute("type"))) {
                    long covered = Long.parseLong(counter.getAttribute("covered"));
                    long missed = Long.parseLong(counter.getAttribute("missed"));
                    long total = covered + missed;
                    return total == 0 ? null : covered * 100.0 / total;
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + xml + ": " + e.getMessage(), e);
        }
    }

    /** Writes {@code summary.json} and the bundle; the bundle holds everything in the folder except the XML. */
    private static Summary finish(ReportInputs inputs, Summary summary) {
        Path dir = inputs.coverageDir();
        try {
            Files.createDirectories(dir);
            JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(SUMMARY_FILE).toFile(), summary);
            writeBundle(dir, dir.resolve(zipName(inputs.runId())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return summary;
    }

    private static void writeBundle(Path dir, Path bundle) throws IOException {
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
    }

    private static List<Path> listExecs(Path execDir) {
        if (!Files.isDirectory(execDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(execDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".exec")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stem(Path exec) {
        String name = exec.getFileName().toString();
        return name.substring(0, name.length() - ".exec".length());
    }
}
