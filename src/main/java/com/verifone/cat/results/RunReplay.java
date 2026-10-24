package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.NdjsonReader;
import com.verifone.cat.report.RunFacts;

/**
 * One run's {@code events.ndjson} folded, in file order, into what Results shows.
 *
 * <p>The Python CAT replays a file through {@code cat.state.apply_event} into a whole
 * {@code RunState}. This is the part of that state Results reads: the run's start and
 * finish, the seven steps, every test's verdict, the plan's cases, and (on request) the
 * log lines. {@code run.started}'s {@code fake_pos} and {@code test_plans}, the recovery
 * rungs and the preflight checks come from {@link RunFacts}, the fold the report writers
 * share; the rest is folded here.
 *
 * <p>Two rules from the Python reducer are kept: the rig a plugin stamps as
 * {@code unknown} never overwrites a known one, and a run's {@code warned_nodeids} move
 * those tests to {@code warned} whatever order their {@code test.finished} arrived in.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests of this repository's reading of recorded
 * runs, not of a rig.
 */
public final class RunReplay {

    /** The seven steps, in the order the launcher runs them (the Python {@code STEP_NAMES}). */
    public static final List<String> STEP_NAMES = List.of(
            "System Checks", "Connections", "Start Simulators", "Commander Configuration",
            "Tests Run", "Generate Report", "Cleanup");

    /** The statuses that count as a verdict; the statistics rate these. */
    public static final Set<String> VERDICT_STATUSES =
            Set.of("passed", "failed", "warned", "skipped", "error", "timeout");

    private static final Set<String> FAILING_STATUSES = Set.of("failed", "error", "timeout");

    /** A test's state in its latest event: {@code running} until a verdict arrives. */
    public record TestState(String nodeid, String status, double duration, String excType) {}

    /** One step: {@code pending} until it is entered, then whatever it finished as. */
    public record StepState(String name, String status, double elapsed) {}

    /** One planned case. {@code node} is null for a case with no migrated test file. */
    public record PlanCase(String key, String summary, String node) {}

    /** One {@code log.line}, as a Log view shows it. */
    public record LogLine(String level, String logger, String message) {}

    private final Path eventsFile;
    private final RunFacts facts = new RunFacts();
    private final boolean includeLogs;
    private final Map<String, String> stepStatus = new LinkedHashMap<>();
    private final Map<String, Double> stepElapsed = new HashMap<>();
    private final Map<String, TestState> tests = new LinkedHashMap<>();
    private final Map<String, PlanCase> cases = new LinkedHashMap<>();
    private final List<LogLine> logLines = new ArrayList<>();
    private List<String> warnedNodeids = List.of();

    private String rig = "";
    private String startedAt = "";
    private String cmd = "";
    private String gitHead = "";
    private List<String> planNames = List.of();
    private boolean finished;
    private boolean cancelled;
    private String outcome;
    private int finishedPassed;
    private int finishedFailed;
    private int finishedWarned;
    private double finishedElapsed;
    private int livePassed;
    private int liveFailed;
    private int liveWarned;

    private RunReplay(Path eventsFile, boolean includeLogs) {
        this.eventsFile = eventsFile;
        this.includeLogs = includeLogs;
        for (String step : STEP_NAMES) {
            stepStatus.put(step, "pending");
            stepElapsed.put(step, 0.0);
        }
    }

    /**
     * Folds {@code eventsFile} in file order. Log lines are skipped unless {@code includeLogs}:
     * they are most of a real run's lines, and no summary reads them.
     *
     * @throws IOException when the file cannot be read as UTF-8
     */
    public static RunReplay read(Path eventsFile, boolean includeLogs) throws IOException {
        RunReplay replay = new RunReplay(eventsFile, includeLogs);
        NdjsonReader.forEach(eventsFile, replay::apply);
        replay.settle();
        return replay;
    }

    /** The events file this replay read. */
    public Path eventsFile() {
        return eventsFile;
    }

