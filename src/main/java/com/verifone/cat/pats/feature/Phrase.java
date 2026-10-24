package com.verifone.cat.pats.feature;

import com.verifone.cat.pats.Action;
import com.verifone.cat.pats.Elements;
import com.verifone.cat.pats.runner.Fields;
import com.verifone.cat.pats.runner.RunnableAction;

import com.verifone.cat.validate.PyRepr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One phrase and the PATS action it says: {@link #build} reads a step's arguments into the action, {@link #write}
 * writes an action in the phrase's words (not yet checked to read back the same; {@link PatsFeature#render} does that).
 *
 * <p>Port of {@code petrotest.pats.feature.Phrase}. A bind's field is either a PATS field name, or one of the
 * special markers {@link #ELEMENT}, {@link #NAME}, {@link #FIELDS} and {@link #PROMPT_ROWS}, as in the Python.
 * UNVERIFIED AGAINST HARDWARE.
 *
 * @param action the action it builds; {@code null} for a phrase whose action is an argument ({@link #NAME})
 * @param text the phrase, with {@code {argument:Type}} placeholders
 * @param kind {@code action}, {@code outcome}, ...
 * @param example a whole line, keyword included, as a case says it
 * @param binds which field each argument carries
 * @param fixed fields every action this phrase builds carries
 */
public record Phrase(
        String action,
        String text,
        String kind,
        String example,
        List<Bind> binds,
        List<RunnableAction.ActionField> fixed) {

    /** A phrase argument that is a whole element ({@code WEB ELEMENT} and {@code TYPE[ID/XPATH]}). */
    public static final String ELEMENT = "@element";
    /** A phrase argument that is the action's own name (the DCR soft keys, the catch-all). */
    public static final String NAME = "@name";
    /** The catch-all's argument: every field the runner reads, {@code KEY=value; KEY=value}. */
    public static final String FIELDS = "@fields";
    /** VALIDATE_DYNAMIC_PROMPTS' {@code PROMPTS} table, each row {@code <prompt> @ <element> = <key> + <key>}. */
    public static final String PROMPT_ROWS = "@prompts";

    /** Argument {@code argument} carries field {@code field}. */
    public record Bind(String argument, String field) {}

    private static final Pattern ARG = Pattern.compile("\\{(\\w+)(?::\\w+)?\\}");

    public Phrase {
        binds = List.copyOf(binds);
        fixed = List.copyOf(fixed);
    }

    /** The action this phrase says, with {@code arguments} as the step parsed them. */
    public Action build(Map<String, String> arguments, int index) {
        String name = action == null ? "" : action;
        List<RunnableAction.ActionField> fields = new ArrayList<>(fixed);
        for (Bind bind : binds) {
            String value = arguments.get(bind.argument());
            if (value == null) {
                throw new IllegalStateException(text + ": no value for {" + bind.argument() + "}");
            }
            switch (bind.field()) {
                case ELEMENT -> {
                    Elements.Ref ref = Elements.elementValue(value);
                    fields.add(new RunnableAction.ActionField("WEB ELEMENT", ref.literal()));
                    fields.add(new RunnableAction.ActionField("TYPE[ID/XPATH]", ref.kind()));
                }
                case NAME -> name = value.toUpperCase(Locale.ROOT);
                case FIELDS -> fields.addAll(unpack(value));
                case PROMPT_ROWS -> fields.add(new RunnableAction.ActionField("PROMPTS", PromptRows.fromText(value)));
                default -> fields.add(new RunnableAction.ActionField(bind.field(), value));
            }
        }
        return new Action(name, fields, index);
    }

    /** {@code action} in this phrase's words; {@link PatsFeature#render} checks that the line reads back the same. */
    public String write(Action action) {
        Map<String, String> values = new HashMap<>();
        for (Bind bind : binds) {
            values.put(bind.argument(), valueFor(bind, action));
        }
        Matcher match = ARG.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (match.find()) {
            String value = values.get(match.group(1));
            if (value == null) {
                throw new IllegalStateException(text + ": no value for {" + match.group(1) + "}");
            }
            out.append(text, last, match.start()).append(value);
            last = match.end();
        }
        return out.append(text.substring(last)).toString();
    }

    private static String valueFor(Bind bind, Action action) {
        return switch (bind.field()) {
            case ELEMENT -> Elements.elementName(action.get("WEB ELEMENT"), action.get("TYPE[ID/XPATH]", "ID"));
            case NAME -> action.name();
            case FIELDS -> pack(action);
            case PROMPT_ROWS -> PromptRows.toText(action.get("PROMPTS"));
            default -> action.get(bind.field());
        };
    }

    /**
     * Every field the runner reads that {@code action} carries, as {@code KEY=value; KEY=value}. {@code DEVICE} and
     * {@code DEVICEID} are left out where they say what the runner assumes anyway (the POS; a register rather than a
     * DCR). An element is never written here: a raw id in a step is refused, so an action that touches one needs a
     * phrase of its own.
     */
    static String pack(Action action) {
        List<String> names = new ArrayList<>(List.of("DEVICE", "DEVICEID"));
        names.addAll(Fields.FIELDS_READ.getOrDefault(action.name().toUpperCase(Locale.ROOT), List.of()));
        if (names.contains("WEB ELEMENT")) {
            throw new UnreadableActionException(action + ": an element is written only by a phrase that names it");
        }
        List<String> parts = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            String value = action.raw(name);
            if (value == null) {
                continue;
            }
            if (name.equals("DEVICE") && value.strip().toUpperCase(Locale.ROOT).equals("POS")) {
                continue;
            }
            if (name.equals("DEVICEID") && !value.strip().toUpperCase(Locale.ROOT).startsWith("DCR")) {
                continue;
            }
            if (containsAny(value, ";\"=") || !value.equals(value.strip())) {
                throw new UnreadableActionException(action + ": " + name + "=" + PyRepr.reprString(value)
                        + " cannot be written in a step");
            }
            parts.add(name + "=" + value);
        }
        return String.join("; ", parts);
    }

    /** The fields a {@code KEY=value; KEY=value} step text carries, in order. */
    static List<RunnableAction.ActionField> unpack(String text) {
        List<RunnableAction.ActionField> fields = new ArrayList<>();
        for (String raw : text.split(";")) {
            String part = raw.strip();
            if (part.isEmpty()) {
                continue;
            }
            int equals = part.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException(PyRepr.reprString(part) + " is not KEY=value");
            }
            fields.add(new RunnableAction.ActionField(part.substring(0, equals).strip(), part.substring(equals + 1)));
        }
        return fields;
    }

    private static boolean containsAny(String text, String chars) {
        for (char c : chars.toCharArray()) {
            if (text.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }
}
