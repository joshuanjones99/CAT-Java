package com.verifone.cat.xray;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.plans.PlanCase;
import com.verifone.cat.xray.Summary.SummaryFacts;
import com.verifone.cat.xray.Summary.Verdict;

/** Builders for the events, the stream and the reporter the Xray tests use (the Python's helpers). */
final class Events {

    static final String PLAN = "PETROCORE-70960";
    static final String RUN_ID = "run1234567890";
    static final String PLAN_NAME = "Charity Donations Indoor";
    static final AtlassianCredentials.Jira JIRA = new AtlassianCredentials.Jira("tester@verifone.com", "token");
    static final AtlassianCredentials.Xray XRAY = new AtlassianCredentials.Xray("id", "secret");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 7, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final DateTimeFormatter WIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    static final List<PlanCase> CASES = List.of(
            new PlanCase("PETROCORE-61396", "Charity on", file("PETROCORE-61396")),
            new PlanCase("PETROCORE-37043", "Login", file("PETROCORE-37043")),
            new PlanCase("PETROCORE-61334", "Donate $1", file("PETROCORE-61334")),
            new PlanCase("PETROCORE-99999", "Not migrated", null));

    private Events() {}

    /** The test file of a case: the {@code cases[*].node} form. */
    static String file(String key) {
        return "tests/suites/charity/" + key + "-TestCase.py";
    }

    /** The pytest nodeid the stream names a case's test by. */
    static String node(String key) {
        return "tests/suites/charity/" + key + "-TestCase.py::test_" + key.toLowerCase().replace('-', '_');
    }

    /** One event: the envelope the Python writes, then the fields. */
    static ObjectNode event(int seq, String type, Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", 1);
        out.put("at", T0.plusMinutes(seq).format(WIRE));
        out.put("seq", seq);
        out.put("worker", "controller");
        out.put("type", type);
        out.putAll(fields);
        return (ObjectNode) JSON.valueToTree(out);
    }

    static ObjectNode event(int seq, String type, String worker, Map<String, Object> fields) {
        ObjectNode node = event(seq, type, fields);
        node.put("worker", worker);
        return node;
    }

    static ObjectNode runStarted(Map<String, Object> overrides) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run_id", RUN_ID);
        fields.put("rig", "lab");
        fields.put("suite", "tests/suites/charity");
        fields.put("cmd", "python -m pytest");
        fields.put("workers", 1);
        fields.put("started_at", "2026-10-07T10:00:00+00:00");
        fields.put("cat_version", "0.1.0");
        fields.put("upgrade_mode", "skip");
        fields.put("test_plans", List.of(PLAN));
        fields.put("fake_pos", false);
        fields.putAll(overrides);
        return event(0, "run.started", fields);
    }

    static ObjectNode runStarted() {
        return runStarted(Map.of());
    }

    static ObjectNode planResolved(int seq) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (PlanCase planned : CASES) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", planned.key());
            row.put("summary", planned.summary());
            row.put("node", planned.node());
            cases.add(row);
        }
        return event(seq, "plan.resolved", Map.of("cases", cases));
    }

    static ObjectNode started(int seq, String nodeid) {
        return event(seq, "test.started", "gw0", Map.of("nodeid", nodeid));
    }

    static ObjectNode finished(int seq, String nodeid, String outcome, Map<String, Object> extra) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nodeid", nodeid);
        fields.put("outcome", outcome);
        fields.put("duration", 2.0);
        fields.put("warnings", 0);
        fields.putAll(extra);
        return event(seq, "test.finished", "gw0", fields);
    }

    static ObjectNode finished(int seq, String nodeid, String outcome) {
        return finished(seq, nodeid, outcome, Map.of());
    }

    static ObjectNode runFinished(int seq, Map<String, Object> extra) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run_id", RUN_ID);
        fields.put("outcome", "failed");
        fields.put("passed", 1);
        fields.put("failed", 1);
        fields.put("warned", 0);
        fields.put("elapsed", 4.0);
        fields.put("dropped_events", 0);
        fields.putAll(extra);
        return event(seq, "run.finished", "gw0", fields);
    }

    static ObjectNode cleanup(int seq, String state, double elapsed) {
        return event(seq, "step.finished", Map.of("step", "Cleanup", "state", state, "elapsed", elapsed));
    }

    /** A whole run: one pass, one validation failure, one case never reached. */
    static List<ObjectNode> stream() {
        List<ObjectNode> out = new ArrayList<>();
        out.add(runStarted());
        out.add(planResolved(1));
        out.add(started(0, node("PETROCORE-61396")));
        out.add(finished(1, node("PETROCORE-61396"), "passed"));
        out.add(started(2, node("PETROCORE-37043")));
        out.add(finished(3, node("PETROCORE-37043"), "failed", Map.of(
                "exc_type", "AssertionError",
                "detail", "PETROCORE-37043: 1 validation(s) failed (PATS carries on past these, so every "
                        + "one is listed):\n\n#12 VALIDATE_RECEIPT(SELECT_FILE='x'): receipt differs:\n--- a")));
        out.add(runFinished(4, Map.of()));
        out.add(cleanup(2, "complete", 1.0));
        return out;
    }

    /** The launcher's facts for the Charity run, as the Python test built them. */
    static SummaryFacts facts() {
        return new SummaryFacts("Solutions1052", "buypass", "master", "", "BENCH", "");
    }

    static AtlassianClient client(XrayFake fake) {
        return new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"), () -> JIRA, () -> XRAY,
                java.time.Duration.ofSeconds(5));
    }

    static XrayClient xrayClient(XrayFake fake) {
        return new XrayClient(client(fake), java.time.Duration.ZERO, null);
    }

    /** A reporter over {@code dir}; a null client is a dry run. {@code now} is its clock, in seconds. */
    static XrayReporter reporter(Path dir, XrayClient client, double[] now,
            Function<XrayRunState, Map<String, List<Verdict>>> history) {
        DoubleSupplier clock = () -> now[0];
        return new XrayReporter(dir, client, facts(), 5.0, key -> CASES, key -> PLAN_NAME, history, clock);
    }

    static XrayReporter reporter(Path dir, XrayClient client) {
        return reporter(dir, client, new double[] {0.0}, null);
    }

    static String read(Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
