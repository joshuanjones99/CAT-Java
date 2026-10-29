package com.verifone.cat.xray;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.atlassian.AtlassianUnavailable;
import com.verifone.cat.coverage.CoverageSummary;

/**
 * {@code cat-java xray-attach}: attach a run's coverage bundle to its Jira ticket(s). The Python's
 * {@code python -m cat.xray.attach}.
 *
 * <p>Started detached by Generate Report ({@code launch.CoverageLaunch}) once the coverage bundle is written, as the
 * Xray sidecar is: a slow or down Jira can neither hold the run up nor fail it. It uploads the file to each issue
 * named, adds one comment with the coverage table, and writes the outcome to {@code coverage-attach.json} in the run
 * directory (its output goes to {@code coverage-attach.log}). It exits 0 whatever Jira did: the outcome is in the
 * status file, and a reporting failure is not a run failure.
 *
 * <p><b>Which ticket</b> is the caller's choice ({@code CoverageLaunch.coverageIssues}): the run's Xray Test
 * Execution(s) when the run reported to Xray, else the test plan(s) it ran. Credentials are the file the Xray sidecar
 * reads ({@link AtlassianCredentials#defaultPath}), Jira's half only.
 *
 * <p>Deviation: the timestamps in the status file end {@code +00:00} as the Python's {@code isoformat()} does, but
 * carry Java's precision.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests run against a loopback fake, never the live site. The Python's was sent
 * live on 2026-10-13 (PETROCORE-143011, PETROCORE-143013).
 */
public final class Attach {

    public static final String STATUS_FILE = "coverage-attach.json";
    public static final String LOG_FILE = "coverage-attach.log";

    static final String LOGGER = "cat.xray.attach";

    static final String USAGE = String.join("\n",
            "usage: cat-java xray-attach --run-dir <dir> --file <bundle> --summary <summary.json> --issue <key>...",
            "",
            "attach a run's coverage bundle to its Jira ticket(s)",
            "  --run-dir   the run's directory under the results root",
            "  --file      the bundle to attach",
            "  --summary   the coverage summary.json",
            "  --issue     a Jira key; repeatable");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx");

    /** Attaches a bundle to issues: issue to {@code "ok"} or the error. The seam {@link #main} calls. */
    @FunctionalInterface
    public interface Attacher {
        Map<String, String> attach(List<String> issues, Path bundle, Path summary);
    }

    private final Attacher attacher;
    private final Consumer<String> log;

    /** The production command: the default credentials file, the real site. */
    public Attach() {
        this(Attach::attachWithDefaultCredentials, line -> System.out.println(line));
    }

    public Attach(Attacher attacher, Consumer<String> log) {
        this.attacher = attacher;
        this.log = log;
    }

    /** Main's entry point: the arguments after {@code xray-attach}. */
    public static int run(String[] argv) {
        return new Attach().main(List.of(argv), System.err);
    }

    /** The comment: one line naming the bundle, then the summary's table (and its notes). */
    public static List<Adf.Block> commentBlocks(Path summaryPath) {
        CoverageSummary summary = CoverageSummary.load(summaryPath);
        if (summary == null) {
            return List.of(Adf.Paragraph.of(Adf.Span.plain(
                    "JaCoCo coverage attached; its summary could not be read.")));
        }
        String lead = "JaCoCo coverage for CAT run " + summary.runId + " on "
                + (summary.build.isEmpty() ? "an unknown build" : summary.build) + " (" + summary.dist + ") is attached.";
        if (!summary.baselineRun.isEmpty()) {
            lead += " Line Δ is against run " + summary.baselineRun + " (" + summary.baselineBuild + ").";
        }
        List<Adf.Block> blocks = new ArrayList<>();
        blocks.add(Adf.Paragraph.of(Adf.Span.plain(lead)));
        blocks.add(new Adf.Table(summary.header(), summary.rows()));
        if (!summary.notes.isEmpty()) {
            blocks.add(Adf.Paragraph.of(Adf.Span.plain("Notes: " + String.join("; ", summary.notes))));
        }
        return blocks;
    }