    private void apply(JsonNode event) {
        facts.fold(event);
        String type = text(event, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "run.started" -> onRunStarted(event);
            case "run.finished" -> onRunFinished(event);
            case "step.entered" -> onStep(event, "running", null);
            case "step.finished" -> onStep(event, stepState(event), event.get("elapsed"));
            case "test.started" -> onTestStarted(event);
            case "test.finished" -> onTestFinished(event);
            case "plan.resolved" -> onPlanResolved(event);
            case "log.line" -> {
                if (includeLogs) {
                    logLines.add(new LogLine(orEmpty(text(event, "level")),
                            orEmpty(text(event, "logger")), orEmpty(text(event, "message"))));
                }
            }
            default -> {
                // Other event types change nothing Results shows.
            }
        }
    }

    private void onRunStarted(JsonNode event) {
        // The launcher's and the plugin's run.started both arrive; a plugin's "unknown" rig
        // does not erase a known one (the Python reducer's rule).
        String incoming = orEmpty(text(event, "rig"));
        if (!("unknown".equals(incoming) && !rig.isEmpty())) {
            rig = incoming;
        }
        if (startedAt.isEmpty()) {
            startedAt = orEmpty(text(event, "started_at"));
        }
        cmd = orEmpty(text(event, "cmd"));
        String head = orEmpty(text(event, "git_head"));
        if (!head.isEmpty()) {
            gitHead = head;
        }
        List<String> names = new ArrayList<>();
        JsonNode array = event.get("plan_names");
        if (array != null && array.isArray()) {
            array.forEach(name -> names.add(name.asText()));
        }
        if (!names.isEmpty()) {
            planNames = List.copyOf(names);
        }
    }

    private void onRunFinished(JsonNode event) {
        finished = true;
        cancelled = event.path("cancelled").asBoolean(false);
        outcome = text(event, "outcome");
        finishedPassed = event.path("passed").asInt();
        finishedFailed = event.path("failed").asInt();
        finishedWarned = event.path("warned").asInt();
        finishedElapsed = event.path("elapsed").asDouble();
        List<String> warned = new ArrayList<>();
        JsonNode array = event.get("warned_nodeids");
        if (array != null && array.isArray()) {
            array.forEach(node -> warned.add(node.asText()));
        }
        warnedNodeids = List.copyOf(warned);
    }

    private void onStep(JsonNode event, String status, JsonNode elapsed) {
        String name = text(event, "step");
        if (name == null || !stepStatus.containsKey(name)) {
            return;
        }
        stepStatus.put(name, status);
        if (elapsed != null) {
            stepElapsed.put(name, elapsed.asDouble());
        }
    }

    private static String stepState(JsonNode event) {
        String state = text(event, "state");
        return state == null || state.isEmpty() ? "failed" : state;
    }

    private void onTestStarted(JsonNode event) {
        String nodeid = text(event, "nodeid");
        if (nodeid != null) {
            tests.putIfAbsent(nodeid, new TestState(nodeid, "running", 0.0, ""));
        }
    }

    private void onTestFinished(JsonNode event) {
        String nodeid = text(event, "nodeid");
        if (nodeid == null) {
            return;
        }
        String verdict = orEmpty(text(event, "outcome"));
        int warnings = event.path("warnings").asInt(0);
        String status = switch (verdict) {
            case "failed" -> "failed";
            case "skipped" -> "skipped";
            case "error" -> "error";
            case "timeout" -> "timeout";
            case "repairing" -> "warned";
            default -> "passed";
        };
        if ("passed".equals(verdict)) {
            if (warnings > 0) {
                liveWarned++;
            } else {
                livePassed++;
            }
        } else if ("failed".equals(verdict)) {
            liveFailed++;
        }
        tests.put(nodeid, new TestState(nodeid, status, event.path("duration").asDouble(),
                orEmpty(text(event, "exc_type"))));
    }

    private void onPlanResolved(JsonNode event) {
        JsonNode array = event.get("cases");
        if (array == null || !array.isArray()) {
            return;
        }
        cases.clear();
        for (JsonNode planned : array) {
            String key = text(planned, "key");
            if (key == null || key.isEmpty()) {
                continue;
            }
            String node = text(planned, "node");
            cases.put(key, new PlanCase(key, orEmpty(text(planned, "summary")),
                    node == null || node.isEmpty() ? null : node));
        }
    }

    /** Moves the warned tests off {@code passed}, once the whole stream is read. */
    private void settle() {
        for (String nodeid : warnedNodeids) {
            TestState test = tests.get(nodeid);
            if (test != null) {
                tests.put(nodeid, new TestState(nodeid, "warned", test.duration(), test.excType()));
            }
        }
    }

