package com.verifone.cat.runservice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.report.ReportPaths;

/**
 * One run's progress, folded live from its {@code events.ndjson} into a {@link RunState} by a
 * {@link RunStateReducer}: the Python CAT's listener over the run's event stream. A refresh reads only the bytes
 * added since the last one, and folds only whole lines, so a line the launcher is still writing waits for the next
 * refresh. A file replaced under it (shorter than what was read) is folded again from the start.
 *
 * <p>The counters are the Python Overview's ({@code verdict_counts}): case units when a plan resolved, test units
 * when none did, never a mixture; Not Run and Percent Completed count plan cases ({@code percent_complete}).
 *
 * <p>Not thread-safe: the owning {@link RunService} calls it under its lock.
 */
public final class RunProgress {

    /** The most failing tests the Overview lists, as the Python CAT's {@code failing[:25]}. */
    static final int FAILURE_LINES = 25;

    /** A test's statuses the Failures card lists: failed, error, timeout and warned. */
    static final List<String> LISTED = List.of("failed", "error", "timeout", "warned");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int READ_CHUNK = 1 << 20;

    /** A test that failed or warned: its node id, its case label, its status and its reason. */
    public record FailureLine(String nodeid, String label, String outcome, String detail) {}

    /** What the Overview draws, from one read of the state. */
    public record Snapshot(RunState state) {

        public boolean started() {
            return !"not_started".equals(state.status());
        }

        public String runId() {
            return state.runId();
        }

        public List<String> testPlans() {
            return state.testPlans();
        }

        /** Whether {@code run.finished} has been read. */
        public boolean finished() {
            return "complete".equals(state.status()) || "cancelled".equals(state.status());
        }

        public boolean cancelled() {
            return "cancelled".equals(state.status());
        }

        /** The run's outcome word, "" before {@code run.finished}. */
        public String outcome() {
            return state.outcome() == null ? "" : state.outcome();
        }

        public int passed() {
            return RunView.verdictCounts(state).get("passed");
        }

        public int failed() {
            return RunView.verdictCounts(state).get("failed");
        }

        public int warned() {
            return RunView.verdictCounts(state).get("warned");
        }

        public int notRun() {
            return state.countNotRun();
        }

        /** Percent Completed: finished cases over runnable ones. */
        public double percent() {
            return state.percentComplete();
        }

        /** The Run Time: the recorded elapsed once over, else since it started. */
        public double seconds(Instant now) {
            return RunView.runSeconds(state, now);
        }

        /** The seven steps' rows. */
        public List<RunView.StepRow> steps() {
            return RunView.stepRows(state);
        }

        /** The step running now, or "" when none is. */
        public String currentStep() {
            for (RunState.StepState step : state.steps()) {
                if ("running".equals(step.status()) || "repairing".equals(step.status())) {
                    return step.name();
                }
            }
            return "";
        }

        /** The failing and warned tests, at most {@link #FAILURE_LINES}, each with its case label. */
        public List<FailureLine> failures() {
            List<RunState.TestState> failing = failing();
            List<RunState.TestState> shown = failing.subList(0, Math.min(FAILURE_LINES, failing.size()));
            Map<String, RunView.NodeLabel> labels = RunView.stateLabels(state.cases(),
                    shown.stream().map(RunState.TestState::nodeid).toList());
            List<FailureLine> out = new ArrayList<>();
            for (RunState.TestState test : shown) {
                RunView.NodeLabel label = labels.get(test.nodeid());
                String reason = RunView.reason(test.excType(), test.detail());
                out.add(new FailureLine(test.nodeid(), label == null ? test.nodeid() : label.text(), test.status(),
                        reason.length() > 100 ? reason.substring(0, 100) : reason));
            }
            return out;
        }

        /** How many tests failed or warned in all. */
        public int failureCount() {
            return failing().size();
        }

        private List<RunState.TestState> failing() {
            return state.tests().values().stream().filter(t -> LISTED.contains(t.status())).toList();
        }
    }

    private final Path file;
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream();
    private RunStateReducer reducer = new RunStateReducer();
    private long offset;

    public RunProgress(Path folder) {
        this.file = folder.resolve(ReportPaths.EVENTS_FILE);
    }

    /** Folds whatever has been written since the last refresh. A missing file folds nothing yet. */
    public void refresh() throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < offset) {
                // The file was replaced under us: start again rather than fold from the middle.
                offset = 0;
                carry.reset();
                reducer = new RunStateReducer();
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

    private void fold(String line) {
        if (line.isBlank()) {
            return;
        }
        try {
            reducer.apply(MAPPER.readTree(line.strip()));
        } catch (JsonProcessingException e) {
            // A line that is not JSON is skipped, as the Python reader skips it.
        }
    }

    /** The state folded so far. */
    public RunState state() {
        return reducer.state();
    }

    /** The Overview's read of the folded state. */
    public Snapshot snapshot() {
        return new Snapshot(reducer.state());
    }
}
