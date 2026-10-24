package com.verifone.cat.pats.feature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a step line against the PATS phrases, as the Python's {@code parse} library does for them.
 *
 * <p>The phrase text is a {@code parse} format: literal text matches exactly, each {@code {name:Type}} matches its
 * type's pattern, and the whole line must match. As in {@code parse}, matching ignores case, so the phrase text is
 * matched in any case. The types are the five PATS types of {@code petrotest.authoring.registry.TYPES} ({@code Element},
 * {@code Field}, {@code Number}, {@code PatsAction}, {@code SoftKey}), with the same patterns, and each converted as
 * the Python converts it. Only the PATS phrases are here: the Python's library also holds the domain phrases, which a
 * PATS line never matches on the Python side either. UNVERIFIED AGAINST HARDWARE.
 */
final class PhraseMatcher {

    /** One phrase a line matched, and its arguments converted as the Python's {@code parse_arguments} gives them. */
    record Match(Phrase phrase, Map<String, String> arguments) {}

    private static final Pattern FIELD = Pattern.compile("\\{(\\w+)(?::(\\w+))?\\}");

    /** The regular expression each type matches, as {@code petrotest.authoring.registry.TYPES} spells it. */
    private static final Map<String, String> TYPES = Map.of(
            "Element", "[A-Z][A-Z0-9_]*(?:\\([^()\"]*\\))?",
            "Field", "[^\"]*",
            "Number", "\\d+",
            "PatsAction", "[A-Z][A-Z0-9_]*",
            "SoftKey", "[LR][1-4]");

    /** A phrase compiled to a pattern, with its argument names and types in the order their groups appear. */
    private record Compiled(Phrase phrase, Pattern pattern, List<String> names, List<String> types) {}

    private static final List<Compiled> COMPILED = compileAll();

    private PhraseMatcher() {}

    /** Every PATS phrase {@code line} (keyword stripped) matches, in the Python's order. */
    static List<Match> matches(String line) {
        List<Match> out = new ArrayList<>();
        for (Compiled compiled : COMPILED) {
            Matcher match = compiled.pattern().matcher(line);
            if (!match.matches()) {
                continue;
            }
            Map<String, String> arguments = new LinkedHashMap<>();
            for (int i = 0; i < compiled.names().size(); i++) {
                arguments.put(compiled.names().get(i), convert(compiled.types().get(i), match.group(i + 1)));
            }
            out.add(new Match(compiled.phrase(), arguments));
        }
        return out;
    }

    private static List<Compiled> compileAll() {
        List<Compiled> out = new ArrayList<>();
        for (Phrase phrase : Phrases.ALL) {
            out.add(compile(phrase));
        }
        return List.copyOf(out);
    }

    private static Compiled compile(Phrase phrase) {
        String text = phrase.text();
        Matcher field = FIELD.matcher(text);
        StringBuilder regex = new StringBuilder();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        int last = 0;
        while (field.find()) {
            regex.append(Pattern.quote(text.substring(last, field.start())));
            String type = field.group(2);
            String pattern = type == null ? null : TYPES.get(type);
            if (pattern == null) {
                throw new IllegalStateException(text + ": {" + field.group(1) + "} has no PATS type");
            }
            regex.append('(').append(pattern).append(')');
            names.add(field.group(1));
            types.add(type);
            last = field.end();
        }
        regex.append(Pattern.quote(text.substring(last)));
        Pattern compiled = Pattern.compile(regex.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL);
        return new Compiled(phrase, compiled, names, types);
    }

    /** The value a type converts its matched text to: {@code SoftKey} is upper-cased, the rest are kept as written. */
    private static String convert(String type, String text) {
        return type.equals("SoftKey") ? text.toUpperCase(Locale.ROOT) : text;
    }
}
