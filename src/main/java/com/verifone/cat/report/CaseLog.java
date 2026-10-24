package com.verifone.cat.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Renders a case's {@code steps.log}: the case, its verdict and its time, then one line per
 * step with PASS, FAIL or NOT RUN and how long it took, the failure under the step that
 * failed, the validations, the files beside it, and the traceback.
 *
 * <p>The Python CAT's {@code petrotest.report.case_record.render}. The rig owner asked for a
 * log a person can read, not a log of JSON lines, so the layout is kept exactly: the column
 * widths and the blank lines are what the operator reads. A test change that moves them
 * changes what the operator sees.
 *
 * <p>Times are shown in {@code zone}, as the Python shows local time. Callers pass the
 * machine's zone; tests pass a fixed one.
 */
public final class CaseLog {

    private static final String DASH = "—";

    private static final Map<String, String> WORDS = Map.of(
            "passed", "PASS",
            "failed", "FAIL",
            "warned", "WARN",
            "skipped", "NOT RUN",
            "running", "RUNNING");

    /** A verdict as it reads in {@code steps.log}, by pytest's outcome. */
    private static final Map<String, String> VERDICTS = Map.of(
            "passed", "PASSED",
            "failed", "FAILED",
            "skipped", "SKIPPED",
            "error", "ERROR (did not run: setup failed)",
            "xfailed", "EXPECTED FAILURE (xfail)",
            "running", "DID NOT FINISH");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private CaseLog() {}

    /** {@code steps.log}'s text for {@code caseRecord}. */
    public static String render(CaseRecord caseRecord, ZoneId zone) throws IOException {
        List<String> lines = new ArrayList<>();
        String outcome = caseRecord.outcome();
        Instant finished = caseRecord.finishedAt() != null ? caseRecord.finishedAt() : Instant.now();
        double took = Duration.between(caseRecord.startedAt(), finished).toMillis() / 1000.0;

        String title = caseRecord.title().isEmpty() ? "" : " " + DASH + " " + caseRecord.title();
        lines.add(caseRecord.key() + title);
        lines.add("Result:   " + VERDICTS.getOrDefault(outcome, outcome.toUpperCase(Locale.ROOT)));
        lines.add("Started:  " + STAMP.format(caseRecord.startedAt().atZone(zone)));
        lines.add("Finished: " + STAMP.format(finished.atZone(zone)) + "  (" + seconds(took) + ")");
        lines.add("Test:     " + caseRecord.nodeid());
        lines.add("");

        List<CaseRecord.StepRecord> steps = caseRecord.steps();
        if (!steps.isEmpty()) {
            lines.add("Steps (" + steps.size() + "):");
            for (CaseRecord.StepRecord step : steps) {
                lines.add(stepLine(step, zone));
                for (String detailLine : step.detail().lines().toList()) {
                    lines.add(" ".repeat(24) + detailLine);
                }
            }
            lines.add("");
        } else {
            lines.add("(no .feature steps: this test is not a scenario)");
            lines.add("");
        }

        List<CaseRecord.ValidationRecord> checks = caseRecord.validations();
        if (!checks.isEmpty()) {
            lines.add("Validations (" + checks.size() + "):");
            for (CaseRecord.ValidationRecord check : checks) {
                String word = check.passed() ? "PASS" : "FAIL";
                lines.add(String.format(Locale.ROOT, "  %-4s  %-8s  %s", word, check.kind(), check.name()));
            }
            lines.add("");
        }

        if (!caseRecord.detail().isEmpty() && !"passed".equals(outcome)) {
            lines.add("Why:");
            caseRecord.detail().lines().forEach(line -> lines.add("  " + line));
            lines.add("");
        }

        List<String> files = filesBeside(caseRecord.folder());
        if (!files.isEmpty()) {
            Map<String, String> described = new LinkedHashMap<>();
            caseRecord.captures().forEach(c -> described.put(c.fileName(), c.what()));
            int width = files.stream().mapToInt(String::length).max().orElse(0);
            lines.add("In this folder:");
            for (String name : files) {
                String what = described.getOrDefault(name, describe(name));
                lines.add(String.format(Locale.ROOT, "  %-" + width + "s", name)
                        + (what.isEmpty() ? "" : "  " + what));
            }
            lines.add("");
        }

        if (!caseRecord.traceback().isEmpty() && !"passed".equals(outcome)) {
            lines.add("Traceback (for whoever debugs it):");
            caseRecord.traceback().lines().forEach(line -> lines.add("  " + line));
            lines.add("");
        }
        return String.join("\n", lines);
    }

    /** One step's line. A skipped step has no time, since it never ran. */
    static String stepLine(CaseRecord.StepRecord step, ZoneId zone) {
        String word = WORDS.getOrDefault(step.outcome(), step.outcome().toUpperCase(Locale.ROOT));
        if ("skipped".equals(step.outcome())) {
            return String.format(Locale.ROOT, "  %3d. %-7s %7s  %8s  %s",
                    step.index(), word, "", "", step.label());
        }
        String spent = "running".equals(step.outcome()) ? "" : seconds(step.duration());
        return String.format(Locale.ROOT, "  %3d. %-7s %7s  %s  %s",
                step.index(), word, spent, CLOCK.format(step.startedAt().atZone(zone)), step.label());
    }

    /**
     * How long a step or case took, as {@code 4.2s} under a minute and {@code 3m 07s} over.
     */
    static String seconds(double value) {
        if (value < 60) {
            return String.format(Locale.ROOT, "%.1fs", value);
        }
        long total = (long) value;
        return String.format(Locale.ROOT, "%dm %02ds", total / 60, total % 60);
    }

    /** What a file another writer left in the folder is, by its name. Empty when it is not known. */
    static String describe(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        if (lowered.endsWith(".png")) {
            return "screenshot of the POS when it failed";
        }
        if (lowered.endsWith("-dom.html")) {
            return "the POS page's DOM when it failed";
        }
        if (lowered.endsWith("-trace.zip")) {
            return "Playwright trace (open with `playwright show-trace`)";
        }
        if ("capture.json".equals(lowered)) {
            return "what recovery captured before it recovered";
        }
        return "";
    }

    /** The folder's file names, sorted, without {@code steps.log}; empty when the folder is absent. */
    private static List<String> filesBeside(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals(CaseRecord.STEPS_LOG))
                    .sorted()
                    .toList();
        }
    }
}
