package com.verifone.cat.xray;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The part of the Python CAT's {@code RunState} the Xray reporter and its summary read, folded one
 * event at a time.
 *
 * <p>The Python reporter folds through {@code cat.state.apply_event}; this class keeps the fields
 * and reducer rules that reporting needs: the run's identity, the seven steps, every test's latest
 * status (with its duration, exception and detail), the plan's cases and the run-end split of
 * warned tests. The verdict rules ({@link #caseVerdicts}) are the Python {@code case_verdicts}:
 * the same rules {@code RunReplay} uses for Results, restated here because that class folds a
 * whole file and cannot be fed incrementally.
 *
 * <p>Additive fields (the rig, the plans, the start time) keep the first known value, as the
 * Python reducer does when the pytest plugin's {@code run.started} follows the launcher's.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests of this repository's reading of a stream.
 */
public final class XrayRunState {

    /** The seven steps, in order (the Python {@code STEP_NAMES}). */
    public static final List<String> STEP_NAMES = List.of(
            "System Checks", "Connections", "Start Simulators", "Commander Configuration",
            "Tests Run", "Generate Report", "Cleanup");

    /** Index of the step that runs the tests; the steps before it can stop a run. */
    static final int TESTS_RUN_INDEX = 4;

    /** Statuses that count as a verdict (the Python {@code VERDICT_STATUSES}). */
    public static final Set<String> VERDICT_STATUSES =
            Set.of("passed", "failed", "warned", "skipped", "error", "timeout");

    private static final Set<String> FAILING = Set.of("failed", "error", "timeout");

    /** One step: {@code pending} until entered, then whatever it finished as. */
    public record StepState(String name, String status, String detail, double elapsed) {}

    /** One test's latest state. {@code excType} and {@code detail} are "" for a pass. */
    public record TestState(String nodeid, String status, double duration, int warnings,
            String excType, String detail) {}

    /** One plan case; {@code node} is null for a case with no migrated test file. */
    public record CaseState(String key, String summary, String node) {}

    private String runId = "";
    private String rig = "";
    private String suite = "";
    private String startedAt = "";
    private String catVersion = "";
    private String upgradeMode = "skip";
    private List<String> testPlans = List.of();
    private List<String> planNames = List.of();
    private boolean fakePos;
    private boolean cancelled;
    private boolean runFinished;
    private double elapsed;
    private List<String> warnedNodeids = List.of();
    private final Map<String, StepState> steps = new LinkedHashMap<>();
    private final Map<String, TestState> tests = new LinkedHashMap<>();
    private final Map<String, CaseState> cases = new LinkedHashMap<>();

    public XrayRunState() {
        for (String name : STEP_NAMES) {
            steps.put(name, new StepState(name, "pending", "", 0.0));
        }
    }

    /** Folds one event; events of types this state does not read change nothing. */
    public void apply(JsonNode event) {
        String type = text(event, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "run.started" -> onRunStarted(event);
            case "run.finished" -> onRunFinished(event);
            case "step.entered" -> onStepEntered(event);
            case "step.finished" -> onStepFinished(event);
            case "test.started" -> onTestStarted(event);
            case "test.finished" -> onTestFinished(event);
            case "plan.resolved" -> onPlanResolved(event);
            default -> {
                // Nothing else changes what the Xray reporting says.
            }
        }
    }

    private void onRunStarted(JsonNode event) {
        // The launcher's and the plugin's run.started both arrive; a defaulted value never
        // overwrites a known one.
        if (runId.isEmpty()) {
            runId = orEmpty(text(event, "run_id"));
        }
        String incomingRig = orEmpty(text(event, "rig"));
        if (!incomingRig.isEmpty() && !("unknown".equals(incomingRig) && !rig.isEmpty())) {
            rig = incomingRig;
        }
        if (startedAt.isEmpty()) {
            startedAt = orEmpty(text(event, "started_at"));
        }
        if (suite.isEmpty()) {
            suite = orEmpty(text(event, "suite"));
        }
        String version = orEmpty(text(event, "cat_version"));
        if (!version.isEmpty()) {
            catVersion = version;
        }
        String upgrade = orEmpty(text(event, "upgrade_mode"));
        if (!upgrade.isEmpty() && !"skip".equals(upgrade)) {
            upgradeMode = upgrade;
        }
        List<String> plans = strings(event.get("test_plans"));
        if (!plans.isEmpty()) {
            testPlans = plans;
        }
        List<String> names = strings(event.get("plan_names"));
        if (!names.isEmpty()) {
            planNames = names;
        }
        if (event.path("fake_pos").asBoolean(false)) {
            fakePos = true;
        }
    }

    private void onRunFinished(JsonNode event) {
        runFinished = true;
        cancelled = event.path("cancelled").asBoolean(false);
        elapsed = event.path("elapsed").asDouble(0.0);
        warnedNodeids = strings(event.get("warned_nodeids"));
        for (String nodeid : warnedNodeids) {
            TestState test = tests.get(nodeid);
            if (test != null) {
                tests.put(nodeid, new TestState(nodeid, "warned", test.duration(),
                        Math.max(1, test.warnings()), test.excType(), test.detail()));
            }
        }
    }

    private void onStepEntered(JsonNode event) {
        String name = text(event, "step");
        if (name != null && steps.containsKey(name)) {
            steps.put(name, new StepState(name, "running", "", 0.0));
        }
    }

    private void onStepFinished(JsonNode event) {
        String name = text(event, "step");
        if (name == null || !steps.containsKey(name)) {
            return;
        }
        String state = orEmpty(text(event, "state"));
        String status = switch (state) {
            case "failed", "skipped", "repairing" -> state;
            default -> "complete"; // the Python reducer's default for an unknown state
        };
        steps.put(name, new StepState(name, status, orEmpty(text(event, "detail")),
                event.path("elapsed").asDouble(0.0)));
    }

    private void onTestStarted(JsonNode event) {
        String nodeid = text(event, "nodeid");
        if (nodeid == null) {
            return;
        }
        TestState previous = tests.get(nodeid);
        tests.put(nodeid, previous == null
                ? new TestState(nodeid, "running", 0.0, 0, "", "")
                : new TestState(nodeid, "running", previous.duration(), previous.warnings(),
                        previous.excType(), previous.detail()));
    }

    private void onTestFinished(JsonNode event) {
        String nodeid = text(event, "nodeid");
        if (nodeid == null) {
            return;
        }
        String status = switch (orEmpty(text(event, "outcome"))) {
            case "failed" -> "failed";
            case "skipped" -> "skipped";
            case "error" -> "error";
            case "timeout" -> "timeout";
            case "repairing" -> "warned";
            default -> "passed";
        };
        tests.put(nodeid, new TestState(nodeid, status, event.path("duration").asDouble(0.0),
                event.path("warnings").asInt(0), orEmpty(text(event, "exc_type")),
                orEmpty(text(event, "detail"))));
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
            cases.put(key, new CaseState(key, orEmpty(text(planned, "summary")),
                    node == null || node.isEmpty() ? null : node));
        }
    }

    // ------------------------------------------------------------------ accessors

    public String runId() {
        return runId;
    }

    public String rig() {
        return rig;
    }

    public String suite() {
        return suite;
    }

    /** The launcher's ISO start time, "" when no {@code run.started} was read. */
    public String startedAt() {
        return startedAt;
    }

    /** The start time as a moment, or empty when it is missing or not ISO-8601. */
    public Optional<OffsetDateTime> startedMoment() {
        try {
            return startedAt.isEmpty() ? Optional.empty() : Optional.of(OffsetDateTime.parse(startedAt));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public String catVersion() {
        return catVersion;
    }

    public String upgradeMode() {
        return upgradeMode;
    }

    public List<String> testPlans() {
        return testPlans;
    }

    public List<String> planNames() {
        return planNames;
    }

    public boolean fakePos() {
        return fakePos;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Whether a {@code run.finished} was read. */
    public boolean runFinished() {
        return runFinished;
    }

    public Map<String, StepState> steps() {
        return steps;
    }

    public Map<String, TestState> tests() {
        return tests;
    }

    public Map<String, CaseState> cases() {
        return cases;
    }

    /** The run's wall time: the steps' sum when recorded, else the pytest session's. */
    public double runElapsed() {
        double steps = this.steps.values().stream().mapToDouble(StepState::elapsed).sum();
        return steps > 0 ? steps : elapsed;
    }

    /** Plan case key to its one verdict, aggregated over its file's tests; unjudged cases are absent. */
    public Map<String, String> caseVerdicts() {
        Map<String, Set<String>> byFile = new HashMap<>();
        for (TestState test : tests.values()) {
            if (VERDICT_STATUSES.contains(test.status())) {
                byFile.computeIfAbsent(nodeFile(test.nodeid()), k -> new HashSet<>()).add(test.status());
            }
        }
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (CaseState planned : cases.values()) {
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

    /** How many cases have each verdict. */
    public Map<String, Integer> verdictCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        caseVerdicts().values().forEach(verdict -> counts.merge(verdict, 1, Integer::sum));
        return counts;
    }

    /** The file part of a pytest nodeid, the form {@link CaseState#node} takes. */
    public static String nodeFile(String nodeid) {
        return nodeid.split("::", 2)[0].replace('\\', '/');
    }

    /** One verdict for a file's tests, worst first (the Python {@code _aggregate_verdict}). */
    static String aggregate(Set<String> statuses) {
        if (statuses.stream().anyMatch(FAILING::contains)) {
            return "failed";
        }
        if (statuses.contains("warned")) {
            return "warned";
        }
        if (statuses.contains("passed")) {
            return "passed";
        }
        return "skipped";
    }

    /** The tests of each file, in the order the stream first named them. */
    Map<String, List<TestState>> testsByFile() {
        Map<String, List<TestState>> byFile = new LinkedHashMap<>();
        for (TestState test : tests.values()) {
            byFile.computeIfAbsent(nodeFile(test.nodeid()), k -> new ArrayList<>()).add(test);
        }
        return byFile;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> out.add(item.asText()));
        }
        return List.copyOf(out);
    }
}
