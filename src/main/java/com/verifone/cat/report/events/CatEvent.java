package com.verifone.cat.report.events;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Every NDJSON event body the Python CAT's {@code cat.report.events} defines, as immutable records.
 *
 * <p>A record holds the event's own fields, under the snake_case names the NDJSON carries; the
 * {@link Envelope} ({@code v}, {@code at}, {@code seq}, {@code worker}) and the {@code type} are
 * added by {@link EventEmitter}. Defaults the Python models declare are applied in each compact
 * constructor, so a record built with {@code null} lists or strings reads as the Python default
 * did. Ranges the models enforce (for example {@code ge=1}) throw {@link IllegalArgumentException}
 * at construction, as pydantic's validation did.
 *
 * <p>Additive-only schema: a field added after a recording was written must be defaulted, so an
 * old {@code events.ndjson} still reads.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the schema is ported from the Python models; no run has read or
 * written these records through this port yet.
 */
public interface CatEvent {

    /** The {@code type} this record is written as. */
    EventType type();

    // ---------------------------------------------------------------- descriptors (not events)

    /** A simulator in the run manifest. Not an event itself; {@link RunManifest} carries it. */
    record SimulatorDescriptor(
            @JsonProperty("name") String name,
            @JsonProperty("kind") String kind,
            @JsonProperty("status") String status) {}

    /** A connection target in the run manifest. */
    record ConnectionDescriptor(
            @JsonProperty("target") String target,
            @JsonProperty("probe_type") String probeType,
            @JsonProperty("expected") String expected) {}

    /**
     * One Jira test case of a run's plan. {@code node} is {@code null} for a case with no
     * migrated test file, which is what keeps a 39-case plan from shrinking to its migrated 30.
     */
    record PlanCaseDescriptor(
            @JsonProperty("key") String key,
            @JsonProperty("summary") String summary,
            @JsonProperty("node") String node) {

        public PlanCaseDescriptor {
            requireText("key", key);
            summary = orEmpty(summary);
        }
    }

    // ------------------------------------------------------------------------------ run events

    /** The whole plan's membership, resolved before the session starts. Not a pytest event. */
    record PlanResolved(@JsonProperty("cases") List<PlanCaseDescriptor> cases) implements CatEvent {

        public PlanResolved {
            cases = listOrEmpty(cases);
        }

        @Override
        public EventType type() {
            return EventType.PLAN_RESOLVED;
        }
    }

    /** A simulator CAT adopted, started, stopped or failed to start (D9/D10). */
    record SimulatorState(
            @JsonProperty("name") String name,
            @JsonProperty("state") String state,
            @JsonProperty("kind") String kind,
            @JsonProperty("owner") String owner,
            @JsonProperty("bind") String bind,
            @JsonProperty("detail") String detail) implements CatEvent {

        public SimulatorState {
            requireText("name", name);
            requireText("state", state);
            kind = orDefault(kind, "supervised");
            owner = orDefault(owner, "CAT");
            bind = orEmpty(bind);
            detail = orEmpty(detail);
        }

        /** The Python defaults for everything but the name and the state. */
        public SimulatorState(String name, String state) {
            this(name, state, null, null, null, null);
        }

        @Override
        public EventType type() {
            return EventType.SIMULATOR_STATE;
        }
    }

    /** The first event of a run. A launcher's restatement sets {@code restated}. */
    record RunStarted(
            @JsonProperty("run_id") String runId,
            @JsonProperty("rig") String rig,
            @JsonProperty("suite") String suite,
            @JsonProperty("cmd") String cmd,
            @JsonProperty("workers") int workers,
            @JsonProperty("started_at") @JsonSerialize(using = IsoInstantSerializer.class)
                    Instant startedAt,
            @JsonProperty("cat_version") String catVersion,
            @JsonProperty("upgrade_mode") String upgradeMode,
            @JsonProperty("test_plans") List<String> testPlans,
            @JsonProperty("plan_names") List<String> planNames,
            @JsonProperty("fake_pos") boolean fakePos,
            @JsonProperty("git_head") String gitHead,
            @JsonProperty("restated") boolean restated) implements CatEvent {

        public RunStarted {
            requireText("run_id", runId);
            requireText("rig", rig);
            requireText("suite", suite);
            requireText("cmd", cmd);
            requireAtLeast("workers", workers, 1);
            requireNonNull("started_at", startedAt);
            requireText("cat_version", catVersion);
            upgradeMode = orDefault(upgradeMode, "skip");
            testPlans = listOrEmpty(testPlans);
            planNames = listOrEmpty(planNames);
            gitHead = orEmpty(gitHead);
        }

        @Override
        public EventType type() {
            return EventType.RUN_STARTED;
        }
    }

