package com.verifone.cat.pats.runner.handlers.devices;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;

import java.time.Duration;
import java.util.Locale;

/**
 * The field reads the sale and devices handlers share, as PATS's {@code _require}, {@code _device} and the
 * case's {@code budget}. Shared by the two families, so neither reaches into the other.
 */
public final class ActionFields {

    private ActionFields() {
    }

    /**
     * PATS's {@code _require}: the field's raw value, or a step failure when the field is absent. An empty field
     * is returned as empty, as PATS's {@code raw} does.
     *
     * @throws PatsStepError when the field is absent
     */
    public static String require(RunnableAction action, String name) {
        String value = action.raw(name);
        if (value == null) {
            throw new PatsStepError(action.describe() + " has no " + name + " field (PATS would throw here)");
        }
        return value;
    }

    /** The {@code DEVICE} field, upper-cased, POS by default ({@code _device}). */
    public static String device(RunnableAction action) {
        return action.get("DEVICE", "POS").toUpperCase(Locale.ROOT);
    }

    /**
     * A bounded wait's ceiling: {@code base} plus whatever the case's preceding SLEEPs carried, which the carry
     * spends ({@link CaseRun#budget}).
     */
    public static Duration budget(CaseRun run, Duration base) {
        return Duration.ofMillis(Math.round(run.budget(base.toMillis() / 1000.0) * 1000.0));
    }
}
