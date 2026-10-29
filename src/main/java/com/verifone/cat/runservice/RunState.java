package com.verifone.cat.runservice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.launch.LaunchStepNames;

/**
 * The whole of one run as its events say it, at one moment: the Python CAT's {@code cat.state.models.RunState}.
 *
 * <p>Immutable: {@link RunStateReducer} folds the events and hands out a new one on each read. Every page that
 * draws a run (the Run tab live, a Results run after the fact) draws it from one of these, as the Python's
 * {@code cat.web.components} take a {@code RunState} and never the service.
 *
 * <p>The derived counts are the Python's, in the Python's units: a plan case is one row, joined to its tests
 * through its file ({@link CaseState#node}); with no plan resolved they fall back to tests. {@link #caseVerdicts}
 * aggregates worst first, {@link #notRunCaseKeys} are the plan's cases with no verdict in plan order, and
 * {@link #percentComplete} divides finished cases by runnable ones.
 *
 * <p>Deviation: the Python keeps the log buffers as {@code deque}s inside a frozen dataclass; here they are copied
 * into this snapshot when it is made, so a reader never sees a buffer move under it.
 *
 * @param runId          the run's id, "" before {@code run.started}
 * @param rig            the rig's name; a plugin's {@code unknown} never overwrites a known one
 * @param suite          the suite folders the run ran
 * @param cmd            the recorded command line
 * @param workers        the recorded worker count
 * @param startedAt      the first {@code run.started}'s time, or null
 * @param catVersion     the recorded CAT version
 * @param upgradeMode    the upgrade mode the run was asked for
 * @param fakePos        sticky: true once any {@code run.started} said the run was on the fake POS
 * @param gitHead        the checkout's commit, "" when unrecorded
 * @param status         {@code not_started}, {@code running}, {@code complete} or {@code cancelled}
 * @param testPlans      the plan keys chosen
 * @param planNames      how each plan read when the run started (may be empty)
 * @param cases          the plan's cases by key, in plan order, the unmigrated ones included
 * @param steps          the seven steps, in order
 * @param tests          every test by node id, in the order its first event came
 * @param testCountTotal the collected count, or null when not known
 * @param testCountPassed tests that passed (run.finished's count once it is read)
 * @param testCountFailed tests that failed
 * @param testCountWarned tests that passed with warnings
 * @param simulators     the simulators' rows, in the order they were first reported
 * @param connections    the connection targets' rows
 * @param logLines       the run's newest log lines, at most {@link #LOG_BUFFER_LINES}
 * @param sourceLogLines the same lines by source ({@code step:<name>} or a {@code sim.} logger), each bounded alike
 * @param elapsed        run.finished's elapsed seconds
 * @param outcome        run.finished's outcome word, or null before it
 * @param recoveries     every recovery rung, in order
 * @param validations    every validation's verdict, in the order checked
 */
