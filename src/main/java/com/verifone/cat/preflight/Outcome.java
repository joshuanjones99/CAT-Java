package com.verifone.cat.preflight;

/**
 * What a check reported. Ported from the Python CAT's {@code petrotest.harness.base.Outcome}.
 *
 * <p>PASS and FAIL are the rig and its workstation. ERROR and TIMEOUT are the probe. The
 * split matters: a probe that threw is not a rig fault, and collapsing the two sends an
 * engineer to the drawer sensor of a rig whose drawer is fine. REPAIRED and UNREPAIRABLE
 * separate "repair did something" from "repair declined, by design".
 */
public enum Outcome {
    PASS("pass"),
    FAIL("fail"),
    ERROR("error"),
    TIMEOUT("timeout"),
    REPAIRED("repaired"),
    UNREPAIRABLE("unrepairable");

    private final String value;

    Outcome(String value) {
        this.value = value;
    }

    /** The lower-case word the Python CAT prints and the run summary uses. */
    public String value() {
        return value;
    }

    /** PASS and REPAIRED both leave the rig testable. */
    public boolean isGood() {
        return this == PASS || this == REPAIRED;
    }
}
