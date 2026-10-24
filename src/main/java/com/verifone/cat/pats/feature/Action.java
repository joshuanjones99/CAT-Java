package com.verifone.cat.pats.feature;

import com.verifone.cat.validate.PyRepr;
import java.util.List;
import java.util.Locale;

/**
 * One PATS action: its name, its fields in order, and its position in the case.
 *
 * <p>Port of the parts of {@code petrotest.pats.case.Action} that {@code petrotest.pats.feature} reads. It is
 * defined here, minimal and local, so this package does not depend on the PATS runner's own types; the orchestrator
 * unifies it with {@code pats.Action} at merge. Field names are matched case-insensitively, as the Python does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE.
 */
public record Action(String name, List<Field> fields, int index) {

    /** One {@code <field name= value=>} child: a name and its value, which may be empty. */
    public record Field(String name, String value) {}

    public Action {
        fields = List.copyOf(fields);
    }

    /** An action at position 0 with the given fields. */
    public static Action of(String name, Field... fields) {
        return new Action(name, List.of(fields), 0);
    }

    /** The first non-empty value of field {@code name}, else {@code fallback} (Python {@code get(name, default)}). */
    public String get(String name, String fallback) {
        String value = raw(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** {@link #get(String, String)} with an empty fallback. */
    public String get(String name) {
        return get(name, "");
    }

    /**
     * Field {@code name} as the Python's {@code raw}: the first non-empty value; {@code ""} when the field is present
     * but empty; {@code null} when it is absent. A handler that throws on a missing field must not throw on an empty one.
     */
    public String raw(String name) {
        String wanted = name.toUpperCase(Locale.ROOT);
        boolean present = false;
        for (Field field : fields) {
            if (field.name().toUpperCase(Locale.ROOT).equals(wanted)) {
                present = true;
                if (field.value() != null && !field.value().isEmpty()) {
                    return field.value();
                }
            }
        }
        return present ? "" : null;
    }

    /** The same action at another position. */
    public Action withIndex(int position) {
        return new Action(name, fields, position);
    }

    /** As the Python's {@code __str__}: {@code #3 NAME(FIELD='value', ...)}, empty fields and DEVICEID/CONTEXT left out. */
    @Override
    public String toString() {
        StringBuilder shown = new StringBuilder();
        for (Field field : fields) {
            if (field.value() == null || field.value().isEmpty()
                    || field.name().equals("DEVICEID") || field.name().equals("CONTEXT")) {
                continue;
            }
            if (shown.length() > 0) {
                shown.append(", ");
            }
            shown.append(field.name()).append('=').append(PyRepr.reprString(field.value()));
        }
        return "#" + (index + 1) + " " + name + "(" + shown + ")";
    }
}