public record RunState(
        String runId,
        String rig,
        String suite,
        String cmd,
        int workers,
        Instant startedAt,
        String catVersion,
        String upgradeMode,
        boolean fakePos,
        String gitHead,
        String status,
        List<String> testPlans,
        List<String> planNames,
        Map<String, CaseState> cases,
        List<StepState> steps,
        Map<String, TestState> tests,
        Integer testCountTotal,
        int testCountPassed,
        int testCountFailed,
        int testCountWarned,
        List<SimulatorState> simulators,
        List<ConnectionState> connections,
        List<LogLine> logLines,
        Map<String, List<LogLine>> sourceLogLines,
        double elapsed,
        String outcome,
        List<Recovery> recoveries,
        List<Validation> validations) {

    /** The seven steps, in order (the Python {@code STEP_NAMES}). */
    public static final List<String> STEP_NAMES = LaunchStepNames.ALL;

    /** Lines each log buffer holds: the run-wide one, and each source's own (the Python {@code LOG_BUFFER_LINES}). */
    public static final int LOG_BUFFER_LINES = 10_000;

    /** How a simulator's logger is named ({@code sim.<name>}). */
    public static final String SIM_LOGGER_PREFIX = "sim.";

    /** Test statuses that mean the test reached a verdict; {@code skipped} is one. */
    public static final Set<String> VERDICT_STATUSES =
            Set.of("passed", "failed", "warned", "skipped", "error", "timeout");

    /** Statuses that make a whole case failed. */
    static final Set<String> FAILING_STATUSES = Set.of("failed", "error", "timeout");

    public RunState {
        testPlans = List.copyOf(testPlans);
        planNames = List.copyOf(planNames);
        cases = Collections.unmodifiableMap(new LinkedHashMap<>(cases));
        steps = List.copyOf(steps);
        tests = Collections.unmodifiableMap(new LinkedHashMap<>(tests));
        simulators = List.copyOf(simulators);
        connections = List.copyOf(connections);
        logLines = List.copyOf(logLines);
        Map<String, List<LogLine>> sources = new LinkedHashMap<>();
        sourceLogLines.forEach((key, lines) -> sources.put(key, List.copyOf(lines)));
        sourceLogLines = Collections.unmodifiableMap(sources);
        recoveries = List.copyOf(recoveries);
        validations = List.copyOf(validations);
    }

    /** A run before any event: seven pending steps and nothing else. */
    public static RunState empty() {
        return new RunStateReducer().state();
    }

    /** The {@code source_log_lines} key of a step: prefixed, so no logger can collide. */
    public static String stepKey(String step) {
        return "step:" + step;
    }

    // ------------------------------------------------------------------ parts

    /**
     * One row of a step's checklist ({@code step.check}) or of a test case's steps ({@code test.step}).
     *
     * @param label   what it is: a sub-task's name, or the step's text with its keyword
     * @param outcome running / passed / failed / warned / skipped
     * @param detail  what it found, or why it failed, warned or was skipped
     * @param elapsed seconds it took; 0 while running or when not timed
     * @param index   a case step's position in its scenario, from 1; 0 for a sub-task
     */
    public record SubStep(String label, String outcome, String detail, double elapsed, int index) {}

    /** One validation's verdict ({@code test.validation}). {@code at} may be null. */
    public record Validation(String nodeid, String caseKey, String kind, String name, boolean passed, String diff,
            String expected, String actual, Instant at) {}

    /**
     * One of the seven steps.
     *
     * @param status        pending, running, complete, failed, skipped or repairing
     * @param progressTotal null while indeterminate
     * @param checks        its sub-tasks so far, in the order they began
     */
    public record StepState(String name, String status, int progressDone, Integer progressTotal,
            String progressDetail, double elapsed, String detail, List<SubStep> checks) {

        public StepState {
            checks = List.copyOf(checks);
        }

        static StepState pending(String name) {
            return new StepState(name, "pending", 0, null, "", 0.0, "", List.of());
        }
    }

    /**
     * One test.
     *
     * @param status   pending, running, passed, failed, skipped, error, timeout or warned
     * @param worker   the worker that ran it, or null
     * @param outcome  the {@code test.finished} outcome word, or null before it
     * @param steps    its {@code .feature} steps so far, by position
     */
    public record TestState(String nodeid, String status, String worker, String outcome, double duration,
            int warnings, String excType, String detail, List<SubStep> steps) {

        public TestState {
            steps = List.copyOf(steps);
        }

        static TestState of(String nodeid, String status) {
            return new TestState(nodeid, status, null, null, 0.0, 0, "", "", List.of());
        }
    }

    /** A simulator's row. {@code owner} is "" when unknown. */
    public record SimulatorState(String name, String kind, String status, String detail, String owner, String bind) {}

    /** A connection target's row. {@code model} may be null. */
    public record ConnectionState(String target, String probeType, String state, String detail, String model) {}

    /** One log line. {@code step} is "" for a line that belongs to the run, not to one step. */
    public record LogLine(Instant at, String level, String logger, String message, String step) {}

    /** One Jira case of the plan; {@code node} is null for a case with no migrated test. */
    public record CaseState(String key, String summary, String node) {

        /** Whether the case resolved to a real test file. */
        public boolean runnable() {
            return node != null;
        }
    }

    /** One trip up the recovery ladder: the test, the rung and its outcome word. */
    public record Recovery(String nodeid, int rung, String outcome) {}

    // ------------------------------------------------------------------ derived counts

    /** The repo-relative file part of a node id, with forward slashes. */
    public static String nodeFile(String nodeid) {
        int cut = nodeid.indexOf("::");
        return (cut < 0 ? nodeid : nodeid.substring(0, cut)).replace('\\', '/');
    }

    /** One verdict for a file's worth of tests, worst first. */
    static String aggregateVerdict(Set<String> statuses) {
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

    /** Plan cases that resolved to a test file: the percentage's denominator; the collected count with no plan. */
    public int countRunnable() {
        if (!cases.isEmpty()) {
            return (int) cases.values().stream().filter(CaseState::runnable).count();
        }
        return testCountTotal == null ? 0 : testCountTotal;
    }

    /** Tests that reached a verdict (test units). */
    public int countFinished() {
        return (int) tests.values().stream().filter(t -> VERDICT_STATUSES.contains(t.status())).count();
    }

    /** Distinct plan cases with a finished test: the numerator; tests with no plan. */
    public int countCasesFinished() {
        if (cases.isEmpty()) {
            return countFinished();
        }
        return caseVerdicts().size();
    }

    /** Plan case key to its one verdict over its file's tests; a case with no verdict yet is absent. */
    public Map<String, String> caseVerdicts() {
        Map<String, Set<String>> byFile = new HashMap<>();
        for (TestState test : tests.values()) {
            if (VERDICT_STATUSES.contains(test.status())) {
                byFile.computeIfAbsent(nodeFile(test.nodeid()), k -> new LinkedHashSet<>()).add(test.status());
            }
        }
        Map<String, String> verdicts = new LinkedHashMap<>();
        for (CaseState planned : cases.values()) {
            if (planned.node() == null) {
                continue;
            }
            Set<String> statuses = byFile.get(planned.node());
            if (statuses != null && !statuses.isEmpty()) {
                verdicts.put(planned.key(), aggregateVerdict(statuses));
            }
        }
        return verdicts;
    }

    /** How many cases ended passed / failed / warned / skipped; a missing key reads 0. */
    public Map<String, Integer> caseVerdictCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String verdict : caseVerdicts().values()) {
            counts.merge(verdict, 1, Integer::sum);
        }
        return counts;
    }

    /** The plan case keys with no verdict (unmigrated, or never reached), in plan order. */
    public List<String> notRunCaseKeys() {
        if (cases.isEmpty()) {
            return List.of();
        }
        Set<String> finished = caseVerdicts().keySet();
        List<String> out = new ArrayList<>();
        for (String key : cases.keySet()) {
            if (!finished.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Plan cases that did not execute: unmigrated, or never reached. 0 with no plan. */
    public int countNotRun() {
        if (cases.isEmpty()) {
            return 0;
        }
        return Math.max(0, cases.size() - countCasesFinished());
    }

    /** Finished cases over runnable cases, times 100; 0 with nothing runnable. */
    public double percentComplete() {
        int runnable = countRunnable();
        if (runnable <= 0) {
            return 0.0;
        }
        return countCasesFinished() * 100.0 / runnable;
    }

    /** One step's own buffered lines, simulator output excluded. */
    public List<LogLine> logLinesFor(String step) {
        List<LogLine> buffer = sourceLogLines.get(stepKey(step));
        if (buffer != null) {
            return buffer;
        }
        List<LogLine> out = new ArrayList<>();
        for (LogLine line : logLines) {
            if (line.step().equals(step) && !line.logger().startsWith(SIM_LOGGER_PREFIX)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The buffered lines one logger wrote: how a simulator's view is read. */
    public List<LogLine> logLinesFrom(String logger) {
        List<LogLine> buffer = sourceLogLines.get(logger);
        if (buffer != null) {
            return buffer;
        }
        List<LogLine> out = new ArrayList<>();
        for (LogLine line : logLines) {
            if (line.logger().equals(logger)) {
                out.add(line);
            }
        }
        return out;
    }
}
