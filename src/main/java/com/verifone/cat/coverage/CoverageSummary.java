package com.verifone.cat.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A coverage report's numbers: each component's counters, and the change from a baseline. The Python's
 * {@code petrotest.coverage.summary}.
 *
 * <p>Read from the XML {@code jacococli report} writes beside the HTML: the {@code <counter>} elements that are
 * direct children of {@code <report>} are the whole component's totals. The XML names a DTD
 * ({@code report.dtd}); it is never fetched, and the DOCTYPE is allowed (the earlier Java port refused it, which
 * would have failed every real report).
 *
 * <p><b>The baseline</b> ({@link #baselineFor}) is the most recent other run under the results root with a coverage
 * summary for the same test plans, on any build: chosen by the plans, not the build, because a new build is exactly
 * when the comparison is wanted. The summary names the baseline's build.
 *
 * <p>{@code summary.json} has the Python's keys ({@code run_id}, {@code exec_files}, {@code baseline_line}, ...), so
 * either CAT reads the other's summaries as baselines. Numbers are formatted as Python's {@code :.1f} does (the
 * double's exact value rounded half-even), so {@code summary.md} reads the same.
 */
public final class CoverageSummary {

    public static final String SUMMARY_JSON = "summary.json";
    public static final String SUMMARY_MD = "summary.md";

    /** The counters shown, in column order. */
    public static final List<String> SHOWN = List.of("LINE", "BRANCH", "METHOD", "CLASS");

    private static final ObjectMapper JSON = new ObjectMapper();

    public String runId = "";
    public String build = "";
    public String dist = "";
    public List<String> plans = new ArrayList<>();
    public Map<String, ComponentCoverage> components = new LinkedHashMap<>();
    /** Lines a reader needs before trusting the numbers (no sources, a missed JVM, ...). */
    public List<String> notes = new ArrayList<>();
    public String baselineRun = "";
    public String baselineBuild = "";
    /** Component to its line coverage in the baseline, for the delta column. */
    public Map<String, Double> baselineLine = new LinkedHashMap<>();
    /** Component to its line coverage over every run on this build. */
    public Map<String, Double> cumulativeLine = new LinkedHashMap<>();

    public CoverageSummary() {}

    public CoverageSummary(String runId, String build, String dist, List<String> plans) {
        this.runId = runId == null ? "" : runId;
        this.build = build == null ? "" : build;
        this.dist = dist == null ? "" : dist;
        this.plans = new ArrayList<>(plans == null ? List.of() : plans);
    }

    /** The Python's {@code asdict}: snake-case keys, counters as {@code [covered, missed]}. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("build", build);
        out.put("dist", dist);
        out.put("plans", plans);
        Map<String, Object> comps = new LinkedHashMap<>();
        components.forEach((name, c) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", c.name());
            Map<String, List<Long>> counters = new LinkedHashMap<>();
            c.counters().forEach((kind, value) -> counters.put(kind, List.of(value[0], value[1])));
            one.put("counters", counters);
            one.put("exec_files", c.execFiles());
            comps.put(name, one);
        });
        out.put("components", comps);
        out.put("notes", notes);
        out.put("baseline_run", baselineRun);
        out.put("baseline_build", baselineBuild);
        out.put("baseline_line", baselineLine);
        out.put("cumulative_line", cumulativeLine);
        return out;
    }

    /** A summary from its JSON; missing keys take their defaults. */
    public static CoverageSummary fromJson(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("a coverage summary is a JSON object");
        }
        CoverageSummary summary = new CoverageSummary();
        summary.runId = data.path("run_id").asText("");
        summary.build = data.path("build").asText("");
        summary.dist = data.path("dist").asText("");
        data.path("plans").forEach(node -> summary.plans.add(node.asText()));
        data.path("notes").forEach(node -> summary.notes.add(node.asText()));
        summary.baselineRun = data.path("baseline_run").asText("");
        summary.baselineBuild = data.path("baseline_build").asText("");
        readDoubles(data.path("baseline_line"), summary.baselineLine);
        readDoubles(data.path("cumulative_line"), summary.cumulativeLine);
        for (Iterator<Map.Entry<String, JsonNode>> it = data.path("components").fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            Map<String, long[]> counters = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> c = value.path("counters").fields(); c.hasNext();) {
                Map.Entry<String, JsonNode> counter = c.next();
                counters.put(counter.getKey(), new long[] {counter.getValue().path(0).asLong(0),
                        counter.getValue().path(1).asLong(0)});
            }
            List<String> execs = new ArrayList<>();
            value.path("exec_files").forEach(node -> execs.add(node.asText()));
            summary.components.put(entry.getKey(),
                    new ComponentCoverage(value.path("name").asText(entry.getKey()), counters, execs));
        }
        return summary;
    }

    /** The summary at {@code path}, or null when it is missing or not a summary. */
    public static CoverageSummary load(Path path) {
        try {
            return fromJson(JSON.readTree(path.toFile()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes {@code summary.json} and {@code summary.md} into {@code directory}. */
    public void write(Path directory) {
        try {
            Files.writeString(directory.resolve(SUMMARY_JSON),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve(SUMMARY_MD), markdown(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One row per component, sorted: name, the {@link #SHOWN} percentages, the line delta, the build's total. */
    public List<List<String>> rows() {
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, ComponentCoverage> entry : new TreeMap<>(components).entrySet()) {
            String name = entry.getKey();
            ComponentCoverage component = entry.getValue();
            List<String> cells = new ArrayList<>();
            cells.add(name);
            for (String kind : SHOWN) {
                long[] value = component.counter(kind);
                Double pct = component.percent(kind);
                cells.add(pct == null ? "-" : fixed1(pct) + "% (" + value[0] + "/" + (value[0] + value[1]) + ")");
            }
            Double line = component.percent("LINE");
            Double base = baselineLine.get(name);
            cells.add(line == null || base == null ? "-" : signed1(line - base));
            Double cumulative = cumulativeLine.get(name);
            cells.add(cumulative == null ? "-" : fixed1(cumulative) + "%");
            rows.add(List.copyOf(cells));
        }
        return rows;
    }

    public List<String> header() {
        List<String> header = new ArrayList<>();
        header.add("Component");
        for (String kind : SHOWN) {
            header.add(kind.charAt(0) + kind.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        header.add("Line Δ");
        header.add((build.isEmpty() ? "Build" : build) + " total");
        return List.copyOf(header);
    }

    public String markdown() {
        List<String> lines = new ArrayList<>();
        lines.add("# JaCoCo coverage — run " + runId);
        lines.add("");
        lines.add("Build " + (build.isEmpty() ? "unknown" : build) + " (" + dist + "); plans "
                + (plans.isEmpty() ? "none" : String.join(", ", plans)) + ".");
        if (!baselineRun.isEmpty()) {
            lines.add("Line Δ is against run " + baselineRun + " ("
                    + (baselineBuild.isEmpty() ? "unknown build" : baselineBuild) + ").");
        }
        lines.add("");
        List<String> header = header();
        lines.add("| " + String.join(" | ", header) + " |");
        lines.add("|" + "---|".repeat(header.size()));
        for (List<String> row : rows()) {
            lines.add("| " + String.join(" | ", row) + " |");
        }
        if (!notes.isEmpty()) {
            lines.add("");
            lines.add("Notes:");
            notes.forEach(note -> lines.add("- " + note));
        }
        return String.join("\n", lines) + "\n";
    }

    /** {@code {type: [covered, missed]}} for the report as a whole: the {@code <counter>} children of the root. */
    public static Map<String, long[]> readCounters(Path xml) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setValidating(false);
            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // no "[Fatal Error]" on stderr: it throws
            document = builder.parse(xml.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + xml + ": " + e.getMessage(), e);
        }
        Map<String, long[]> counters = new LinkedHashMap<>();
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element counter && "counter".equals(counter.getTagName())) {
                counters.put(counter.getAttribute("type"), new long[] {number(counter.getAttribute("covered")),
                        number(counter.getAttribute("missed"))});
            }
        }
        return counters;
    }

    /** The newest other run's coverage summary for the same plans, or null; see the class comment. */
    public static CoverageSummary baselineFor(Path resultsRoot, String runId, List<String> plans) {
        if (plans == null || plans.isEmpty() || resultsRoot == null || !Files.isDirectory(resultsRoot)) {
            return null;
        }
        List<String> wanted = plans.stream().sorted().toList();
        CoverageSummary best = null;
        FileTime bestTime = null;
        try (DirectoryStream<Path> runs = Files.newDirectoryStream(resultsRoot)) {
            for (Path run : runs) {
                Path path = run.resolve("coverage").resolve(SUMMARY_JSON);
                if (run.getFileName().toString().equals(runId) || !Files.isRegularFile(path)) {
                    continue;
                }
                CoverageSummary summary = load(path);
                if (summary == null || !summary.plans.stream().sorted().toList().equals(wanted)
                        || summary.components.isEmpty()) {
                    continue;
                }
                FileTime time = Files.getLastModifiedTime(path);
                if (bestTime == null || time.compareTo(bestTime) > 0) {
                    best = summary;
                    bestTime = time;
                }
            }
        } catch (IOException e) {
            return best;
        }
        return best;
    }

    /** Python's {@code f"{x:.1f}"}: the double's exact value, rounded half-even. */
    static String fixed1(double value) {
        String text = new BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString();
        return negativeZero(value, text);
    }

    /** Python's {@code f"{x:+.1f}"}. */
    static String signed1(double value) {
        String text = fixed1(value);
        return text.startsWith("-") ? text : "+" + text;
    }

    private static String negativeZero(double value, String text) {
        // Python keeps the sign of a value that rounds to zero: f"{-0.04:.1f}" is "-0.0".
        if (Math.copySign(1.0, value) < 0 && !text.startsWith("-")) {
            return "-" + text;
        }
        return text;
    }

    private static long number(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Long.parseLong(text.strip());
    }

    private static void readDoubles(JsonNode node, Map<String, Double> into) {
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isNumber()) {
                into.put(entry.getKey(), entry.getValue().asDouble());
            }
        }
    }
}
