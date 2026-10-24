package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.report.ReportPaths;

/**
 * Writes run folders the way the CAT writers do, one JSON object per line, for tests that
 * need a run on disk. Every test runs against its own {@code @TempDir}.
 */
public final class RunFixtures {

    /** The default event time: every fixture run starts here. */
    public static final String T0 = "2026-10-20T09:00:00Z";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunFixtures() {}

    /** One event: its type and its fields, as {@code name, value} pairs. */
    public static Map<String, Object> event(String type, Object... fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        for (int i = 0; i < fields.length; i += 2) {
            out.put((String) fields[i], fields[i + 1]);
        }
        out.putIfAbsent("at", T0);
        return out;
    }

    /** A run.started as the launcher sends it. */
    public static Map<String, Object> started(String rig, boolean fakePos, String gitHead,
            List<String> plans, List<String> names) {
        return event("run.started", "run_id", "r", "rig", rig, "suite", "tests/suites/charity",
                "cmd", "pytest tests/suites/charity", "workers", 1, "started_at", T0,
                "cat_version", "0", "fake_pos", fakePos, "git_head", gitHead,
                "test_plans", plans, "plan_names", names);
    }

    /** A run.finished with its counts. */
    public static Map<String, Object> finished(String outcome, int passed, int failed, int warned,
            boolean cancelled, double elapsed, List<String> warnedNodeids) {
        return event("run.finished", "run_id", "r", "outcome", outcome, "passed", passed, "failed", failed,
                "warned", warned, "warned_nodeids", warnedNodeids, "cancelled", cancelled,
                "elapsed", elapsed, "dropped_events", 0);
    }

    /** A test.finished. */
    public static Map<String, Object> test(String nodeid, String outcome, double duration, String excType) {
        return event("test.finished", "nodeid", nodeid, "worker", "session", "outcome", outcome,
                "duration", duration, "warnings", 0, "exc_type", excType);
    }

    /** A test.started. */
    public static Map<String, Object> testStarted(String nodeid) {
        return event("test.started", "nodeid", nodeid, "worker", "session");
    }

    /** A step.finished. */
    public static Map<String, Object> stepFinished(String step, String state, double elapsed) {
        return event("step.finished", "step", step, "state", state, "elapsed", elapsed, "detail", "");
    }

    /** A step.entered. */
    public static Map<String, Object> stepEntered(String step) {
        return event("step.entered", "step", step);
    }

    /** A plan.resolved. A case with a null node has no migrated test file. */
    public static Map<String, Object> planResolved(Object... keyNodeSummary) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int i = 0; i < keyNodeSummary.length; i += 3) {
            Map<String, Object> planned = new HashMap<>();
            planned.put("key", keyNodeSummary[i]);
            planned.put("node", keyNodeSummary[i + 1]);
            planned.put("summary", keyNodeSummary[i + 2]);
            cases.add(planned);
        }
        return event("plan.resolved", "cases", cases);
    }

    /** A log.line. */
    public static Map<String, Object> logLine(String message) {
        return event("log.line", "level", "INFO", "logger", "cat.test", "message", message);
    }

    /**
     * Writes {@code events} as {@code <root>/<runId>/events.ndjson}, adding the envelope the
     * writers add (version, worker, sequence number).
     *
     * @return the run folder
     */
    public static Path writeRun(Path root, String runId, List<Map<String, Object>> events) throws IOException {
        Path dir = Files.createDirectories(root.resolve(runId));
        StringBuilder text = new StringBuilder();
        int seq = 0;
        for (Map<String, Object> event : events) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("v", 1);
            line.put("worker", "controller");
            line.put("seq", seq++);
            line.putAll(event);
            text.append(MAPPER.writeValueAsString(line)).append('\n');
        }
        Files.writeString(dir.resolve(ReportPaths.EVENTS_FILE), text.toString());
        return dir;
    }
}
