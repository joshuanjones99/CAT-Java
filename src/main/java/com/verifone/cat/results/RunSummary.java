package com.verifone.cat.results;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One run, digested for the run list and the cross-run statistics.
 *
 * <p>The Python CAT's {@code cat.results.store.RunSummary}. It is derived, never
 * authoritative: opening a run re-reads its {@code events.ndjson}. The JSON shape
 * ({@link #toJson}, {@link #fromJson}) is the Python's, so an index written by either
 * CAT reads in the other.
 *
 * @param runId      the run folder's name
 * @param startedAt  ISO-8601 start, or the events file's mtime when no run.started was read
 * @param rig        the rig's name, or "" / "unknown" for a plain pytest session
 * @param fakePos    ran against the fake POS
 * @param testPlans  the Jira plan keys chosen for the run
 * @param status     passed, failed, error, skipped, timeout, cancelled or incomplete
 * @param passed     run.finished's count (live counts when it is absent)
 * @param failed     likewise
 * @param warned     likewise
 * @param notRun     plan cases with no verdict
 * @param elapsed    wall seconds: the steps' sum when recorded
 * @param gitHead    the commit at session start, "" when unrecorded
 * @param cmd        the recorded pytest command line
 * @param steps      the seven steps in order
 * @param tests      per-test verdicts (non-passing only for a "no rig" run)
 * @param planNames  the plans' names as the run recorded them; empty for an older run
 */
public record RunSummary(
        String runId,
        String startedAt,
        String rig,
        boolean fakePos,
        List<String> testPlans,
        String status,
        int passed,
        int failed,
        int warned,
        int notRun,
        double elapsed,
        String gitHead,
        String cmd,
        List<StepRow> steps,
        List<TestRow> tests,
        List<String> planNames) {

    /** One step: its name, status and seconds. */
    public record StepRow(String name, String status, double elapsed) {}

    /** One test's verdict in one run. */
    public record TestRow(String nodeid, String status, double duration, String excType) {}

    /** Rig values a plain pytest session stamps; they are never aggregated as a rig. */
    static final Set<String> NO_RIG = Set.of("", "unknown");

    public RunSummary {
        testPlans = List.copyOf(testPlans);
        steps = List.copyOf(steps);
        tests = List.copyOf(tests);
        planNames = List.copyOf(planNames);
    }

    /** {@code fake_pos}, {@code rig} or {@code no rig}: never aggregated together. */
    public String segment() {
        if (fakePos) {
            return "fake_pos";
        }
        return NO_RIG.contains(rig) ? "no rig" : "rig";
    }

    /** The index row for this run, in the Python CAT's key names. */
    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        out.put("run_id", runId);
        out.put("started_at", startedAt);
        out.put("rig", rig);
        out.put("fake_pos", fakePos);
        out.set("test_plans", mapper.valueToTree(testPlans));
        out.put("status", status);
        out.put("passed", passed);
        out.put("failed", failed);
        out.put("warned", warned);
        out.put("not_run", notRun);
        out.put("elapsed", elapsed);
        out.put("git_head", gitHead);
        out.put("cmd", cmd);
        ArrayNode stepRows = out.putArray("steps");
        for (StepRow step : steps) {
            stepRows.addArray().add(step.name()).add(step.status()).add(step.elapsed());
        }
        ArrayNode testRows = out.putArray("tests");
        for (TestRow test : tests) {
            testRows.addArray().add(test.nodeid()).add(test.status()).add(test.duration()).add(test.excType());
        }
        out.set("plan_names", mapper.valueToTree(planNames));
        return out;
    }

    /**
     * The inverse of {@link #toJson}. A row from before {@code plan_names} existed reads as
     * having none.
     *
     * @throws IllegalArgumentException when a required field is missing or malformed
     */
    public static RunSummary fromJson(JsonNode data) {
        List<StepRow> steps = new ArrayList<>();
        for (JsonNode step : required(data, "steps")) {
            steps.add(new StepRow(step.get(0).asText(), step.get(1).asText(), step.get(2).asDouble()));
        }
        List<TestRow> tests = new ArrayList<>();
        for (JsonNode test : required(data, "tests")) {
            tests.add(new TestRow(test.get(0).asText(), test.get(1).asText(),
                    test.get(2).asDouble(), test.get(3).asText()));
        }
        List<String> plans = new ArrayList<>();
        required(data, "test_plans").forEach(plan -> plans.add(plan.asText()));
        List<String> names = new ArrayList<>();
        if (data.hasNonNull("plan_names")) {
            data.get("plan_names").forEach(name -> names.add(name.asText()));
        }
        return new RunSummary(
                text(data, "run_id"),
                text(data, "started_at"),
                text(data, "rig"),
                required(data, "fake_pos").asBoolean(),
                plans,
                text(data, "status"),
                required(data, "passed").asInt(),
                required(data, "failed").asInt(),
                required(data, "warned").asInt(),
                required(data, "not_run").asInt(),
                required(data, "elapsed").asDouble(),
                text(data, "git_head"),
                text(data, "cmd"),
                steps,
                tests,
                names);
    }

    private static JsonNode required(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("index row has no " + field);
        }
        return value;
    }

    private static String text(JsonNode data, String field) {
        return required(data, field).asText();
    }
}
