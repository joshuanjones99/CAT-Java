package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The state fold ({@code cat.state} port) and the rows drawn from it ({@code cat.web.view} port), checked as the
 * Python's {@code test_cat_state.py} and {@code test_cat_web.py} check them; the second half against the Python's own
 * committed fixture {@code d425dac7000e.events.ndjson} (lab run d425dac7000e, cancelled mid Tests Run), with the
 * expected values read from that file independently of the reducer. Evidence about this repository's reading of
 * recorded runs only.
 */
class RunStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RunState fold(String... lines) throws IOException {
        RunStateReducer reducer = new RunStateReducer();
        for (String line : lines) {
            reducer.apply(MAPPER.readTree(line));
        }
        return reducer.state();
    }

    @Test
    void theInitialStateHasSevenPendingStepsAndNothingElse() {
        RunState state = RunState.empty();
        assertEquals("not_started", state.status());
        assertEquals(RunState.STEP_NAMES, state.steps().stream().map(RunState.StepState::name).toList());
        assertTrue(state.steps().stream().allMatch(s -> "pending".equals(s.status())));
        assertEquals(0.0, state.percentComplete());
    }

    @Test
    void thePluginsRunStartedDoesNotWipeTheLaunchersValues() throws IOException {
        RunState state = fold(
                "{\"type\":\"run.started\",\"run_id\":\"r\",\"rig\":\"lab\",\"suite\":\"tests/suites/cadence\","
                        + "\"started_at\":\"2026-10-24T10:00:00Z\",\"upgrade_mode\":\"new_install\","
                        + "\"test_plans\":[\"PETROCORE-118517\"],\"plan_names\":[\"Cadence\"],\"fake_pos\":true}",
                "{\"type\":\"run.started\",\"run_id\":\"r\",\"rig\":\"unknown\",\"suite\":\"one/file.py\","
                        + "\"started_at\":\"2026-10-24T10:05:00Z\",\"upgrade_mode\":\"skip\",\"test_plans\":[],"
                        + "\"fake_pos\":false}");
        assertEquals("lab", state.rig());
        assertEquals("tests/suites/cadence", state.suite());
        assertEquals("new_install", state.upgradeMode());
        assertEquals(List.of("PETROCORE-118517"), state.testPlans());
        assertEquals(List.of("Cadence"), state.planNames());
        assertTrue(state.fakePos());
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), state.startedAt());
    }

    @Test
    void aRunningChecklistRowIsSettledAndASettledOneIsKept() throws IOException {
        RunState state = fold(
                "{\"type\":\"step.entered\",\"step\":\"Commander Configuration\"}",
                "{\"type\":\"step.check\",\"step\":\"Commander Configuration\",\"label\":\"provision\",\"outcome\":\"running\"}",
                "{\"type\":\"step.check\",\"step\":\"Commander Configuration\",\"label\":\"provision\",\"outcome\":\"passed\"}",
                "{\"type\":\"step.check\",\"step\":\"Commander Configuration\",\"label\":\"provision\",\"outcome\":\"running\"}",
                "{\"type\":\"step.check\",\"step\":\"Commander Configuration\",\"label\":\"provision\",\"outcome\":\"failed\"}");
        List<RunState.SubStep> checks = state.steps().get(3).checks();
        assertEquals(List.of("passed", "failed"), checks.stream().map(RunState.SubStep::outcome).toList());
    }

    @Test
    void aTestStartedAgainDropsItsStepsAndValidations() throws IOException {
        String node = "tests/a/PETROCORE-1-TestCase.feature::s";
        RunState state = fold(
                "{\"type\":\"test.started\",\"nodeid\":\"" + node + "\"}",
                "{\"type\":\"test.step\",\"nodeid\":\"" + node + "\",\"index\":2,\"keyword\":\"When\",\"text\":\"b\","
                        + "\"outcome\":\"passed\"}",
                "{\"type\":\"test.step\",\"nodeid\":\"" + node + "\",\"index\":1,\"keyword\":\"Given\",\"text\":\"a\","
                        + "\"outcome\":\"passed\"}",
                "{\"type\":\"test.validation\",\"nodeid\":\"" + node + "\",\"kind\":\"tlog\",\"passed\":true}");
        assertEquals(List.of("Given a", "When b"),
                state.tests().get(node).steps().stream().map(RunState.SubStep::label).toList());
        assertEquals(1, state.validations().size());

        RunState again = fold(
                "{\"type\":\"test.started\",\"nodeid\":\"" + node + "\"}",
                "{\"type\":\"test.validation\",\"nodeid\":\"" + node + "\",\"kind\":\"tlog\",\"passed\":true}",
                "{\"type\":\"test.started\",\"nodeid\":\"" + node + "\"}");
        assertTrue(again.validations().isEmpty());
        assertTrue(again.tests().get(node).steps().isEmpty());
    }

    @Test
    void severalTestsInOneCaseFileCountAsOneCaseWorstFirst() throws IOException {
        RunState state = fold(
                "{\"type\":\"plan.resolved\",\"cases\":[{\"key\":\"PETROCORE-1\",\"node\":\"t/a.py\"},"
                        + "{\"key\":\"PETROCORE-2\",\"node\":\"t/b.py\"},{\"key\":\"PETROCORE-3\",\"node\":null}]}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::one\",\"outcome\":\"passed\"}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t\\\\a.py::two\",\"outcome\":\"error\"}");
        assertEquals(Map.of("PETROCORE-1", "failed"), state.caseVerdicts());
        assertEquals(List.of("PETROCORE-2", "PETROCORE-3"), state.notRunCaseKeys());
        assertEquals(2, state.countNotRun());
        assertEquals(50.0, state.percentComplete(), 0.001);
    }

    @Test
    void theFinishMovesWarnedTestsAndALateRowIsNotCountedTwice() throws IOException {
        RunState state = fold(
                "{\"type\":\"test.finished\",\"nodeid\":\"t::a\",\"outcome\":\"passed\"}",
                "{\"type\":\"run.finished\",\"outcome\":\"passed\",\"passed\":1,\"failed\":0,\"warned\":1,"
                        + "\"warned_nodeids\":[\"t::b\"],\"elapsed\":2}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t::b\",\"outcome\":\"passed\"}");
        assertEquals(1, state.testCountPassed());
        assertEquals(1, state.testCountWarned());
        assertEquals("warned", state.tests().get("t::b").status());
        assertEquals("complete", state.status());
    }

    @Test
    void aNoisySourceDoesNotEvictAQuietStepsLines() throws IOException {
        RunStateReducer reducer = new RunStateReducer();
        reducer.apply(MAPPER.readTree("{\"type\":\"log.line\",\"level\":\"INFO\",\"logger\":\"cat\","
                + "\"message\":\"quiet\",\"step\":\"System Checks\"}"));
        for (int i = 0; i < RunState.LOG_BUFFER_LINES + 5; i++) {
            reducer.apply(MAPPER.readTree("{\"type\":\"log.line\",\"level\":\"INFO\",\"logger\":\"sim.Petro\","
                    + "\"message\":\"noise " + i + "\",\"step\":\"Start Simulators\"}"));
        }
        RunState state = reducer.state();
        assertEquals(RunState.LOG_BUFFER_LINES, state.logLines().size());
        assertEquals(List.of("quiet"), state.logLinesFor("System Checks").stream().map(RunState.LogLine::message).toList());
        assertTrue(state.logLinesFor("Start Simulators").isEmpty(), "a simulator's lines are its own source");
        assertEquals(RunState.LOG_BUFFER_LINES, state.logLinesFrom("sim.Petro").size());
    }

    @Test
    void aSummaryFoldSkipsTheLogLines() throws IOException {
        RunStateReducer reducer = new RunStateReducer(false);
        reducer.apply(MAPPER.readTree("{\"type\":\"log.line\",\"level\":\"INFO\",\"logger\":\"cat\",\"message\":\"m\"}"));
        assertTrue(reducer.state().logLines().isEmpty());
    }

    @Test
    void simulatorsAndConnectionsAreAddedThenUpdatedInPlace() throws IOException {
        RunState state = fold(
                "{\"type\":\"simulator.state\",\"name\":\"BUYPASS Host\",\"state\":\"starting\"}",
                "{\"type\":\"simulator.state\",\"name\":\"BUYPASS Host\",\"state\":\"something new\",\"owner\":\"external\"}",
                "{\"type\":\"connection.state\",\"target\":\"C18\",\"state\":\"down\",\"detail\":\"refused\"}",
                "{\"type\":\"connection.state\",\"target\":\"C18\",\"state\":\"up\"}");
        assertEquals(1, state.simulators().size());
        assertEquals("something new", state.simulators().get(0).status());
        assertEquals("external", state.simulators().get(0).owner());
        assertEquals("up", state.connections().get(0).state());
        assertEquals("idle", RunView.tone("something new"), "an unknown word is grey, not green");
    }

    // ------------------------------------------------------------------ the Python's recorded run

    private static List<JsonNode> fixture() throws IOException {
        try (InputStream in = RunStateTest.class.getResourceAsStream("/results/d425dac7000e.events.ndjson")) {
            List<JsonNode> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    out.add(MAPPER.readTree(line));
                }
            }
            return out;
        }
    }

    private static String file(String nodeid) {
        return nodeid.split("::", 2)[0].replace('\\', '/');
    }

    @Test
    void theCaseRowsFollowThePlanAndSayWhyACaseHasNoVerdict() throws IOException {
        List<JsonNode> raw = fixture();
        RunStateReducer reducer = new RunStateReducer();
        raw.forEach(reducer::apply);
        RunState state = reducer.state();
        JsonNode cases = raw.stream().filter(e -> "plan.resolved".equals(e.path("type").asText())).findFirst()
                .orElseThrow().get("cases");
        Set<String> finished = new HashSet<>();
        Set<String> started = new HashSet<>();
        for (JsonNode e : raw) {
            if ("test.finished".equals(e.path("type").asText())) {
                finished.add(file(e.get("nodeid").asText()));
            } else if ("test.started".equals(e.path("type").asText())) {
                started.add(file(e.get("nodeid").asText()));
            }
        }

        List<RunView.CaseRow> rows = RunView.caseRows(state);
        List<String> keys = new ArrayList<>();
        cases.forEach(c -> keys.add(c.get("key").asText()));
        assertEquals(keys, rows.stream().map(RunView.CaseRow::key).toList());
        Map<String, String> byKey = new HashMap<>();
        rows.forEach(r -> byKey.put(r.key(), r.status()));
        for (JsonNode c : cases) {
            String status = byKey.get(c.get("key").asText());
            JsonNode node = c.get("node");
            if (node == null || node.isNull()) {
                assertEquals("not migrated", status);
            } else if (finished.contains(node.asText())) {
                assertTrue(Set.of("passed", "failed", "warned", "skipped").contains(status), status);
            } else if (started.contains(node.asText())) {
                assertEquals("interrupted", status);
            } else {
                assertEquals("not run", status);
            }
        }
        Map<String, Integer> counts = state.caseVerdictCounts();
        assertEquals(13, counts.get("passed"));
        assertEquals(5, counts.get("failed"));
        assertEquals(13, rows.stream().filter(r -> "passed".equals(r.status())).count());
        for (RunView.CaseRow row : rows) {
            if ("failed".equals(row.status())) {
                assertFalse(row.detail().isEmpty(), row.toString());
                assertFalse(row.elapsed().isEmpty(), row.toString());
            }
        }
    }

    @Test
    void theStepsReadInterruptedOnceACancelledRunIsOver() throws IOException {
        RunStateReducer reducer = new RunStateReducer();
        fixture().forEach(reducer::apply);
        RunState state = reducer.state();
        Map<String, String> steps = new HashMap<>();
        RunView.stepRows(state).forEach(r -> steps.put(r.name(), r.status()));
        assertEquals("interrupted", steps.get("Tests Run"));
        assertEquals("complete", steps.get("System Checks"));
        assertEquals("pending", steps.get("Cleanup"));
        assertEquals("cancelled", RunView.runStatus(state));
        assertEquals("Cancelled", RunView.statusWord(state));
    }

    @Test
    void aRunWithNoPlanListsItsTestsByShortNodeid() throws IOException {
        RunState state = fold("{\"type\":\"test.finished\",\"nodeid\":"
                + "\"tests/suites/charity/test_petrocore_61334.py::test_cash_sale\",\"outcome\":\"passed\"}");
        List<RunView.CaseRow> rows = RunView.caseRows(state);
        assertEquals(1, rows.size());
        assertEquals("passed", rows.get(0).status());
        assertTrue(rows.get(0).key().contains("61334"));
        assertEquals("PETROCORE-118399", RunView.shortNodeid(
                "tests/suites/cadence/PETROCORE-118399-TestCase.py::test_petrocore_118399"));
    }

    @Test
    void theRunClockCountsFromTheStartAndStopsAtTheRecordedEnd() throws IOException {
        RunState running = fold("{\"type\":\"run.started\",\"run_id\":\"r\",\"rig\":\"lab\","
                + "\"started_at\":\"2026-10-22T09:00:00Z\"}");
        assertEquals(249.0, RunView.runSeconds(running, Instant.parse("2026-10-22T09:04:09Z")));
        assertEquals("0:04:09", RunView.clockText(249));
        assertEquals("3:00:05", RunView.clockText(3 * 3600 + 5));
        RunState done = fold("{\"type\":\"run.started\",\"run_id\":\"r\",\"rig\":\"lab\","
                + "\"started_at\":\"2026-10-22T09:00:00Z\"}",
                "{\"type\":\"run.finished\",\"outcome\":\"passed\",\"elapsed\":61.0}");
        assertEquals(61.0, RunView.runSeconds(done, Instant.parse("2026-10-22T18:00:00Z")));
        assertEquals(0.0, RunView.runSeconds(RunState.empty(), Instant.now()));
        assertNull(RunState.empty().outcome());
    }

    @Test
    void verdictCountsAreCasesWithAPlanAndTestsWithout() throws IOException {
        RunState plan = fold(
                "{\"type\":\"plan.resolved\",\"cases\":[{\"key\":\"K-1\",\"node\":\"t/a.py\"},{\"key\":\"K-2\",\"node\":null}]}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::x\",\"outcome\":\"passed\"}",
                "{\"type\":\"test.finished\",\"nodeid\":\"t/a.py::y\",\"outcome\":\"passed\"}");
        assertEquals(1, RunView.verdictCounts(plan).get("passed"));
        assertEquals(2, RunView.verdictCounts(plan).get("total"));
        RunState none = fold("{\"type\":\"test.finished\",\"nodeid\":\"t::x\",\"outcome\":\"skipped\"}");
        assertEquals(1, RunView.verdictCounts(none).get("skipped"));
        assertEquals(1, RunView.verdictCounts(none).get("total"));
    }
}
