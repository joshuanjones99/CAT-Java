package com.verifone.cat.pats.feature;

import com.verifone.cat.pos.ContentFamily;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.LocatorTypes;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.validate.PyRepr;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PATS {@code WEB ELEMENT} literal as a step names it: the catalogue name, {@code ITEM_PANEL_N(145421)}, never the
 * raw id or XPath (DESIGN.md 4.2 rule 1).
 *
 * <p>Port of {@code petrotest.pats.feature.element_name} and {@code element_value}, with {@link #resolve} standing for
 * {@code petrotest.pats.elements.resolve}, which the orchestrator should point this class at when the two packages
 * merge (the logic is copied, not shared, so this package builds alone). The catalogue is {@link Locators}.
 *
 * <p>Content families are looked up by member in constant-name order, so a member held by two families resolves to the
 * first by name; the test asserts there is no such member. UNVERIFIED AGAINST HARDWARE.
 */
public final class Elements {

    /** A literal and its kind, {@code "ID"} or {@code "XPATH"}: Python's {@code element_value} pair. */
    public record Ref(String literal, String kind) {}

    /** The catalogue has no element for a PATS literal; the message names the literal. */
    public static final class UnknownElementException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownElementException(String message) {
            super(message);
        }
    }

    private static final Pattern NAME = Pattern.compile("([A-Z][A-Z0-9_]*)(?:\\(([^()\"]*)\\))?");

    /** A templated catalogue entry (every slot an index) and the pattern its literal matches. */
    private record Template(Pattern pattern, Locator locator) {}

    /** Every {@link Locator} and {@link ContentFamily} constant by its Java (and Python) name. */
    private static final Map<String, Object> CATALOGUE = catalogueByName();
    private static final List<Template> TEMPLATES = templates();
    private static final Map<String, ContentFamily> FAMILY_MEMBERS = familyMembers();

    private Elements() {}

    /**
     * The PATS literal {@code raw} of kind {@code kind} (ID or XPATH) as its catalogue name, for a step.
     *
     * @throws UnreadableActionException when the catalogue does not hold it, or has no name a step can carry
     */
    public static String elementName(String raw, String kind) {
        String text = raw.strip();
        boolean xpath = isXpath(kind);
        try {
            resolve(text, kind);
        } catch (UnknownElementException exc) {
            throw new UnreadableActionException(exc.getMessage(), exc);
        }
        Locator exact = Locators.BY_VALUE.get(text);
        if (exact != null && !exact.isTemplate() && (exact.kind() == Locator.Kind.XPATH) == xpath) {
            return exact.name();
        }
        if (!xpath) {
            for (Template template : TEMPLATES) {
                Matcher match = template.pattern().matcher(text);
                if (match.matches()) {
                    return template.locator().name() + "(" + String.join(",", groups(match)) + ")";
                }
            }
            ContentFamily family = FAMILY_MEMBERS.get(text);
            if (family != null) {
                return family.name() + "(" + text + ")";
            }
        }
        throw new UnreadableActionException(
                kind + " " + PyRepr.reprString(text) + " has no catalogue name a step can carry");
    }

    /**
     * A catalogue name a step carries, as the literal and kind the POS driver needs.
     *
     * @throws IllegalArgumentException when {@code name} is not a control in the catalogue
     */
    public static Ref elementValue(String name) {
        Matcher match = NAME.matcher(name.strip());
        Object target = match.matches() ? CATALOGUE.get(match.group(1)) : null;
        if (target == null) {
            throw new IllegalArgumentException(PyRepr.reprString(name) + " is not a control in petrotest.pos.locators");
        }
        String arg = match.group(2);
        if (arg == null) {
            if (target instanceof Locator locator) {
                return new Ref(locator.value(), kindOf(locator));
            }
            throw new IllegalArgumentException(PyRepr.reprString(name) + " names a content family with no member");
        }
        if (target instanceof ContentFamily) {
            return new Ref(arg, "ID"); // the member is the literal itself
        }
        Locator located = ((Locator) target).at((Object[]) arg.split(","));
        return new Ref(located.value(), kindOf(located));
    }

    /**
     * The catalogue locator for PATS's {@code WEB ELEMENT} {@code raw} of kind {@code kind}: an exact literal, a member
     * of a templated family, or a member of a content family. Mirrors {@code petrotest.pats.elements.resolve}.
     *
     * @throws UnknownElementException when no catalogue entry holds the literal
     */
    static Locator resolve(String raw, String kind) {
        String text = raw.strip();
        boolean xpath = isXpath(kind);
        Locator exact = Locators.BY_VALUE.get(text);
        if (exact != null && !exact.isTemplate() && (exact.kind() == Locator.Kind.XPATH) == xpath) {
            return exact;
        }
        if (!xpath) {
            for (Template template : TEMPLATES) {
                Matcher match = template.pattern().matcher(text);
                if (match.matches()) {
                    return template.locator().at((Object[]) groups(match));
                }
            }
            ContentFamily family = FAMILY_MEMBERS.get(text);
            if (family != null) {
                return Locator.of(text, Locator.Kind.ID, family.name() + "(" + PyRepr.reprString(text) + ")",
                        family.group(), 0, false, false, null, null, List.of(), "");
            }
        }
        throw new UnknownElementException(kind + " " + PyRepr.reprString(text)
                + " is not in petrotest.pos.locators; regenerate the catalogue (tools/analysis/gen_locators.py)"
                + " rather than naming it anywhere else");
    }

    private static boolean isXpath(String kind) {
        return kind.strip().toUpperCase(Locale.ROOT).equals("XPATH");
    }

    private static String kindOf(Locator locator) {
        return locator.kind() == Locator.Kind.XPATH ? "XPATH" : "ID";
    }

    private static String[] groups(Matcher match) {
        String[] out = new String[match.groupCount()];
        for (int i = 0; i < out.length; i++) {
            out[i] = match.group(i + 1);
        }
        return out;
    }

    private static Map<String, Object> catalogueByName() {
        Map<String, Object> out = new TreeMap<>();
        for (java.lang.reflect.Field field : Locators.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof Locator || value instanceof ContentFamily) {
                    out.put(field.getName(), value);
                }
            } catch (IllegalAccessException exc) {
                throw new IllegalStateException("catalogue constant " + field.getName() + " is not readable", exc);
            }
        }
        return Map.copyOf(out);
    }

    private static List<Template> templates() {
        List<Template> out = new ArrayList<>();
        for (Locator locator : Locators.ELEMENT_IDS) {
            if (!locator.isTemplate() || locator.placeholders().stream().anyMatch(slot -> !"n".equals(slot))) {
                continue;
            }
            for (String literal : locator.literals()) {
                out.add(new Template(LocatorTypes.templateRegex(literal), locator));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, ContentFamily> familyMembers() {
        Map<String, ContentFamily> members = new HashMap<>();
        for (Object value : new TreeMap<>(CATALOGUE).values()) {
            if (value instanceof ContentFamily family) {
                for (String member : family.members()) {
                    members.putIfAbsent(member, family);
                }
            }
        }
        return Map.copyOf(members);
    }

    /** Every content family's members, for the test that asserts no member is held by two families. */
    static List<String> familyMemberList() {
        List<String> out = new ArrayList<>();
        for (Object value : CATALOGUE.values()) {
            if (value instanceof ContentFamily family) {
                out.addAll(family.members());
            }
        }
        return out;
    }

}
