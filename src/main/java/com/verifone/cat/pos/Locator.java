package com.verifone.cat.pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * One POS element, as measured in the corpus. Port of {@code petrotest.pos.locator_types.Locator}.
 *
 * <p>{@code value} is the raw literal, possibly still carrying {@code {n}} or {@code {label}}
 * placeholders; see {@link #at}. {@code alternates} are further literal spellings of the same
 * element (for example the {@code &} and {@code &amp;} spellings of one tab). Both are offered to
 * the browser at once: a CSS selector list is an OR.
 *
 * <p>{@code refs} is the blast radius: the corpus references measured for this element.
 * {@code frame} is a sticky-forward hint from the corpus, never an assertion. The record is
 * immutable; templating returns a new locator.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: selectors are strings, proven only to equal the Python CAT's.
 */
public record Locator(
        String value,
        Kind kind,
        String name,
        String group,
        int refs,
        boolean documented,
        boolean deprecated,
        String replacement,
        String frame,
        List<String> alternates,
        String note,
        boolean unresolved) {

    /** The two selector shapes the corpus has. */
    public enum Kind {
        ID,
        XPATH
    }

    public Locator {
        alternates = List.copyOf(alternates);
    }

    /**
     * The generated catalogue's constructor: an element that is not {@link LocatorTypes#unresolved}.
     * Parameters follow the Python dataclass field order (the record's last component is the
     * unresolved flag, which is false here).
     */
    public static Locator of(
            String value,
            Kind kind,
            String name,
            String group,
            int refs,
            boolean documented,
            boolean deprecated,
            String replacement,
            String frame,
            List<String> alternates,
            String note) {
        return new Locator(
                value, kind, name, group, refs, documented, deprecated, replacement, frame,
                alternates, note, false);
    }

    /** The placeholder names still unresolved in {@code value}, in order of appearance. */
    public List<String> placeholders() {
        List<String> names = new ArrayList<>();
        Matcher m = LocatorTypes.PLACEHOLDER.matcher(value);
        while (m.find()) {
            names.add(m.group(1));
        }
        return List.copyOf(names);
    }

    public boolean isTemplate() {
        return LocatorTypes.PLACEHOLDER.matcher(value).find();
    }

    /**
     * Substitute the {@code {n}} index slots, left to right. Arity is checked: a silently
     * half-rendered selector is the failure a locator layer must not have.
     */
    public Locator at(Object... indices) {
        return atNamed(Map.of(), indices);
    }

    /**
     * {@link #at} with named slots ({@code {label}}, used by the text-predicate families and
     * {@link ContentFamily}). Java has no keyword arguments, so the names come in a map.
     */
    public Locator atNamed(Map<String, ?> named, Object... indices) {
        if (unresolved) {
            return this;
        }
        List<String> slots = placeholders();
        long positional = slots.stream().filter("n"::equals).count();
        if (indices.length != positional) {
            throw new LocatorError(displayName() + ": expected " + positional
                    + " index argument(s) for " + LocatorTypes.pyRepr(value)
                    + ", got " + indices.length);
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String slot : slots) {
            if (!"n".equals(slot) && !named.containsKey(slot)) {
                missing.add(slot);
            }
        }
        if (!missing.isEmpty()) {
            throw new LocatorError(displayName() + ": missing slot(s) "
                    + missing.stream().map(LocatorTypes::pyRepr).collect(Collectors.joining(", ", "[", "]"))
                    + " for " + LocatorTypes.pyRepr(value));
        }
        return new Locator(
                render(value, indices, named),
                kind,
                name,
                group,
                refs,
                documented,
                deprecated,
                replacement,
                frame,
                alternates.stream().map(alt -> render(alt, indices, named)).toList(),
                note,
                false);
    }

    /**
     * Each rendering uses a fresh iterator over the same indices: alternates are spellings of the
     * same element, so each is rendered with the same arguments.
     */
    private static String render(String text, Object[] indices, Map<String, ?> named) {
        Iterator<Object> it = Arrays.asList(indices).iterator();
        StringBuilder out = new StringBuilder();
        Matcher m = LocatorTypes.PLACEHOLDER.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String key = m.group(1);
            if ("n".equals(key)) {
                out.append(it.next());
            } else {
                out.append(named.get(key));
            }
            last = m.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /** The raw literal and every alternate spelling, in that order. */
    public List<String> literals() {
        List<String> all = new ArrayList<>(alternates.size() + 1);
        all.add(value);
        all.addAll(alternates);
        return List.copyOf(all);
    }

    /**
     * The Playwright selector for this element. Throws if placeholders are unresolved (a
     * half-rendered selector matches nothing and times out without explaining why), or if this is
     * an unresolved control.
     */
    public String selector() {
        if (unresolved) {
            throw new UnresolvedLocatorError(name + ": no element ID for this control has been confirmed. " + note);
        }
        if (isTemplate()) {
            throw new LocatorError(displayName() + ": unresolved slot(s) " + placeholders()
                    + " - call .at(...) first (DESIGN.md 5.2)");
        }
        if (kind == Kind.XPATH) {
            return "xpath=" + value;
        }
        return literals().stream().map(LocatorTypes::idSelector).collect(Collectors.joining(", "));
    }

    /** A CSS {@code [id^="..."]} selector for every member of an indexed family. ID-only. */
    public String prefixSelector() {
        if (kind != Kind.ID) {
            throw new LocatorError(displayName() + ": prefix_selector is ID-only");
        }
        int brace = value.indexOf('{');
        String head = brace < 0 ? value : value.substring(0, brace);
        if (head.isEmpty() || head.equals(value)) {
            throw new LocatorError(displayName() + ": not an indexed family, no prefix to take");
        }
        return LocatorTypes.idPrefixSelector(head);
    }

    /**
     * The numeric index recovered from a concrete ID this family matches; the inverse of
     * {@link #at}. Empty when the ID does not match, or the family has named slots.
     */
    public OptionalLong indexOf(String elementId) {
        if (kind != Kind.ID || !isTemplate()) {
            return OptionalLong.empty();
        }
        if (placeholders().stream().anyMatch(slot -> !"n".equals(slot))) {
            return OptionalLong.empty();
        }
        Matcher m = LocatorTypes.templateRegex(value).matcher(elementId);
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        // The last numeric slot is the row index; leading slots are the constant panel numbers.
        return LocatorTypes.parseIndex(m.group(m.groupCount()));
    }

    /** The name when there is one, else the literal. Used in messages, as the Python does. */
    String displayName() {
        return name.isEmpty() ? value : name;
    }
}
