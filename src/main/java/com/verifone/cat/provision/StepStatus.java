package com.verifone.cat.provision;

/**
 * What a checklist step did. A port of {@code petrotest.provision.checklist.StepStatus}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public enum StepStatus {
    /** The step performed its work. */
    DONE("done"),
    /** An idempotent no-op: it was already in the wanted state. */
    ALREADY("already"),
    /** Not attempted, with a reason. Never silent. */
    SKIPPED("skipped"),
    FAILED("failed");

    private final String wire;

    StepStatus(String wire) {
        this.wire = wire;
    }

    /** The lower-case name the run report prints. */
    public String wire() {
        return wire;
    }
}