    /**
     * Declares a run's simulators and connections. Emitted by no production code in the Python
     * CAT (only its reducer reads it); kept as schema, so the port keeps it too.
     */
    record RunManifest(
            @JsonProperty("simulators") List<SimulatorDescriptor> simulators,
            @JsonProperty("connections") List<ConnectionDescriptor> connections,
            @JsonProperty("expected_tests") Integer expectedTests) implements CatEvent {

        public RunManifest {
            simulators = listOrEmpty(simulators);
            connections = listOrEmpty(connections);
        }

        @Override
        public EventType type() {
            return EventType.RUN_MANIFEST;
        }
    }

    /** The last event of a run. {@code cancelled} means the operator stopped it. */
    record RunFinished(
            @JsonProperty("run_id") String runId,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("passed") int passed,
            @JsonProperty("failed") int failed,
            @JsonProperty("warned") int warned,
            @JsonProperty("warned_nodeids") List<String> warnedNodeids,
            @JsonProperty("cancelled") boolean cancelled,
            @JsonProperty("elapsed") double elapsed,
            @JsonProperty("dropped_events") long droppedEvents) implements CatEvent {

        public RunFinished {
            requireText("run_id", runId);
            requireNonNull("outcome", outcome);
            requireAtLeast("passed", passed, 0);
            requireAtLeast("failed", failed, 0);
            requireAtLeast("warned", warned, 0);
            requireAtLeast("elapsed", elapsed, 0);
            requireAtLeast("dropped_events", droppedEvents, 0);
            warnedNodeids = listOrEmpty(warnedNodeids);
        }

        @Override
        public EventType type() {
            return EventType.RUN_FINISHED;
        }
    }

    // ----------------------------------------------------------------------------- step events

    /** A step ({@code System Checks}, {@code Tests Run}, ...) begins. */
    record StepEntered(@JsonProperty("step") String step) implements CatEvent {

        public StepEntered {
            requireText("step", step);
        }

        @Override
        public EventType type() {
            return EventType.STEP_ENTERED;
        }
    }

    /** Progress within a step; {@code total} is {@code null} while it is indeterminate. */
    record StepProgress(
            @JsonProperty("step") String step,
            @JsonProperty("done") int done,
            @JsonProperty("total") Integer total,
            @JsonProperty("detail") String detail) implements CatEvent {

        public StepProgress {
            requireText("step", step);
            requireAtLeast("done", done, 0);
            detail = orEmpty(detail);
        }

        @Override
        public EventType type() {
            return EventType.STEP_PROGRESS;
        }
    }

    /** A step ends: {@code complete}, {@code failed}, {@code skipped} or {@code repairing}. */
    record StepFinished(
            @JsonProperty("step") String step,
            @JsonProperty("state") String state,
            @JsonProperty("elapsed") double elapsed,
            @JsonProperty("detail") String detail) implements CatEvent {

        public StepFinished {
            requireText("step", step);
            requireText("state", state);
            requireAtLeast("elapsed", elapsed, 0);
            detail = orEmpty(detail);
        }

        @Override
        public EventType type() {
            return EventType.STEP_FINISHED;
        }
    }

    /** One sub-task of a step, reported as it starts ({@code running}) and as it ends. */
    record StepCheck(
            @JsonProperty("step") String step,
            @JsonProperty("label") String label,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("detail") String detail,
            @JsonProperty("elapsed") double elapsed) implements CatEvent {

        public StepCheck {
            requireText("step", step);
            requireText("label", label);
            requireText("outcome", outcome);
            detail = orEmpty(detail);
            requireAtLeast("elapsed", elapsed, 0);
        }

        @Override
        public EventType type() {
            return EventType.STEP_CHECK;
        }
    }

    // ------------------------------------------------------------------------------ test events

    /** The number of tests pytest collected. */
    record TestCollected(@JsonProperty("count") int count) implements CatEvent {

        public TestCollected {
            requireAtLeast("count", count, 0);
        }

        @Override
        public EventType type() {
            return EventType.TEST_COLLECTED;
        }
    }

    /** A test begins. The envelope's {@code worker} names the xdist worker that ran it. */
    record TestStarted(@JsonProperty("nodeid") String nodeid) implements CatEvent {

        public TestStarted {
            requireText("nodeid", nodeid);
        }

        @Override
        public EventType type() {
            return EventType.TEST_STARTED;
        }
    }

    /** One {@code .feature} step of a running case, as it starts and as it ends. */
    record TestStep(
            @JsonProperty("nodeid") String nodeid,
            @JsonProperty("index") int index,
            @JsonProperty("keyword") String keyword,
            @JsonProperty("text") String text,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("duration") double duration,
            @JsonProperty("detail") String detail) implements CatEvent {

        public TestStep {
            requireText("nodeid", nodeid);
            requireAtLeast("index", index, 1);
            keyword = orEmpty(keyword);
            requireText("text", text);
            requireText("outcome", outcome);
            requireAtLeast("duration", duration, 0);
            detail = orEmpty(detail);
        }

        @Override
        public EventType type() {
            return EventType.TEST_STEP;
        }
    }

