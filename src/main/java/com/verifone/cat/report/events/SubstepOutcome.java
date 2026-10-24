package com.verifone.cat.report.events;

import java.util.List;

/**
 * What a sub-task ({@code step.check}) or a case's own step ({@code test.step}) came to.
 *
 * <p>{@code running} is replaced by one of the others when it ends; {@code warned} is a check
 * that failed without failing its step, or a PATS validation that failed while its case carried
 * on. Kept as strings on the records, as the Python models keep them: the wire vocabulary is the
 * contract, and the Python models do not validate it.
 */
public final class SubstepOutcome {

    public static final String RUNNING = "running";
    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String WARNED = "warned";
    public static final String SKIPPED = "skipped";

    /** Every outcome, in the Python tuple's order. */
    public static final List<String> ALL = List.of(RUNNING, PASSED, FAILED, WARNED, SKIPPED);

    private SubstepOutcome() {}
}
