package com.verifone.cat.report.events;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * An event's outcome, as the Python CAT's {@code cat.report.events.Outcome} spells it on the wire.
 *
 * <p>Why six outcomes: the response differs. PASSED and FAILED are rig faults; ERROR and TIMEOUT
 * are probe or harness faults. REPAIRING distinguishes "repair applied" from "repair declined".
 */
public enum Outcome {
    PASSED("passed"),
    FAILED("failed"),
    SKIPPED("skipped"),
    ERROR("error"),
    TIMEOUT("timeout"),
    REPAIRING("repairing");

    private final String wire;

    Outcome(String wire) {
        this.wire = wire;
    }

    /** The lowercase value written to NDJSON and read by the reports. */
    @JsonValue
    public String wire() {
        return wire;
    }
}
