package com.verifone.cat.runservice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.launch.LaunchStepNames;
import com.verifone.cat.launch.StepResult;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.RunFacts;

/**
 * One run's progress, folded from its {@code events.ndjson}. A refresh reads only the bytes added
 * since the last one, and folds only whole lines, so a line the launcher is still writing waits for
 * the next refresh.
 *
 * <p>The counters follow the Python CAT's Overview: passed, failed (failed, error or timeout),
 * passed with warnings, and not run (the planned cases with no node). A finished run's own
 * {@code run.finished} counts win over the tally.
 *
 * <p>Not thread-safe: the owning {@link RunService} calls it under its lock.
 */
public final class RunProgress {

    /** A step that has been entered and not yet finished. */
    static final String RUNNING = "running";

    /** The most failing tests the Overview lists, as the Python CAT's {@code failing[:25]}. */
    static final int FAILURE_LINES = 25;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int READ_CHUNK = 1 << 20;

    /** A step's line in the Overview: its name, state ({@code pending}, {@code running}, or the step's own) and detail. */
    public record StepLine(String name, String state, String detail) {}

    /** A test that failed or warned. */
    public record FailureLine(String nodeid, String outcome, String detail) {}

    /** What the Overview draws, as one read. */
    public record Snapshot(
            boolean started,
            String runId,
            String rig,
            List<String> testPlans,
            Instant startedAt,
            boolean finished,
            String outcome,
            boolean cancelled,
            double elapsed,
            int passed,
            int failed,
            int warned,
            int notRun,
            List<StepLine> steps,
            String currentStep,
            int stepDone,
            int stepTotal,
            List<FailureLine> failures,
            int failureCount) {

        /** The run time in seconds, from the run's own clock once it has finished, else from {@code now}. */
        public double seconds(Instant now) {
            if (finished) {
                return elapsed;
            }
            if (startedAt == null) {
                return 0;
            }
            return Math.max(0, (now.toEpochMilli() - startedAt.toEpochMilli()) / 1000.0);
        }

        /** Percent complete: 100 once the run has finished, else the current step's own progress. */
        public double percent() {
            if (finished) {
                return 100;
            }
            return stepTotal > 0 ? Math.min(100.0, 100.0 * stepDone / stepTotal) : 0;
        }
    }

    private final Path file;
    private RunFacts facts = new RunFacts();
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream();
    private final Map<String, String[]> steps = new LinkedHashMap<>();
    private final Map<String, int[]> stepProgress = new LinkedHashMap<>();
    private final Map<String, FailureLine> failures = new LinkedHashMap<>();
    private final String folderName;
    private long offset;
    private boolean started;
    private Instant startedAt;
    private boolean finished;
    private String outcome = "";
    private boolean cancelled;
    private double elapsed;
    private int passed;
    private int failed;
    private int warned;
    private int finishedPassed;
    private int finishedFailed;
    private int finishedWarned;
    private boolean finishedCounts;
    private String currentStep = "";

    public RunProgress(Path folder) {
        this.file = folder.resolve(ReportPaths.EVENTS_FILE);
        this.folderName = folder.getFileName() == null ? "" : folder.getFileName().toString();
    }

