package com.verifone.cat.pats.runner;

import com.verifone.cat.validate.PyRepr;

import java.util.List;
import java.util.Locale;

/**
 * One PATS action as the runner reads it: its name, its place in the case, and its
 * {@code <field name= value=>} children in document order.
 *
 * <p>This is the runner's whole view of an action. The parsed PATS case types (the case reader's
 * own action type) adapt to it, so the runner does not depend on how a case is read. Field access
 * is PATS's: names match case-insensitively, a field's first non-empty value wins, and a field
 * that is present but empty is not the same as one that is absent (see {@link #raw(String)}).
 *
 * <p>Ported from the Python {@code petrotest.pats.case.Action}, which the runner reads through
 * {@code get}, {@code raw} and {@code str}.
 */
public interface RunnableAction {

    /** One {@code <field>} of an action: its name as written, and its value as written. */
    record ActionField(String name, String value) {
    }

    /** The action's name as written, for example {@code TOUCH_EVENT}. The registry matches it upper-cased. */
    String name();

    /** Zero-based position of the action in its case. */
    int index();

    /** The action's fields, in document order, repeats included. */
    List<ActionField> fields();

    /**
     * PATS's {@code get}: the first non-empty value of {@code field}, else {@code defaultValue}. A field
     * that is absent and one that is empty both read as {@code defaultValue}.
     */
    default String get(String field, String defaultValue) {
        String value = raw(field);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    /** {@link #get(String, String)} with an empty default. */
    default String get(String field) {
        return get(field, "");
    }

    /**
     * PATS's {@code raw}: the first non-empty value of {@code field}; {@code ""} when the field is present
     * but every value is empty; {@code null} when it is absent. Handlers that throw on a missing field
     * read through this, so an empty field does not throw.
     */
    default String raw(String field) {
        String wanted = field.toUpperCase(Locale.ROOT);
        boolean present = false;
        for (ActionField f : fields()) {
            if (f.name().toUpperCase(Locale.ROOT).equals(wanted)) {
                if (f.value() != null && !f.value().isEmpty()) {
                    return f.value();
                }
                present = true;
            }
        }
        return present ? "" : null;
    }

    /**
     * The action as a message names it: {@code #n NAME(FIELD='value', ...)}, with {@code n} from 1, and
     * the empty fields and {@code DEVICEID}/{@code CONTEXT} left out, as PATS's {@code str} does.
     */
    default String describe() {
        StringBuilder shown = new StringBuilder();
        for (ActionField f : fields()) {
            if (f.value() == null || f.value().isEmpty()
                    || "DEVICEID".equals(f.name()) || "CONTEXT".equals(f.name())) {
                continue;
            }
            if (shown.length() > 0) {
                shown.append(", ");
            }
            shown.append(f.name()).append('=').append(PyRepr.reprString(f.value()));
        }
        return "#" + (index() + 1) + " " + name() + "(" + shown + ")";
    }
}
