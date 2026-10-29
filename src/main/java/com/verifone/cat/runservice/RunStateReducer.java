package com.verifone.cat.runservice;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Folds a run's events, in order, into a {@link RunState}: the Python CAT's {@code cat.state.reducer}
 * ({@code apply_event} and its {@code _apply_*} functions) with its {@code EventReducer}.
 *
 * <p>The rules are the Python's, each kept on purpose:
 * <ul>
 *   <li>Events are not gated on {@code run.started}: a worker's event can be read before the controller's.</li>
 *   <li>Two {@code run.started} arrive per run. The plans, plan names and upgrade mode are additive (a defaulted
 *       value never overwrites a known one), the suite keeps the first unless the event is {@code restated}, a
 *       plugin's {@code unknown} rig never overwrites a known one, the start time is the first, and
 *       {@code fake_pos} (older streams: {@code pos_double}) is sticky.</li>
 *   <li>{@code run.finished}'s counts are authoritative and its {@code warned_nodeids} move those tests to
 *       {@code warned}; a {@code test.finished} that arrives after it gets the finish applied again
 *       ({@code EventReducer}), so it is neither counted twice nor left unwarned.</li>
 *   <li>A step entered again starts its checklist afresh; a {@code running} checklist row is replaced by the next
 *       one with its label, a settled one is not (a sub-task run twice keeps both rows).</li>
 *   <li>A test started again reports its steps afresh and drops its earlier validations.</li>
 *   <li>A log line goes to the run-wide buffer and to its source's own: a {@code sim.} logger's, else its step's.
 *       Each holds {@link RunState#LOG_BUFFER_LINES}.</li>
 *   <li>An event that will not apply is dropped; it never breaks the fold.</li>
 * </ul>
 *
 * <p>Not thread-safe: the owner folds and reads under its own lock. {@link #state} is cached until the next event.
 *
 * <p>Deviation: events are read as the NDJSON lines' JSON, not as typed models, so a line from a newer CAT with
 * fields this does not know still folds what it can.
 */
public final class RunStateReducer {

    private final boolean includeLogs;

    private String runId = "";
    private String rig = "";
    private String suite = "";
    private String cmd = "";
    private int workers;
    private Instant startedAt;
    private String catVersion = "";
    private String upgradeMode = "skip";
    private boolean fakePos;
    private String gitHead = "";
    private String status = "not_started";
    private List<String> testPlans = List.of();
    private List<String> planNames = List.of();
    private final Map<String, RunState.CaseState> cases = new LinkedHashMap<>();
    private final List<RunState.StepState> steps = new ArrayList<>();
    private final Map<String, RunState.TestState> tests = new LinkedHashMap<>();
    private Integer testCountTotal;
    private int passed;
    private int failed;
    private int warned;
    private final List<RunState.SimulatorState> simulators = new ArrayList<>();
    private final List<RunState.ConnectionState> connections = new ArrayList<>();
    private final Deque<RunState.LogLine> logLines = new ArrayDeque<>();
    private final Map<String, Deque<RunState.LogLine>> sourceLogLines = new LinkedHashMap<>();
    private double elapsed;
    private String outcome;
    private final List<RunState.Recovery> recoveries = new ArrayList<>();
    private final List<RunState.Validation> validations = new ArrayList<>();

    private JsonNode finishedEvent;
    private RunState cached;

    /** A reducer that keeps log lines. */
    public RunStateReducer() {
        this(true);
    }

    /**
     * @param includeLogs whether {@code log.line} events are kept; a summary skips them, as the Python's
     *                    {@code replay(include_logs=False)} does (they are most of a real run's lines)
     */
    public RunStateReducer(boolean includeLogs) {
        this.includeLogs = includeLogs;
        for (String name : RunState.STEP_NAMES) {
            steps.add(RunState.StepState.pending(name));
        }
    }

    /** Applies one event (one NDJSON line's object). Returns whether anything may have changed. */
    public boolean apply(JsonNode event) {
        if (event == null || !event.isObject()) {
            return false;
        }
        String type = text(event, "type");
        if (type == null) {
            return false;
        }
        if (!includeLogs && "log.line".equals(type)) {
            return false;
        }
        cached = null;
        try {
            applyOne(type, event);
        } catch (RuntimeException e) {
            return false; // the Python logs and keeps the state as it was
        }
        if ("run.started".equals(type)) {
            finishedEvent = null;
        } else if ("run.finished".equals(type)) {
            finishedEvent = event;
        } else if (finishedEvent != null && "test.finished".equals(type)) {
            // A late row still takes the finish's warnings, and does not count twice.
            applyRunFinished(finishedEvent);
        }
        return true;
    }

    /** The state as folded so far. */
    public RunState state() {
        if (cached == null) {
            Map<String, List<RunState.LogLine>> sources = new LinkedHashMap<>();
            sourceLogLines.forEach((key, lines) -> sources.put(key, new ArrayList<>(lines)));
            cached = new RunState(runId, rig, suite, cmd, workers, startedAt, catVersion, upgradeMode, fakePos,
                    gitHead, status, testPlans, planNames, cases, steps, tests, testCountTotal, passed, failed,
                    warned, simulators, connections, new ArrayList<>(logLines), sources, elapsed, outcome,
                    recoveries, validations);
        }
        return cached;
    }

    private void applyOne(String type, JsonNode event) {
        switch (type) {
            case "run.started" -> applyRunStarted(event);
            case "run.manifest" -> applyRunManifest(event);
            case "run.finished" -> applyRunFinished(event);
            case "step.entered" -> updateStep(text(event, "step"), step -> new RunState.StepState(step.name(),
                    "running", step.progressDone(), step.progressTotal(), step.progressDetail(), step.elapsed(),
                    step.detail(), List.of()));
            case "step.progress" -> updateStep(text(event, "step"), step -> new RunState.StepState(step.name(),
                    step.status(), intOf(event, "done"), integerOf(event, "total"), orEmpty(text(event, "detail")),
                    step.elapsed(), step.detail(), step.checks()));
            case "step.finished" -> applyStepFinished(event);
            case "step.check" -> applyStepCheck(event);
            case "test.step" -> applyTestStep(event);
            case "test.validation" -> validations.add(new RunState.Validation(orEmpty(text(event, "nodeid")),
                    orEmpty(text(event, "case")), orEmpty(text(event, "kind")), orEmpty(text(event, "name")),
                    event.path("passed").asBoolean(false), orEmpty(text(event, "diff")),
                    orEmpty(text(event, "expected")), orEmpty(text(event, "actual")), instant(text(event, "at"))));
            case "test.collected" -> testCountTotal = intOf(event, "count");
            case "test.started" -> applyTestStarted(event);
            case "test.finished" -> applyTestFinished(event);
            case "preflight.check" -> applyPreflightCheck(event);
            case "recovery.rung" -> recoveries.add(new RunState.Recovery(orEmpty(text(event, "nodeid")),
                    intOf(event, "rung"), orEmpty(text(event, "outcome"))));
            case "connection.state" -> applyConnectionState(event);
            case "log.line" -> applyLogLine(event);
            case "plan.resolved" -> applyPlanResolved(event);
            case "simulator.state" -> applySimulatorState(event);
            default -> {
                // Unknown type: ignored, as the Python ignores it.
            }
        }
    }

    private void applyRunStarted(JsonNode event) {
        runId = orEmpty(text(event, "run_id"));
        String incoming = orEmpty(text(event, "rig"));
        if (!("unknown".equals(incoming) && !rig.isEmpty())) {
            rig = incoming;
        }
        String eventSuite = orEmpty(text(event, "suite"));
        suite = event.path("restated").asBoolean(false) ? eventSuite : (suite.isEmpty() ? eventSuite : suite);
        cmd = orEmpty(text(event, "cmd"));
        workers = intOf(event, "workers");
        if (startedAt == null) {
            startedAt = instant(text(event, "started_at"));
        }
        catVersion = orEmpty(text(event, "cat_version"));
        String mode = text(event, "upgrade_mode");
        if (mode != null && !mode.isEmpty() && !"skip".equals(mode)) {
            upgradeMode = mode;
        }
        List<String> plans = strings(event.get("test_plans"));
        if (!plans.isEmpty()) {
            testPlans = plans;
        }
        List<String> names = strings(event.get("plan_names"));
        if (!names.isEmpty()) {
            planNames = names;
        }
        JsonNode fake = event.has("fake_pos") ? event.get("fake_pos") : event.get("pos_double");
        fakePos = fakePos || (fake != null && fake.asBoolean(false));
        String head = orEmpty(text(event, "git_head"));
        if (!head.isEmpty()) {
            gitHead = head;
        }
        status = "running";
    }

    private void applyRunManifest(JsonNode event) {
        simulators.clear();
        for (JsonNode sim : iterable(event.get("simulators"))) {
            simulators.add(new RunState.SimulatorState(orEmpty(text(sim, "name")), orDefault(text(sim, "kind"),
                    "loopback"), orDefault(text(sim, "status"), "not_started"), "", "", ""));
        }
        connections.clear();
        for (JsonNode conn : iterable(event.get("connections"))) {
            connections.add(new RunState.ConnectionState(orEmpty(text(conn, "target")),
                    orDefault(text(conn, "probe_type"), "unknown"), "unavailable", "", null));
        }
        testCountTotal = integerOf(event, "expected_tests");
    }

    private void applyRunFinished(JsonNode event) {
        for (String nodeid : strings(event.get("warned_nodeids"))) {
            RunState.TestState test = tests.get(nodeid);
            if (test != null) {
                tests.put(nodeid, new RunState.TestState(nodeid, "warned", test.worker(), test.outcome(),
                        test.duration(), Math.max(1, test.warnings()), test.excType(), test.detail(), test.steps()));
            }
        }
        status = event.path("cancelled").asBoolean(false) ? "cancelled" : "complete";
        outcome = text(event, "outcome");
        passed = intOf(event, "passed");
        failed = intOf(event, "failed");
        warned = intOf(event, "warned");
        elapsed = event.path("elapsed").asDouble(0);
    }

    private interface StepChange {
        RunState.StepState apply(RunState.StepState step);
    }

    private void updateStep(String name, StepChange change) {
        int index = name == null ? -1 : RunState.STEP_NAMES.indexOf(name);
        if (index < 0) {
            return;
        }
        steps.set(index, change.apply(steps.get(index)));
    }

    private void applyStepFinished(JsonNode event) {
        String state = orEmpty(text(event, "state"));
        String mapped = switch (state) {
            case "complete", "failed", "skipped", "repairing" -> state;
            default -> "complete";
        };
        double took = event.path("elapsed").asDouble(0);
        String detail = orEmpty(text(event, "detail"));
        updateStep(text(event, "step"), step -> new RunState.StepState(step.name(), mapped, step.progressDone(),
                step.progressTotal(), step.progressDetail(), took, detail, step.checks()));
    }

    private void applyStepCheck(JsonNode event) {
        RunState.SubStep row = new RunState.SubStep(orEmpty(text(event, "label")), orDefault(text(event, "outcome"),
                "running"), orEmpty(text(event, "detail")), event.path("elapsed").asDouble(0), 0);
        updateStep(text(event, "step"), step -> new RunState.StepState(step.name(), step.status(),
                step.progressDone(), step.progressTotal(), step.progressDetail(), step.elapsed(), step.detail(),
                settle(step.checks(), row)));
    }

    /** {@code rows} with {@code row} in place of the running row it settles, else appended. */
    static List<RunState.SubStep> settle(List<RunState.SubStep> rows, RunState.SubStep row) {
        List<RunState.SubStep> out = new ArrayList<>(rows);
        for (int position = out.size() - 1; position >= 0; position--) {
            if (out.get(position).label().equals(row.label())) {
                if ("running".equals(out.get(position).outcome())) {
                    out.set(position, row);
                    return out;
                }
                break;
            }
        }
        out.add(row);
        return out;
    }

    private void applyTestStep(JsonNode event) {
        String nodeid = orEmpty(text(event, "nodeid"));
        RunState.TestState test = tests.getOrDefault(nodeid, RunState.TestState.of(nodeid, "running"));
        String label = (orEmpty(text(event, "keyword")) + " " + orEmpty(text(event, "text"))).strip();
        int index = intOf(event, "index");
        RunState.SubStep row = new RunState.SubStep(label, orDefault(text(event, "outcome"), "running"),
                orEmpty(text(event, "detail")), event.path("duration").asDouble(0), index);
        List<RunState.SubStep> rows = new ArrayList<>();
        for (RunState.SubStep existing : test.steps()) {
            if (existing.index() != index) {
                rows.add(existing);
            }
        }
        rows.add(row);
        rows.sort(Comparator.comparingInt(RunState.SubStep::index));
        tests.put(nodeid, new RunState.TestState(nodeid, test.status(), test.worker(), test.outcome(),
                test.duration(), test.warnings(), test.excType(), test.detail(), rows));
    }

    private void applyTestStarted(JsonNode event) {
        String nodeid = orEmpty(text(event, "nodeid"));
        RunState.TestState test = tests.getOrDefault(nodeid, RunState.TestState.of(nodeid, "pending"));
        tests.put(nodeid, new RunState.TestState(nodeid, "running", text(event, "worker"), test.outcome(),
                test.duration(), test.warnings(), test.excType(), test.detail(), List.of()));
        validations.removeIf(v -> v.nodeid().equals(nodeid));
    }

    private void applyTestFinished(JsonNode event) {
        String nodeid = orEmpty(text(event, "nodeid"));
        RunState.TestState test = tests.getOrDefault(nodeid, RunState.TestState.of(nodeid, "pending"));
        String verdict = orEmpty(text(event, "outcome"));
        String mapped = switch (verdict) {
            case "failed" -> "failed";
            case "skipped" -> "skipped";
            case "error" -> "error";
            case "timeout" -> "timeout";
            case "repairing" -> "warned";
            default -> "passed";
        };
        int warnings = intOf(event, "warnings");
        tests.put(nodeid, new RunState.TestState(nodeid, mapped, test.worker(), verdict,
                event.path("duration").asDouble(0), warnings, orEmpty(text(event, "exc_type")),
                orEmpty(text(event, "detail")), test.steps()));
        if ("passed".equals(verdict)) {
            if (warnings > 0) {
                warned++;
            } else {
                passed++;
            }
        } else if ("failed".equals(verdict)) {
            failed++;
        }
    }

    private void applyPreflightCheck(JsonNode event) {
        String detail = orEmpty(text(event, "detail")) + " [" + orEmpty(text(event, "outcome")) + "]";
        updateStep(text(event, "name"), step -> new RunState.StepState(step.name(), step.status(),
                step.progressDone(), step.progressTotal(), step.progressDetail(), step.elapsed(), detail,
                step.checks()));
    }

    private void applyConnectionState(JsonNode event) {
        String target = orEmpty(text(event, "target"));
        String state = orEmpty(text(event, "state"));
        String detail = orEmpty(text(event, "detail"));
        String model = text(event, "model");
        for (int i = 0; i < connections.size(); i++) {
            RunState.ConnectionState conn = connections.get(i);
            if (conn.target().equals(target)) {
                connections.set(i, new RunState.ConnectionState(target, conn.probeType(), state, detail, model));
                return;
            }
        }
        connections.add(new RunState.ConnectionState(target, "unknown", state, detail, model));
    }

    private void applyLogLine(JsonNode event) {
        RunState.LogLine line = new RunState.LogLine(instant(text(event, "at")), orEmpty(text(event, "level")),
                orEmpty(text(event, "logger")), orEmpty(text(event, "message")), orEmpty(text(event, "step")));
        push(logLines, line);
        String key;
        if (line.logger().startsWith(RunState.SIM_LOGGER_PREFIX)) {
            key = line.logger();
        } else if (!line.step().isEmpty()) {
            key = RunState.stepKey(line.step());
        } else {
            key = "";
        }
        if (!key.isEmpty()) {
            push(sourceLogLines.computeIfAbsent(key, k -> new ArrayDeque<>()), line);
        }
    }

    private static void push(Deque<RunState.LogLine> buffer, RunState.LogLine line) {
        buffer.addLast(line);
        while (buffer.size() > RunState.LOG_BUFFER_LINES) {
            buffer.removeFirst();
        }
    }

    private void applyPlanResolved(JsonNode event) {
        cases.clear();
        for (JsonNode planned : iterable(event.get("cases"))) {
            String key = text(planned, "key");
            if (key == null || key.isEmpty()) {
                continue;
            }
            String node = text(planned, "node");
            cases.put(key, new RunState.CaseState(key, orEmpty(text(planned, "summary")),
                    node == null || node.isEmpty() ? null : node));
        }
    }

    private void applySimulatorState(JsonNode event) {
        String name = orEmpty(text(event, "name"));
        RunState.SimulatorState row = new RunState.SimulatorState(name, orDefault(text(event, "kind"), "supervised"),
                orEmpty(text(event, "state")), orEmpty(text(event, "detail")), orDefault(text(event, "owner"), "CAT"),
                orEmpty(text(event, "bind")));
        for (int i = 0; i < simulators.size(); i++) {
            if (simulators.get(i).name().equals(name)) {
                simulators.set(i, row);
                return;
            }
        }
        simulators.add(row);
    }

    // ------------------------------------------------------------------ JSON

    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String orDefault(String text, String fallback) {
        return text == null || text.isEmpty() ? fallback : text;
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? 0 : value.asInt();
    }

    private static Integer integerOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : iterable(array)) {
            if (!item.isNull()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return array != null && array.isArray() ? array : List.of();
    }

    static Instant instant(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            try {
                return java.time.OffsetDateTime.parse(text).toInstant();
            } catch (RuntimeException again) {
                return null;
            }
        }
    }
}
