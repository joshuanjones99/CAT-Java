package com.verifone.cat.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a run's report writers learn from the events that are the same for every writer.
 *
 * <p>The Python CAT's {@code petrotest.report.ndjson.fold_common}: {@code run.started},
 * {@code plan.resolved}, {@code recovery.rung} and {@code preflight.check}. The JUnit
 * writer and the HTML summary both fold these identically, so they live here. The
 * {@code test.finished} fold does not: each writer keeps its own, since JUnit keys it by
 * node id and HTML buckets it by outcome.
 *
 * <p>Two rules from the Python code are kept on purpose:
 * <ul>
 *   <li>{@code fake_pos} is sticky. Two {@code run.started} events reach the stream (the
 *       launcher's and the pytest plugin's), and only the pytest side knows whether
 *       {@code --fake-pos} was passed, so once either says true the run is a fake-POS run.
 *       Older streams call the field {@code pos_double}.</li>
 *   <li>{@code test_plans} keeps the earlier list when a later event sends none.</li>
 * </ul>
 */
public final class RunFacts {

    /** One recovery rung's outcome, as the ladder reported it. */
    public record Rung(String rung, String outcome) {}

    /** One preflight check's outcome. {@code detail} is {@code ""} when the event carried none. */
    public record PreflightCheck(String name, String phase, String outcome, String detail) {}

    private String runId;
    private String rig;
    private boolean fakePos;
    private List<String> testPlans = List.of();
    private final Map<String, String> notRunCases = new LinkedHashMap<>();
    private final Map<String, List<Rung>> recoveryRungs = new LinkedHashMap<>();
    private final List<PreflightCheck> preflightChecks = new ArrayList<>();

    /**
     * Folds {@code event} into this state if it is one of the four common types.
     *
     * @return whether {@code event} was one of them (a writer folds the rest itself)
     */
    public boolean fold(JsonNode event) {
        String type = text(event, "type");
        if (type == null) {
            return false;
        }
        switch (type) {
            case "run.started" -> foldRunStarted(event);
            case "plan.resolved" -> foldPlanResolved(event);
            case "recovery.rung" -> foldRecoveryRung(event);
            case "preflight.check" -> preflightChecks.add(new PreflightCheck(
                    text(event, "name"), text(event, "phase"), text(event, "outcome"),
                    orEmpty(text(event, "detail"))));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void foldRunStarted(JsonNode event) {
        runId = text(event, "run_id");
        rig = text(event, "rig");
        // `fake_pos` wins when present; a stream from before 2026-10-08 says `pos_double`.
        JsonNode fake = event.has("fake_pos") ? event.get("fake_pos") : event.get("pos_double");
        fakePos = fakePos || truthy(fake);
        List<String> plans = new ArrayList<>();
        JsonNode array = event.get("test_plans");
        if (array != null && array.isArray()) {
            array.forEach(plan -> plans.add(plan.asText()));
        }
        if (!plans.isEmpty()) {
            testPlans = List.copyOf(plans);
        }
    }

    private void foldPlanResolved(JsonNode event) {
        JsonNode cases = event.get("cases");
        if (cases == null || !cases.isArray()) {
            return;
        }
        for (JsonNode plannedCase : cases) {
            String key = text(plannedCase, "key");
            if (key != null && !key.isEmpty() && !truthy(plannedCase.get("node"))) {
                notRunCases.put(key, orEmpty(text(plannedCase, "summary")));
            }
        }
    }

    private void foldRecoveryRung(JsonNode event) {
        String nodeid = text(event, "nodeid");
        if (nodeid == null || nodeid.isEmpty()) {
            return;
        }
        recoveryRungs.computeIfAbsent(nodeid, k -> new ArrayList<>())
                .add(new Rung(text(event, "rung"), text(event, "outcome")));
    }

    /** The field as text, or {@code null} when it is absent or JSON {@code null}. */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /** Python's truthiness for the JSON values these events carry. */
    static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0;
        }
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        return value.size() > 0;
    }

    public String runId() {
        return runId;
    }

    public String rig() {
        return rig;
    }

    public boolean fakePos() {
        return fakePos;
    }

    public List<String> testPlans() {
        return Collections.unmodifiableList(testPlans);
    }

    /** Case key to its summary, for every planned case with no migrated test file. */
    public Map<String, String> notRunCases() {
        return Collections.unmodifiableMap(notRunCases);
    }

    /** Node id to its recovery rungs, in the order they happened. */
    public Map<String, List<Rung>> recoveryRungs() {
        return Collections.unmodifiableMap(recoveryRungs);
    }

    public List<PreflightCheck> preflightChecks() {
        return Collections.unmodifiableList(preflightChecks);
    }
}
