package com.verifone.cat.plans;

/** A partial-run selection that names nothing in the plan. A run of nothing is not a run anyone asked for. */
public final class SelectionError extends IllegalArgumentException {

    public SelectionError(String message) {
        super(message);
    }
}