    /**
     * Attaches {@code bundle} and comments on each issue; issue to {@code "ok"} or the error. One issue's refusal
     * never stops the others.
     */
    public static Map<String, String> attach(List<String> issues, Path bundle, Path summaryPath, XrayClient client,
            AttachUpload upload, Consumer<String> log) {
        Map<String, String> outcome = new LinkedHashMap<>();
        JsonNode body = Adf.toAdf(commentBlocks(summaryPath));
        for (String issue : issues) {
            try {
                client.attachFile(issue, bundle, upload);
                client.addComment(issue, body);
                outcome.put(issue, "ok");
                log.accept(line("INFO", issue + ": attached " + bundle.getFileName()));
            } catch (AtlassianUnavailable e) {
                outcome.put(issue, e.getMessage());
                log.accept(line("ERROR", issue + ": " + e.getMessage()));
            }
        }
        return outcome;
    }

    /** {@link #attach} with the default credentials file and the real site; a missing file is each issue's error. */
    static Map<String, String> attachWithDefaultCredentials(List<String> issues, Path bundle, Path summary) {
        Path file = AtlassianCredentials.defaultPath();
        AtlassianCredentials.Jira jira;
        try {
            jira = AtlassianCredentials.loadJira(file);
        } catch (AtlassianUnavailable e) {
            Map<String, String> outcome = new LinkedHashMap<>();
            issues.forEach(issue -> outcome.put(issue, e.getMessage()));
            return outcome;
        }
        // Only Jira is written to; the Xray half of the client is never used.
        AtlassianClient atlassian = new AtlassianClient(AtlassianClient.SITE, AtlassianClient.XRAY_API, () -> jira,
                () -> {
                    throw new AtlassianUnavailable("the coverage upload never calls Xray");
                }, Duration.ofSeconds(30));
        XrayClient client = new XrayClient(atlassian, Duration.ofSeconds(20), null);
        AttachUpload upload = new AttachUpload(AtlassianClient.SITE, () -> jira, AttachUpload.DEFAULT_TIMEOUT);
        return attach(issues, bundle, summary, client, upload, line -> System.out.println(line));
    }

    /** Parses the arguments, attaches, writes {@code coverage-attach.json}; 0 whatever Jira did, 2 on a usage error. */
    public int main(List<String> argv, PrintStream err) {
        String runDir = null;
        String file = null;
        String summary = null;
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < argv.size(); i++) {
            String flag = argv.get(i);
            if ("--help".equals(flag) || "-h".equals(flag)) {
                err.println(USAGE);
                return 0;
            }
            if (!List.of("--run-dir", "--file", "--summary", "--issue").contains(flag) || i + 1 >= argv.size()) {
                err.println("xray-attach: bad argument " + flag);
                err.println(USAGE);
                return 2;
            }
            String value = argv.get(++i);
            switch (flag) {
                case "--run-dir" -> runDir = value;
                case "--file" -> file = value;
                case "--summary" -> summary = value;
                default -> issues.add(value);
            }
        }
        if (runDir == null || file == null || summary == null || issues.isEmpty()) {
            err.println("xray-attach: --run-dir, --file, --summary and --issue are required");
            err.println(USAGE);
            return 2;
        }
        Path bundle = Path.of(file);
        ObjectNode status = JSON.createObjectNode();
        status.put("file", bundle.toString());
        status.put("started_at", now());
        ObjectNode outcome = status.putObject("issues");
        if (!Files.isRegularFile(bundle)) {
            issues.forEach(issue -> outcome.put(issue, "no file at " + bundle));
        } else {
            attacher.attach(issues, bundle, Path.of(summary)).forEach(outcome::put);
        }
        status.put("finished_at", now());
        try {
            Path dir = Path.of(runDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(STATUS_FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(status),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.accept(line("ERROR", "could not write " + STATUS_FILE + ": " + e.getMessage()));
        }
        // 0 either way: the outcome is in the status file, and a reporting failure is not a run failure.
        return 0;
    }

    /** {@code coverage-attach.json} for a run, or null when the upload never wrote one. */
    public static JsonNode readStatus(Path runDir) {
        try {
            JsonNode node = JSON.readTree(runDir.resolve(STATUS_FILE).toFile());
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO);
    }

    /** One log line in the Python's {@code %(asctime)s %(levelname)s %(name)s: %(message)s} shape. */
    private static String line(String level, String message) {
        return OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")) + " " + level + " "
                + LOGGER + ": " + message;
    }
}
