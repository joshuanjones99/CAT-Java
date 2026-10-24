package com.verifone.cat.report.events;

/**
 * The {@code type} field of each NDJSON line, one per event record in {@link CatEvent}.
 *
 * <p>The Python CAT derives each name from its class name (CamelCase to dotted snake case, see
 * {@code cat.telemetry.sink._get_event_type_name}). Readers key on these strings, so they are
 * written out here, not computed: a renamed class must not silently rename the wire.
 */
public enum EventType {
    RUN_STARTED("run.started"),
    RUN_FINISHED("run.finished"),
    RUN_MANIFEST("run.manifest"),
    PLAN_RESOLVED("plan.resolved"),
    SIMULATOR_STATE("simulator.state"),
    STEP_ENTERED("step.entered"),
    STEP_PROGRESS("step.progress"),
    STEP_FINISHED("step.finished"),
    STEP_CHECK("step.check"),
    TEST_COLLECTED("test.collected"),
    TEST_STARTED("test.started"),
    TEST_STEP("test.step"),
    TEST_VALIDATION("test.validation"),
    TEST_FINISHED("test.finished"),
    PREFLIGHT_CHECK("preflight.check"),
    RECOVERY_RUNG("recovery.rung"),
    CONNECTION_STATE("connection.state"),
    LOG_LINE("log.line");

    private final String wire;

    EventType(String wire) {
        this.wire = wire;
    }

    /** The value of the {@code type} field on the line. */
    public String wire() {
        return wire;
    }
}