    /** One validation's verdict (receipt, TLog, report, ...) with its diff. */
    record TestValidation(
            @JsonProperty("nodeid") String nodeid,
            @JsonProperty("case") String caseKey,
            @JsonProperty("kind") String kind,
            @JsonProperty("name") String name,
            @JsonProperty("passed") boolean passed,
            @JsonProperty("diff") String diff,
            @JsonProperty("expected") String expected,
            @JsonProperty("actual") String actual) implements CatEvent {

        public TestValidation {
            nodeid = orEmpty(nodeid);
            caseKey = orEmpty(caseKey);
            requireText("kind", kind);
            name = orEmpty(name);
            diff = orEmpty(diff);
            expected = orEmpty(expected);
            actual = orEmpty(actual);
        }

        @Override
        public EventType type() {
            return EventType.TEST_VALIDATION;
        }
    }

    /**
     * A test's verdict. {@code warnings} is always 0 from pytest's own hook: an item's warnings are
     * flushed only after its report, so the split is computed at session end
     * ({@link RunFinished#warnedNodeids()}).
     */
    record TestFinished(
            @JsonProperty("nodeid") String nodeid,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("duration") double duration,
            @JsonProperty("warnings") int warnings,
            @JsonProperty("exc_type") String excType,
            @JsonProperty("detail") String detail) implements CatEvent {

        public TestFinished {
            requireText("nodeid", nodeid);
            requireNonNull("outcome", outcome);
            requireAtLeast("duration", duration, 0);
            requireAtLeast("warnings", warnings, 0);
            excType = orEmpty(excType);
            detail = orEmpty(detail);
        }

        @Override
        public EventType type() {
            return EventType.TEST_FINISHED;
        }
    }

    // ------------------------------------------------------- preflight, recovery, connections

    /** One preflight check in its verify, repair or reverify phase. */
    record PreflightCheck(
            @JsonProperty("name") String name,
            @JsonProperty("phase") Phase phase,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("detail") String detail,
            @JsonProperty("elapsed") double elapsed) implements CatEvent {

        public PreflightCheck {
            requireText("name", name);
            requireNonNull("phase", phase);
            requireNonNull("outcome", outcome);
            detail = orEmpty(detail);
            requireAtLeast("elapsed", elapsed, 0);
        }

        @Override
        public EventType type() {
            return EventType.PREFLIGHT_CHECK;
        }
    }

    /** A recovery action applied after a test ({@code rung} 0 is the least disruptive). */
    record RecoveryRung(
            @JsonProperty("nodeid") String nodeid,
            @JsonProperty("rung") int rung,
            @JsonProperty("outcome") Outcome outcome) implements CatEvent {

        public RecoveryRung {
            requireText("nodeid", nodeid);
            requireAtLeast("rung", rung, 0);
            requireNonNull("outcome", outcome);
        }

        @Override
        public EventType type() {
            return EventType.RECOVERY_RUNG;
        }
    }

    /** A connection probed: {@code up}, {@code down} or {@code unknown}. */
    record ConnectionState(
            @JsonProperty("target") String target,
            @JsonProperty("state") String state,
            @JsonProperty("detail") String detail,
            @JsonProperty("model") String model) implements CatEvent {

        public ConnectionState {
            requireText("target", target);
            requireText("state", state);
            detail = orEmpty(detail);
        }

        @Override
        public EventType type() {
            return EventType.CONNECTION_STATE;
        }
    }

    // ------------------------------------------------------------------------------ log lines

    /** One log record kept on the stream. {@code step} is empty for a line that belongs to no step. */
    record LogLine(
            @JsonProperty("level") String level,
            @JsonProperty("logger") String logger,
            @JsonProperty("message") String message,
            @JsonProperty("step") String step) implements CatEvent {

        public LogLine {
            requireText("level", level);
            requireText("logger", logger);
            requireNonNull("message", message);
            step = orEmpty(step);
        }

        @Override
        public EventType type() {
            return EventType.LOG_LINE;
        }
    }

    // ---------------------------------------------------------------------------- shared checks

    private static void requireText(String name, String value) {
        requireNonNull(name, value);
    }

    private static void requireNonNull(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static void requireAtLeast(String name, long value, long min) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be >= " + min + ", was " + value);
        }
    }

    private static void requireAtLeast(String name, double value, double min) {
        if (Double.isNaN(value) || value < min) {
            throw new IllegalArgumentException(name + " must be >= " + min + ", was " + value);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static <T> List<T> listOrEmpty(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
