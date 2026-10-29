package com.verifone.cat.setup;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.verifone.cat.preflight.Result;

/**
 * One workstation check: a name, what it is called on the page, whether a person must act on it (rather than CAT),
 * whether it is required, whether it is a sign-in the operator types, the fix to show when it fails, and the probe.
 *
 * <p>Ported from the Python CAT's {@code cat.setup.base.SetupItem}, with its opt-in parts:
 * <ul>
 *   <li>{@link #repair()}: the item's {@code apply()}, the bounded idempotent repair the audit runs for an
 *       automatable ({@code manual == false}) item whose probe FAILs. Null means none: {@link #apply} then reports
 *       {@code unrepairable} with {@link #fixHint()}, as every Python manual item's {@code apply()} does.</li>
 *   <li>{@link #fields()} and {@link #saver()}: the sign-in card's inputs and its {@code apply_manual(env, values)}.</li>
 *   <li>{@link #savedValues()}: what the card's inputs show (the saved login, masked where secret).</li>
 *   <li>{@link #liveCheck()}: the {@code check_live()} sign-in, a network call made only after a save or by the
 *       saved-login check on start, never by {@link #detect}.</li>
 * </ul>
 *
 * @param name     the stable identifier, also the {@link Result#check()} of its result
 * @param title    the name the page shows
 * @param manual   true when the fix is for a person (install, sign in)
 * @param required true when a missing item blocks a run
 * @param credential true for a sign-in the operator types, which CAT refuses to run without
 * @param fixHint  what to do when the probe does not pass
 * @param probe    the read-only check
 * @param repair   the automatable repair, or null
 * @param fields   the sign-in card's inputs; empty for an item with nothing to type
 * @param saver    saves what was typed into {@link #fields()}, or null
 * @param savedValues reads what is saved for {@link #fields()}, or null
 * @param liveCheck signs in with what is saved, or null
 */
public record SetupItem(String name, String title, boolean manual, boolean required, boolean credential,
        String fixHint, Probe probe, Repair repair, List<ManualField> fields, FieldSaver saver,
        SavedValues savedValues, LiveCheck liveCheck) {

    /** The probe. It may throw; {@link #detect} turns any throw into an ERROR row. */
    @FunctionalInterface
    public interface Probe {
        Result detect(SetupEnv env) throws Exception;
    }

    /** The Python's {@code apply(env)}: may change the workstation, through {@link SetupEnv}'s write methods. */
    @FunctionalInterface
    public interface Repair {
        Result apply(SetupEnv env) throws Exception;
    }

    /** The Python's {@code apply_manual(env, values)}: saves what a person typed. A blank value keeps what is saved. */
    @FunctionalInterface
    public interface FieldSaver {
        Result save(SetupEnv env, Map<String, String> values) throws Exception;
    }

    /** The Python's {@code saved_values(env)}: the saved value of each field, by key; empty when none is saved. */
    @FunctionalInterface
    public interface SavedValues {
        Map<String, String> read(SetupEnv env) throws Exception;
    }

    /** The Python's {@code check_live()}: a sign-in over the network with what is saved. */
    @FunctionalInterface
    public interface LiveCheck {
        Result check(SetupEnv env) throws Exception;
    }

    public SetupItem {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a setup item must have a name");
        }
        title = title == null || title.isBlank() ? name : title;
        fixHint = fixHint == null ? "" : fixHint;
        Objects.requireNonNull(probe, "probe");
        fields = List.copyOf(fields == null ? List.of() : fields);
    }

    /** A detect-only item: no repair, nothing to type, no live sign-in. */
    public SetupItem(String name, String title, boolean manual, boolean required, boolean credential,
            String fixHint, Probe probe) {
        this(name, title, manual, required, credential, fixHint, probe, null, List.of(), null, null, null);
    }

    /** This item with {@code fix} as its {@code apply()}. */
    public SetupItem withRepair(Repair fix) {
        return new SetupItem(name, title, manual, required, credential, fixHint, probe, fix, fields, saver,
                savedValues, liveCheck);
    }

    /** This item with a sign-in card: its inputs, how they are saved, and how the saved values are read back. */
    public SetupItem withFields(List<ManualField> inputs, FieldSaver save, SavedValues read) {
        return new SetupItem(name, title, manual, required, credential, fixHint, probe, repair, inputs, save, read,
                liveCheck);
    }

    /** This item with {@code check} as its live sign-in. */
    public SetupItem withLiveCheck(LiveCheck check) {
        return new SetupItem(name, title, manual, required, credential, fixHint, probe, repair, fields, saver,
                savedValues, check);
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

    /** Runs the repair, or reports {@code unrepairable} with the fix hint when the item has none. Never throws. */
    public Result apply(SetupEnv env) {
        if (repair == null) {
            return Result.unrepairable(name, fixHint);
        }
        try {
            Result result = repair.apply(env);
            return result == null ? Result.error(name, "the repair returned no result") : result;
        } catch (Exception e) {
            return Result.error(name, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Whether the page offers a sign-in card for this item. */
    public boolean hasFields() {
        return !fields.isEmpty() && saver != null;
    }

    /** The saved value of each field, or empty when nothing is saved or the store will not read. */
    public Map<String, String> saved(SetupEnv env) {
        if (savedValues == null) {
            return Map.of();
        }
        try {
            Map<String, String> values = savedValues.read(env);
            return values == null ? Map.of() : values;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