    /** The run's rig as the launcher recorded it, "" when no run.started was read. */
    public String rig() {
        return rig;
    }

    /** The first run.started's time, or "" when none was read. */
    public String startedAt() {
        return startedAt;
    }

    public String cmd() {
        return cmd;
    }

    public String gitHead() {
        return gitHead;
    }

    public List<String> planNames() {
        return planNames;
    }

    public boolean fakePos() {
        return facts.fakePos();
    }

    public List<String> testPlans() {
        return facts.testPlans();
    }

    /** True once a run.finished has been read. */
    public boolean finished() {
        return finished;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** The run's outcome word, or null when no run.finished was read. */
    public String outcome() {
        return outcome;
    }

    /** run.finished's count once it is read, the live count before. */
    public int passed() {
        return finished ? finishedPassed : livePassed;
    }

    public int failed() {
        return finished ? finishedFailed : liveFailed;
    }

    public int warned() {
        return finished ? finishedWarned : liveWarned;
    }

    /** The steps in the launcher's order. */
    public List<StepState> steps() {
        List<StepState> out = new ArrayList<>();
        for (String name : STEP_NAMES) {
            out.add(new StepState(name, stepStatus.get(name), stepElapsed.get(name)));
        }
        return out;
    }

    /** Every test that has an event, in the order its first event arrived. */
    public List<TestState> tests() {
        return List.copyOf(tests.values());
    }

    /** The plan's cases in plan order. Empty when no plan was resolved. */
    public List<PlanCase> cases() {
        return List.copyOf(cases.values());
    }

    /** Every log line read, when the replay was asked for logs; else empty. */
    public List<LogLine> logLines() {
        return List.copyOf(logLines);
    }

    /** The run's wall time: the steps' sum when the launcher recorded them, else run.finished's. */
    public double runElapsed() {
        double steps = stepElapsed.values().stream().mapToDouble(Double::doubleValue).sum();
        return steps > 0 ? steps : finishedElapsed;
    }

    /**
     * Plan case key to its single verdict, aggregated over its file's tests.
     *
     * <p>A case resolves to a file and a file can hold several tests, so the file is collapsed
     * worst first: any failure makes the case failed, then any warning, then any pass; a file
     * whose every test was skipped is skipped. A case with no verdict yet is absent.
     */
    public Map<String, String> caseVerdicts() {
        Map<String, Set<String>> byFile = new HashMap<>();
        for (TestState test : tests.values()) {
            if (VERDICT_STATUSES.contains(test.status())) {
                byFile.computeIfAbsent(nodeFile(test.nodeid()), k -> new HashSet<>()).add(test.status());
            }
        }
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (PlanCase planned : cases.values()) {
            if (planned.node() == null) {
                continue;
            }
            Set<String> statuses = byFile.get(planned.node());
            if (statuses != null && !statuses.isEmpty()) {
                verdicts.put(planned.key(), aggregate(statuses));
            }
        }
        return verdicts;
    }

    /** Plan cases with no verdict (unmigrated, or never reached), in plan order. */
    public List<PlanCase> notRunCases() {
        if (cases.isEmpty()) {
            return List.of();
        }
        Map<String, String> verdicts = caseVerdicts();
        List<PlanCase> out = new ArrayList<>();
        for (PlanCase planned : cases.values()) {
            if (!verdicts.containsKey(planned.key())) {
                out.add(planned);
            }
        }
        return out;
    }

    /** The recovery rungs and preflight checks, as the report writers fold them. */
    public RunFacts facts() {
        return facts;
    }

    /** The repo-relative file part of a pytest nodeid, with forward slashes. */
    static String nodeFile(String nodeid) {
        return nodeid.split("::", 2)[0].replace('\\', '/');
    }

    static String aggregate(Set<String> statuses) {
        for (String status : FAILING_STATUSES) {
            if (statuses.contains(status)) {
                return "failed";
            }
        }
        if (statuses.contains("warned")) {
            return "warned";
        }
        if (statuses.contains("passed")) {
            return "passed";
        }
        return "skipped";
    }

    /** The field as text, or null when it is absent or JSON null. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
