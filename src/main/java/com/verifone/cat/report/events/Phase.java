package com.verifone.cat.report.events;

import com.fasterxml.jackson.annotation.JsonValue;

/** The phase of a preflight check: verify, repair, or reverify. */
public enum Phase {
    VERIFY("verify"),
    REPAIR("repair"),
    REVERIFY("reverify");

    private final String wire;

    Phase(String wire) {
        this.wire = wire;
    }

    /** The lowercase value written to NDJSON. */
    @JsonValue
    public String wire() {
        return wire;
    }
}
