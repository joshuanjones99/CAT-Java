package com.verifone.cat.results;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.NdjsonReader;
import com.verifone.cat.report.RunFacts;
import com.verifone.cat.runservice.RunState;
import com.verifone.cat.runservice.RunStateReducer;

/**
 * One run's {@code events.ndjson} folded, in file order, into a {@link RunState}: the Python CAT's
 * {@code cat.results.store.replay}, which folds a file through {@code cat.state}'s {@code EventReducer}.
 *
 * <p>The fold is {@link RunStateReducer}, the same one the Run tab folds a live run with, so a past run reads as it
 * read while it ran. This class keeps the narrow views Results' index and statistics read (the steps, the tests,
 * the plan's cases, the counts) over that state, and {@link #state} for the pages that draw the whole run.
 * {@code run.started}'s {@code fake_pos} and {@code test_plans}, the recovery rungs and the preflight checks also come
 * from {@link RunFacts}, the fold the report writers share.
 *
 * <p>Log lines are folded only on request: they are most of a real run's lines, and no summary reads them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests of this repository's reading of recorded runs, not of a rig.
 */
public final class RunReplay {

    /** The seven steps, in the order the launcher runs them (the Python {@code STEP_NAMES}). */
    public static final List<String> STEP_NAMES = RunState.STEP_NAMES;

    /** The statuses that count as a verdict; the statistics rate these. */
    public static final Set<String> VERDICT_STATUSES = RunState.VERDICT_STATUSES;

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
    private final RunStateReducer reducer;
    private RunState state;
    private String startedAtText = "";

    private RunReplay(Path eventsFile, boolean includeLogs) {
        this.eventsFile = eventsFile;
        this.reducer = new RunStateReducer(includeLogs);
    }

    /**
     * Folds {@code eventsFile} in file order. Log lines are skipped unless {@code includeLogs}.
     *
     * @throws IOException when the file cannot be read as UTF-8
     */
    public static RunReplay read(Path eventsFile, boolean includeLogs) throws IOException {
        RunReplay replay = new RunReplay(eventsFile, includeLogs);
        NdjsonReader.forEach(eventsFile, replay::apply);
        replay.state = replay.reducer.state();
        return replay;
    }

    /** The events file this replay read. */
    public Path eventsFile() {
        return eventsFile;
    }

    private void apply(JsonNode event) {
        facts.fold(event);
        reducer.apply(event);
        if (startedAtText.isEmpty() && "run.started".equals(text(event, "type"))) {
            startedAtText = text(event, "started_at");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** The whole run as its events said it. */
    public RunState state() {
        return state;
    }

    /** The run's rig as the launcher recorded it, "" when no run.started was read. */
    public String rig() {
        return state.rig();
    }

    /** The first run.started's time, or "" when none was read. */
    public String startedAt() {
        return startedAtText;
    }

    public String cmd() {
        return state.cmd();
    }

    public String gitHead() {
        return state.gitHead();
    }

    public List<String> planNames() {
        return state.planNames();
    }

    public boolean fakePos() {
        return facts.fakePos() || state.fakePos();
    }

    public List<String> testPlans() {
        return facts.testPlans();
    }

    /** True once a run.finished has been read. */
    public boolean finished() {
        return state.outcome() != null;
    }

    public boolean cancelled() {
        return "cancelled".equals(state.status());
    }

    /** The run's outcome word, or null when no run.finished was read. */
    public String outcome() {
        return state.outcome();
    }

    /** run.finished's count once it is read, the live count before. */
    public int passed() {
        return state.testCountPassed();
    }

    public int failed() {
        return state.testCountFailed();
    }

    public int warned() {
        return state.testCountWarned();
    }

    /** The steps in the launcher's order. */
    public List<StepState> steps() {
        List<StepState> out = new ArrayList<>();
        for (RunState.StepState step : state.steps()) {
            out.add(new StepState(step.name(), step.status(), step.elapsed()));
        }
        return out;
    }

    /** Every test that has an event, in the order its first event arrived. */
    public List<TestState> tests() {
        List<TestState> out = new ArrayList<>();
        for (RunState.TestState test : state.tests().values()) {
            out.add(new TestState(test.nodeid(), test.status(), test.duration(), test.excType()));
        }
        return out;
    }

    /** The plan's cases in plan order. Empty when no plan was resolved. */
    public List<PlanCase> cases() {
        List<PlanCase> out = new ArrayList<>();
        for (RunState.CaseState planned : state.cases().values()) {
            out.add(new PlanCase(planned.key(), planned.summary(), planned.node()));
        }
        return out;
    }

    /** Every log line read, when the replay was asked for logs; else empty. */
    public List<LogLine> logLines() {
        List<LogLine> out = new ArrayList<>();
        for (RunState.LogLine line : state.logLines()) {
            out.add(new LogLine(line.level(), line.logger(), line.message()));
        }
        return out;
    }

    /** The run's wall time: the steps' sum when the launcher recorded them, else run.finished's. */
    public double runElapsed() {
        double steps = state.steps().stream().mapToDouble(RunState.StepState::elapsed).sum();
        return steps > 0 ? steps : state.elapsed();
    }

    /** Plan case key to its single verdict, aggregated over its file's tests, worst first. */
    public Map<String, String> caseVerdicts() {
        return state.caseVerdicts();
    }

    /** Plan cases with no verdict (unmigrated, or never reached), in plan order. */
    public List<PlanCase> notRunCases() {
        List<PlanCase> out = new ArrayList<>();
        for (String key : state.notRunCaseKeys()) {
            RunState.CaseState planned = state.cases().get(key);
            out.add(new PlanCase(planned.key(), planned.summary(), planned.node()));
        }
        return out;
    }

    /** The recovery rungs and preflight checks, as the report writers fold them. */
    public RunFacts facts() {
        return facts;
    }

    /** The repo-relative file part of a pytest nodeid, with forward slashes. */
    static String nodeFile(String nodeid) {
        return RunState.nodeFile(nodeid);
    }
}
