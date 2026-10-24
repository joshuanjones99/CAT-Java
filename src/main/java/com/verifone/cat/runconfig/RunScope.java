package com.verifone.cat.runconfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Run scope row of the Config tab: which of the ticked plans' cases a run runs.
 *
 * <p>Ported from {@code cat.panels.config.scope.ScopeChoice}. The saved keys are the Python
 * CAT's ({@code config-scope}, {@code config-tags}, {@code config-cases},
 * {@code config-tags-cases}, {@code config-from}, {@code config-prerequisites}), so one
 * {@code last-config.json} serves both. A file saved before the row existed is Whole plan.
 *
 * @param tagCases Tags with a case ticked or unticked by hand: the ticked keys, which are then
 *     the case list. {@code null} while the ticks follow the expression.
 * @param prerequisites add the prerequisites the scope leaves out (the default)
 */
public record RunScope(
        Mode mode,
        String tags,
        List<String> cases,
        List<String> tagCases,
        String fromCase,
        boolean prerequisites) {

    /** The four choices, in the row's order. */
    public enum Mode {
        WHOLE("whole", "Whole plan"),
        CASES("cases", "Cases"),
        TAGS("tags", "Tags"),
        FROM("from", "From a case");

        private final String value;
        private final String label;

        Mode(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        public String label() {
            return label;
        }

        public static Optional<Mode> of(String value) {
            for (Mode mode : values()) {
                if (mode.value.equals(value)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    static final String KEY_SCOPE = "config-scope";
    static final String KEY_TAGS = "config-tags";
    static final String KEY_CASES = "config-cases";
    static final String KEY_TAG_CASES = "config-tags-cases";
    static final String KEY_FROM = "config-from";
    static final String KEY_PREREQUISITES = "config-prerequisites";

    public RunScope {
        mode = mode == null ? Mode.WHOLE : mode;
        tags = tags == null ? "" : tags;
        cases = cases == null ? List.of() : List.copyOf(cases);
        tagCases = tagCases == null ? null : List.copyOf(tagCases);
        fromCase = fromCase == null ? "" : fromCase;
    }

    public static RunScope whole() {
        return new RunScope(Mode.WHOLE, "", List.of(), null, "", true);
    }

    /** The keys as the saved-choices file holds them. */
    public Map<String, Object> saved() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_SCOPE, mode.value());
        out.put(KEY_TAGS, tags);
        out.put(KEY_CASES, new ArrayList<>(cases));
        out.put(KEY_TAG_CASES, tagCases == null ? null : new ArrayList<>(tagCases));
        out.put(KEY_FROM, fromCase);
        out.put(KEY_PREREQUISITES, prerequisites);
        return out;
    }

    /** From a saved-choices file: a bad or absent value falls back to the Python CAT's default. */
    public static RunScope fromSaved(Map<String, ?> saved) {
        Mode mode = Mode.of(text(saved.get(KEY_SCOPE))).orElse(Mode.WHOLE);
        Object tagCases = saved.get(KEY_TAG_CASES);
        Object prerequisites = saved.get(KEY_PREREQUISITES);
        return new RunScope(
                mode,
                text(saved.get(KEY_TAGS)).strip(),
                strings(saved.get(KEY_CASES)),
                tagCases instanceof List<?> ? strings(tagCases) : null,
                text(saved.get(KEY_FROM)),
                prerequisites instanceof Boolean flag ? flag : true);
    }

    /** A copy with the Tags expression changed: its ticks follow the new expression again. */
    public RunScope withTags(String newTags) {
        return new RunScope(mode, newTags, cases, null, fromCase, prerequisites);
    }

    public RunScope withMode(Mode newMode) {
        return new RunScope(newMode, tags, cases, tagCases, fromCase, prerequisites);
    }

    public RunScope withPrerequisites(boolean on) {
        return new RunScope(mode, tags, cases, tagCases, fromCase, on);
    }

    public RunScope withCases(List<String> keys) {
        return new RunScope(mode, tags, keys, tagCases, fromCase, prerequisites);
    }

    public RunScope withTagCases(List<String> keys) {
        return new RunScope(mode, tags, cases, keys, fromCase, prerequisites);
    }

    public RunScope withFromCase(String key) {
        return new RunScope(mode, tags, cases, tagCases, key, prerequisites);
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            out.add(String.valueOf(item));
        }
        return out;
    }
}
