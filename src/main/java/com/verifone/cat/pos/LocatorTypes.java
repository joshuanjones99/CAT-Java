package com.verifone.cat.pos;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written helpers the generated {@link Locators} catalogue is written in.
 *
 * <p>Port of {@code petrotest.pos.locator_types} (DESIGN.md section 4.2 rule 1 and 5.2). This class
 * knows no element ID and no XPath. Selectors are plain strings: nothing here depends on
 * Playwright. UNVERIFIED AGAINST HARDWARE: the selectors are only proven to equal the Python
 * CAT's (see the parity test), not to match anything on a live Topaz.
 */
public final class LocatorTypes {

    /** The placeholder the corpus extraction leaves for a numeric segment ({@code _[0-9]+} to {@code _{n}}). */
    public static final String INDEX_PLACEHOLDER = "{n}";

    /** The default {@code since} of a build variant: the first build on which the default spelling applies. */
    public static final String DEFAULT_SINCE = "057.00";

    /** {@code \{(\w+)\}}, the slot syntax. Unicode classes, as Python's str patterns are. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern LEADING_DIGITS =
            Pattern.compile("\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    private LocatorTypes() {}

    /**
     * Parse a POS build version ({@code "057.00"} is {@code [57, 0]}). Non-numeric trailing junk is
     * dropped rather than raising, as in the Python. Throws if nothing numeric leads.
     */
    public static List<BigInteger> parseVersion(String version) {
        List<BigInteger> parts = new ArrayList<>();
        for (String chunk : version.strip().split("\\.", -1)) {
            Matcher digits = LEADING_DIGITS.matcher(chunk);
            if (!digits.lookingAt()) {
                break;
            }
            parts.add(new BigInteger(digits.group()));
        }
        if (parts.isEmpty()) {
            throw new LocatorError("cannot parse build version " + pyRepr(version) + " (DESIGN.md 8)");
        }
        return List.copyOf(parts);
    }

    /** True when {@code a} is an earlier build than {@code b}, comparing numerically and zero-padded. */
    public static boolean isBefore(List<BigInteger> a, List<BigInteger> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = component(a, i).compareTo(component(b, i));
            if (c != 0) {
                return c < 0;
            }
        }
        return false;
    }

    private static BigInteger component(List<BigInteger> version, int i) {
        return i < version.size() ? version.get(i) : BigInteger.ZERO;
    }

    /**
     * What a literal's concrete spellings match: a digit group at each {@code {n}} slot, any text
     * at a named one ({@code {label}}), the literal parts quoted. Groups come in slot order.
     */
    public static Pattern templateRegex(String literal) {
        StringBuilder regex = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(literal);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(literal.substring(last, m.start())));
            regex.append("n".equals(m.group(1)) ? "(\\d+)" : "(.+)");
            last = m.end();
        }
        regex.append(Pattern.quote(literal.substring(last)));
        return Pattern.compile(regex.toString(), Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    }

    /** {@code [id="..."]}: the attribute form, because corpus IDs contain CSS-significant characters. */
    static String idSelector(String elementId) {
        if (elementId.indexOf('"') >= 0) {
            throw new LocatorError("element ID has a double quote, no selector: " + pyRepr(elementId));
        }
        return "[id=\"" + elementId + "\"]";
    }

    /** {@code [id^="..."]}: every member of an indexed family in one query. */
    static String idPrefixSelector(String prefix) {
        if (prefix.indexOf('"') >= 0) {
            throw new LocatorError("ID prefix contains a double quote: " + pyRepr(prefix));
        }
        return "[id^=\"" + prefix + "\"]";
    }

    /**
     * The section 5.2 declaration. Keyword-only in Python, positional here: the two spellings
     * differ by one suffix, so the call site names every argument in the generated catalogue.
     */
    public static BuildVariant buildVariant(
            String defaultValue,
            String legacyValue,
            String since,
            String name,
            String group,
            int refs,
            int legacyRefs) {
        String base = name == null ? "" : name;
        Locator current = new Locator(
                defaultValue, Locator.Kind.ID, base, group, refs,
                true, false, null, null, List.of(), "", false);
        Locator legacy = new Locator(
                legacyValue, Locator.Kind.ID, base.isEmpty() ? "" : base + "_LEGACY", group,
                legacyRefs, true, true, defaultValue, null, List.of(), "", false);
        return new BuildVariant(current, legacy, since, name);
    }

    /** A named control whose element ID is genuinely not known; its selector raises. */
    public static Locator unresolved(String name, String reason) {
        return new Locator(
                "<unresolved:" + name + ">", Locator.Kind.ID, name, "", 0,
                false, false, null, null, List.of(), reason, true);
    }

    /** Index a catalogue by raw literal; the first locator to claim a literal wins. */
    public static Map<String, Locator> byValue(List<Locator> catalogue) {
        Map<String, Locator> index = new LinkedHashMap<>();
        for (Locator loc : catalogue) {
            for (String literal : loc.literals()) {
                index.putIfAbsent(literal, loc);
            }
        }
        return Collections.unmodifiableMap(index);
    }

    /** Index a catalogue by constant name; the last locator to claim a name wins (dict comprehension). */
    public static Map<String, Locator> byName(List<Locator> catalogue) {
        Map<String, Locator> index = new LinkedHashMap<>();
        for (Locator loc : catalogue) {
            index.put(loc.name(), loc);
        }
        return Collections.unmodifiableMap(index);
    }

    /** Concatenate two registries, keeping order. */
    public static List<Locator> concat(List<Locator> first, List<Locator> second) {
        List<Locator> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    /** Parse an index recovered from an ID; a value too large for a long is refused. */
    static OptionalLong parseIndex(String digits) {
        try {
            return OptionalLong.of(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            throw new LocatorError("index out of range: " + digits);
        }
    }

    /**
     * Python's {@code repr()} of a string, for the messages and names the Python emits. Matches
     * Python for printable text; control characters become {@code \\xNN} or {@code \\uNNNN}.
     */
    public static String pyRepr(String s) {
        char quote = (s.indexOf('\'') >= 0 && s.indexOf('"') < 0) ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(quote);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == quote) {
                out.append('\\').append(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || c == 0x7f) {
                out.append(String.format("\\x%02x", (int) c));
            } else if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append(quote).toString();
    }
}
