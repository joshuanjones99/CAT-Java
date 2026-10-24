package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;

/** The field reads the configuration handlers share: PATS's {@code _require} and its empty-means-none reads. */
final class ActionFields {

    private ActionFields() {
    }

    /**
     * PATS's {@code _require}: the field's first non-empty value, or {@code ""} when it is present but empty.
     *
     * @throws PatsStepError when the field is absent (PATS throws there)
     */
    static String require(RunnableAction action, String name) {
        String value = action.raw(name);
        if (value == null) {
            throw new PatsStepError(action.describe() + " has no " + name + " field (PATS would throw here)");
        }
        return value;
    }

    /** {@code value}, or {@code null} when it is empty: PATS's {@code x or None}. */
    static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
