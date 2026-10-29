package com.verifone.cat.authoring;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.NdjsonReader;

/**
 * A replayed run's {@code events.ndjson}, folded into what the replay gate reads: the part of the Python's
 * {@code cat.state.RunState} that {@code cat.authoring.replay.judge} looks at, folded as {@code cat.state.reducer} folds
 * it ({@code cat.results.store.replay}).
 *
 * <p>Kept here rather than read from {@code results.RunReplay}, which folds what the Results page shows and keeps no
 * step or test detail: the gate quotes both. The rules are the reducer's: a step's {@code step.finished} state, unknown
 * ones {@code complete}; a {@code preflight.check} named for a step sets that step's detail; a test {@code running} from
 * its {@code test.started}, then its {@code test.finished} outcome ({@code repairing} is {@code warned}); the run's
 * {@code warned_nodeids} move those tests to {@code warned}; {@code cancelled} is a status of its own.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: read from files this repository's tests write.
 */
public final class ReplayRun {

    /** The seven steps, in run order (the Python {@code STEP_NAMES}). */
    public static final List<String> STEP_NAMES = List.of(
            "System Checks", "Connections", "Start Simulators", "Commander Configuration",
            "Tests Run", "Generate Report", "Cleanup");

    /** One step: {@code pending} until entered. */
    public record Step(String name, String status, String detail) {}

    /** One test's latest state. */
    public record Test(String nodeid, String status, String excType, String detail) {}

    /** One recovery ladder walk: the node, its rung, its outcome. */
    public record Recovery(String nodeid, String rung, String outcome) {}

    private String cmd = "";
    private boolean fakePos;
    private String status = "running";
    private String outcome;
    private final Map<String, Step> steps = new LinkedHashMap<>();
    private final Map<String, Test> tests = new LinkedHashMap<>();
    private final List<Recovery> recoveries = new ArrayList<>();

    ReplayRun() {
        for (String name : STEP_NAMES) {
            steps.put(name, new Step(name, "pending", ""));
        }
    }

    /**
     * Folds {@code eventsFile}.
     *
     * @throws IOException when it cannot be read
     */
    public static ReplayRun read(Path eventsFile) throws IOException {
        ReplayRun run = new ReplayRun();
        NdjsonReader.forEach(eventsFile, run::apply);
        return run;
    }

    void apply(JsonNode event) {
        String type = text(event, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "run.started" -> {
                String command = text(event, "cmd");
                if (command != null) {
                    cmd = command;
                }
                fakePos |= event.path("fake_pos").asBoolean(false);
            }
            case "run.finished" -> {
                List<String> warned = new ArrayList<>();
                event.path("warned_nodeids").forEach(n -> warned.add(n.asText()));
                for (String nodeid : warned) {
                    Test test = tests.get(nodeid);
                    if (test != null) {
                        tests.put(nodeid, new Test(nodeid, "warned", test.excType(), test.detail()));
                    }
                }
                status = event.path("cancelled").asBoolean(false) ? "cancelled" : "complete";
                outcome = text(event, "outcome");
            }
            case "step.entered" -> {
                String name = text(event, "step");
                if (name != null && steps.containsKey(name)) {
                    steps.put(name, new Step(name, "running", steps.get(name).detail()));
                }
            }
            case "step.finished" -> {
                String name = text(event, "step");
                if (name != null && steps.containsKey(name)) {
                    String state = orEmpty(text(event, "state"));
                    String settled = List.of("complete", "failed", "skipped", "repairing").contains(state) ? state
                            : "complete";
                    steps.put(name, new Step(name, settled, orEmpty(text(event, "detail"))));
                }
            }
            case "preflight.check" -> {
                String name = text(event, "name");
                if (name != null && steps.containsKey(name)) {
                    Step step = steps.get(name);
                    steps.put(name, new Step(name, step.status(),
                            orEmpty(text(event, "detail")) + " [" + orEmpty(text(event, "outcome")) + "]"));
                }
            }
            case "test.started" -> {
                String nodeid = text(event, "nodeid");
                if (nodeid != null) {
                    Test test = tests.get(nodeid);
                    tests.put(nodeid, new Test(nodeid, "running", test == null ? "" : test.excType(),
                            test == null ? "" : test.detail()));
                }
            }
            case "test.finished" -> {
                String nodeid = text(event, "nodeid");
                if (nodeid != null) {
                    String verdict = orEmpty(text(event, "outcome"));
                    String settled = switch (verdict) {
                        case "failed", "skipped", "error", "timeout" -> verdict;
                        case "repairing" -> "warned";
                        default -> "passed";
                    };
                    tests.put(nodeid, new Test(nodeid, settled, orEmpty(text(event, "exc_type")),
                            orEmpty(text(event, "detail"))));
                }
            }
            case "recovery.rung" -> {
                String nodeid = text(event, "nodeid");
                if (nodeid != null) {
                    recoveries.add(new Recovery(nodeid, orEmpty(text(event, "rung")), orEmpty(text(event, "outcome"))));
                }
            }
            default -> {
                // nothing else changes what the gate reads
            }
        }
    }

    /** The {@code cmd} the run started with: a capture's carries {@code --snapshot-update}. */
    public String cmd() {
        return cmd;
    }

    public boolean fakePos() {
        return fakePos;
    }

    /** {@code running}, {@code complete} or {@code cancelled}. */
    public String status() {
        return status;
    }

    /** The run's outcome, or null when it never finished. */
    public String outcome() {
        return outcome;
    }

    public List<Step> steps() {
        return List.copyOf(steps.values());
    }

    /** Every test by node id, in first-seen order. */
    public Map<String, Test> tests() {
        return java.util.Collections.unmodifiableMap(tests);
    }

    public List<Recovery> recoveries() {
        return List.copyOf(recoveries);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
