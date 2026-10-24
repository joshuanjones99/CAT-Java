package com.verifone.cat.setup;

import java.util.Objects;

import com.verifone.cat.preflight.Result;

/**
 * One workstation check: a name, what it is called on the page, whether a person must act on it
 * (rather than CAT or a script), whether it is required, whether it is a sign-in the operator
 * types, the fix to show when it fails, and the probe itself.
 *
 * <p>Ported from the Python CAT's {@code cat.setup.base.SetupItem}, without its {@code apply()}: the
 * Java Setup page never changes the machine. Every item here is detect-only, and a failing one
 * carries {@link #fixHint()}, the command or step the operator runs.
 *
 * @param name     the stable identifier, also the {@link Result#check()} of its result
 * @param title    the name the page shows
 * @param manual   true when the fix is for a person (install, sign in, set a variable)
 * @param required true when a missing item blocks a run
 * @param credential true for a sign-in the operator types, which CAT refuses to run without
 * @param fixHint  what to do when the probe does not pass
 * @param probe    the read-only check
 */
public record SetupItem(String name, String title, boolean manual, boolean required, boolean credential,
        String fixHint, Probe probe) {

    /** The probe. It may throw; {@link #detect} turns any throw into an ERROR row. */
    @FunctionalInterface
    public interface Probe {
        Result detect(SetupEnv env) throws Exception;
    }

    public SetupItem {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a setup item must have a name");
        }
        title = title == null || title.isBlank() ? name : title;
        fixHint = fixHint == null ? "" : fixHint;
        Objects.requireNonNull(probe, "probe");
    }

    /** Runs the probe. A probe that throws costs its own row, not the audit: the result is ERROR. */
    public Result detect(SetupEnv env) {
        try {
            Result result = probe.detect(env);
            return result == null ? Result.error(name, "the probe returned no result") : result;
        } catch (Exception e) {
            return Result.error(name, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
