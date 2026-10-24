package com.verifone.cat.pats.runner;

/**
 * The case's verdict when only its validations failed: every action ran as PATS runs it, and the
 * message lists every failed validation. Ported from the Python {@code AssertionError} that
 * {@code CaseRun.finish} raises with {@code actions_completed} set. The flag is kept as
 * {@link #actionsCompleted()}: a teardown that cleans up after a failure must leave the rig as the
 * case left it, since every action ran.
 */
public class ValidationsFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ValidationsFailed(String message) {
        super(message);
    }

    /** Always true here: this verdict is only raised after every action has run. */
    public boolean actionsCompleted() {
        return true;
    }
}