    /** Folds whatever has been written since the last refresh. A missing file folds nothing yet. */
    public void refresh() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < offset) {
                // The file was replaced under us: start again rather than fold from the middle.
                reset();
            }
            raf.seek(offset);
            byte[] buf = new byte[READ_CHUNK];
            int n;
            while ((n = raf.read(buf)) != -1) {
                offset += n;
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        carry.write(buf, from, i - from);
                        fold(carry.toString(StandardCharsets.UTF_8));
                        carry.reset();
                        from = i + 1;
                    }
                }
                carry.write(buf, from, n - from);
            }
        }
    }

    private void reset() {
        offset = 0;
        carry.reset();
        steps.clear();
        stepProgress.clear();
        failures.clear();
        started = false;
        startedAt = null;
        finished = false;
        outcome = "";
        cancelled = false;
        elapsed = 0;
        passed = 0;
        failed = 0;
        warned = 0;
        finishedCounts = false;
        currentStep = "";
        facts = new RunFacts();
    }

    private void fold(String line) {
        if (line.isBlank()) {
            return;
        }
        JsonNode event;
        try {
            event = MAPPER.readTree(line.strip());
        } catch (JsonProcessingException e) {
            return;
        }
        if (event == null || !event.isObject()) {
            return;
        }
        if (facts.fold(event)) {
            if ("run.started".equals(text(event, "type"))) {
                started = true;
                startedAt = instant(text(event, "at"));
            }
            return;
        }
        switch (orEmpty(text(event, "type"))) {
            case "step.entered" -> {
                String name = text(event, "step");
                steps.put(name, new String[] {RUNNING, ""});
                currentStep = name;
            }
            case "step.progress" -> {
                String name = text(event, "step");
                JsonNode done = event.get("done");
                JsonNode total = event.get("total");
                stepProgress.put(name, new int[] {intOf(done), intOf(total)});
            }
            case "step.finished" -> {
                String name = text(event, "step");
                steps.put(name, new String[] {text(event, "state"), orEmpty(text(event, "detail"))});
                if (name != null && name.equals(currentStep)) {
                    currentStep = "";
                }
            }
            case "test.finished" -> foldTest(event);
            case "run.finished" -> {
                finished = true;
                outcome = orEmpty(text(event, "outcome"));
                cancelled = event.path("cancelled").asBoolean(false);
                elapsed = event.path("elapsed").asDouble(0);
                finishedPassed = intOf(event.get("passed"));
                finishedFailed = intOf(event.get("failed"));
                finishedWarned = intOf(event.get("warned"));
                finishedCounts = true;
            }
            default -> {
                // Other events do not change the Overview.
            }
        }
    }

    private void foldTest(JsonNode event) {
        String nodeid = orEmpty(text(event, "nodeid"));
        String result = orEmpty(text(event, "outcome"));
        int warnings = intOf(event.get("warnings"));
        boolean bad = "failed".equals(result) || "error".equals(result) || "timeout".equals(result);
        if (bad) {
            failed++;
        } else if (warnings > 0) {
            warned++;
        } else if ("passed".equals(result)) {
            passed++;
        }
        if (bad || warnings > 0) {
            String detail = orEmpty(text(event, "detail"));
            int cut = detail.indexOf('\n');
            String first = cut < 0 ? detail : detail.substring(0, cut);
            failures.put(nodeid, new FailureLine(nodeid, result, first));
        }
    }

    /** The Overview's read of the folded state, with {@code notRun} from the planned cases. */
    public Snapshot snapshot() {
        List<StepLine> lines = new ArrayList<>();
        for (String name : LaunchStepNames.ALL) {
            String[] state = steps.get(name);
            lines.add(state == null
                    ? new StepLine(name, StepResult.PENDING, "")
                    : new StepLine(name, state[0], state[1]));
        }
        int[] progress = stepProgress.getOrDefault(currentStep, new int[] {0, 0});
        List<FailureLine> listed = new ArrayList<>(failures.values());
        boolean counts = finishedCounts;
        String runId = facts.runId() == null || facts.runId().isEmpty() ? folderName : facts.runId();
        return new Snapshot(
                started,
                runId,
                orEmpty(facts.rig()),
                facts.testPlans(),
                startedAt,
                finished,
                outcome,
                cancelled,
                elapsed,
                counts ? finishedPassed : passed,
                counts ? finishedFailed : failed,
                counts ? finishedWarned : warned,
                facts.notRunCases().size(),
                lines,
                currentStep,
                progress[0],
                progress[1],
                List.copyOf(listed.subList(0, Math.min(FAILURE_LINES, listed.size()))),
                listed.size());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static int intOf(JsonNode node) {
        return node == null || !node.isNumber() ? 0 : node.asInt();
    }

    private static Instant instant(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
