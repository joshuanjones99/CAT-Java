package com.verifone.cat.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One phrase of the step library: its text as a {@code parse} template, the group, kind, level and example it has in the
 * Python library, and the fixtures its step function takes. Port of {@code petrotest.authoring.registry.StepDef}, without
 * the step function: the Java side checks and reads a case, it does not run one.
 *
 * <p>A template's {@code {name:Type}} fields match as the Python's {@code parse} matches them: an untyped field matches
 * lazily anything, a typed one matches its type's pattern ({@link #TYPES}, the Python's {@code TYPES}). Matching ignores
 * case, as {@code parse} does by default. {@link #of} refuses a phrase whose own example does not match it, as the Python
 * {@code step} decorator does at import.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: a phrase names what a case does on a rig; this class only reads the text.
 */
public final class StepDef {

    /** The groups, in the order the catalogue lists them ({@code registry.GROUPS}). */
    public static final List<String> GROUPS = List.of(
            "POS screen",
            "Register keys (legacy RMI)",
            "Pinpad",
            "Forecourt",
            "Store and Commander",
            "Checks",
            "Screen controls (UI level)",
            "PATS actions (migrated)");

    /** The regular expression each argument type matches ({@code registry.TYPES}). */
    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("Money", "\\$?\\d*\\.\\d\\d"),
            Map.entry("Number", "\\d+"),
            Map.entry("Hex", "0x[0-9A-Fa-f]{2}"),
            Map.entry("MopKey", "CASH|CREDIT|OTHER MOP|0x[0-9A-Fa-f]{2}"),
            Map.entry("DcrKey", "[LR][1-4]"),
            Map.entry("Duration", "\\d+(?:\\.\\d+)?\\s*(?:ms|s|m|h)"),
            Map.entry("Pad", "numeric|calculator|on-screen"),
            Map.entry("PadKey", "OK|CANCEL|CLEAR"),
            Map.entry("Field", "[^\"]*"),
            Map.entry("Element", "[A-Z][A-Z0-9_]*(?:\\([^()\"]*\\))?"),
            Map.entry("PatsAction", "[A-Z][A-Z0-9_]*"),
            Map.entry("SoftKey", "[LR][1-4]"));

    /** An untyped field: {@code parse}'s default, a lazy match of any text. */
    private static final String UNTYPED = ".+?";

    private static final Pattern FIELD = Pattern.compile("\\{(\\w+)(?::(\\w+))?\\}");

    private final String text;
    private final String group;
    private final String kind;
    private final String level;
    private final String example;
    private final String doc;
    private final List<String> fixtures;
    private final List<String> params;
    private final Map<String, String> types;
    private final Pattern pattern;

    private StepDef(String text, String group, String kind, String level, String example, String doc,
            List<String> fixtures, List<String> params, Map<String, String> types, Pattern pattern) {
        this.text = text;
        this.group = group;
        this.kind = kind;
        this.level = level;
        this.example = example;
        this.doc = doc;
        this.fixtures = List.copyOf(fixtures);
        this.params = List.copyOf(params);
        this.types = types;
        this.pattern = pattern;
    }

    /**
     * A phrase, checked as the Python's {@code step} decorator checks it.
     *
     * @param text     the template, e.g. {@code the cashier rings up PLU {plu:Number}}
     * @param group    one of {@link #GROUPS}
     * @param kind     {@code context}, {@code action} or {@code outcome}
     * @param level    {@code domain}, {@code ui} or {@code pats}
     * @param example  a whole line, keyword included, as a case says it
     * @param doc      the first paragraph of the step's docstring: what the catalogue shows
     * @param fixtures the fixtures the step function takes, beyond its arguments
     * @throws IllegalArgumentException when the group is unknown, a type is unknown, or the example does not match
     */
    public static StepDef of(String text, String group, String kind, String level, String example, String doc,
            List<String> fixtures) {
        if (!GROUPS.contains(group)) {
            throw new IllegalArgumentException("step " + text + ": group " + group + " is not one of " + GROUPS);
        }
        List<String> params = new ArrayList<>();
        Map<String, String> types = new LinkedHashMap<>();
        Matcher field = FIELD.matcher(text);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (field.find()) {
            String name = field.group(1);
            String type = field.group(2);
            String body = type == null ? UNTYPED : TYPES.get(type);
            if (body == null) {
                throw new IllegalArgumentException("step " + text + ": {" + name + "} has an unknown type " + type);
            }
            regex.append(Pattern.quote(text.substring(last, field.start())));
            regex.append('(').append(body).append(')');
            params.add(name);
            types.put(name, type == null ? "Text" : type);
            last = field.end();
        }
        regex.append(Pattern.quote(text.substring(last)));
        Pattern pattern = Pattern.compile(regex.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        StepDef definition = new StepDef(text, group, kind, level, example, doc, fixtures, params, types, pattern);
        String line = example.contains(" ") ? example.substring(example.indexOf(' ') + 1) : example;
        if (!definition.matches(line)) {
            throw new IllegalArgumentException("step " + text + ": its own example " + example + " does not match it");
        }
        return definition;
    }

    /** The phrase's template, with its types. */
    public String text() {
        return text;
    }

    public String group() {
        return group;
    }

    public String kind() {
        return kind;
    }

    /** {@code domain}, {@code ui} or {@code pats}. */
    public String level() {
        return level;
    }

    public String example() {
        return example;
    }

    /** The first paragraph of the step's docstring. */
    public String doc() {
        return doc;
    }

    public List<String> fixtures() {
        return fixtures;
    }

    /** The phrase's arguments, in the order they appear. */
    public List<String> params() {
        return params;
    }

    /** Each argument's type name ({@code Text} when untyped), by argument name. */
    public Map<String, String> types() {
        return types;
    }

    /** The phrase as an author reads it: {@code the cashier rings up PLU {plu}}, types dropped. */
    public String display() {
        return FIELD.matcher(text).replaceAll(m -> Matcher.quoteReplacement("{" + m.group(1) + "}"));
    }

    /** Whether {@code line} (keyword stripped) is this phrase, as the whole line. */
    public boolean matches(String line) {
        return pattern.matcher(line).matches();
    }

    /**
     * The arguments {@code line} (keyword stripped) gives this phrase, by name, as written (no type conversion), or
     * empty when the line is not this phrase. The step runner ({@code pats.runner.handlers.steps}) converts them.
     */
    public java.util.Optional<Map<String, String>> arguments(String line) {
        Matcher m = pattern.matcher(line);
        if (!m.matches()) {
            return java.util.Optional.empty();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < params.size(); i++) {
            out.put(params.get(i), m.group(i + 1));
        }
        return java.util.Optional.of(out);
    }

    @Override
    public String toString() {
        return text;
    }
}
