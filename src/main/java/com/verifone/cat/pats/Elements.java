package com.verifone.cat.pats;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pos.ContentFamily;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.LocatorTypes;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.validate.PyRepr;

/**
 * A PATS {@code WEB ELEMENT} literal, mapped to the catalogue {@link Locator} that vouches for it, and back. Port of
 * {@code petrotest.pats.elements} and of {@code petrotest.pats.feature.element_name} / {@code element_value}.
 *
 * <p>DESIGN.md 4.2 rule 1: nothing outside {@code pos.Locators} may name an element. A PATS case names one, in its XML or
 * its {@code .scenario}, so every literal it names is looked up here and must be one the generated catalogue already
 * carries: as an exact literal, as a member of a templated family ({@code ItemPanel_{n}}, with pinned panel numbers
 * matched literally), or as a member of a {@link ContentFamily}. An unknown literal raises
 * {@link UnknownElementException}; nothing is guessed. The returned locator is templated to the case's own concrete ID,
 * so what is clicked is exactly what PATS clicks.
 *
 * <p>Every lookup by name goes through the catalogue's ordered registries: {@link Locators#ELEMENT_IDS} and
 * {@link Locators#XPATHS} for literals, {@link Locators#BY_NAME} for control names. The generated catalogue has no
 * registry of its {@link ContentFamily} constants, so those are found by reflection and indexed by name, in name order,
 * so the first family to hold a member is the same on every run. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Elements {

    /** A literal and its kind, {@code "ID"} or {@code "XPATH"}: Python's {@code element_value} pair. */
    public record Ref(String literal, String kind) {
    }

    private static final Pattern NAME = Pattern.compile("([A-Z][A-Z0-9_]*)(?:\\(([^()\"]*)\\))?");

    /** A templated catalogue entry (every slot an index) and the pattern its literal matches. */
    private record Template(Pattern pattern, Locator locator) {
    }

    /** The templated ID families, in catalogue order, each literal spelling. */
    private static final List<Template> TEMPLATES = templates();

    /**
     * The controls by name: {@link Locators#BY_NAME}, then any {@link Locator} constant the registry leaves out. The
     * generated lists hold 830 of the 852 constants (the Python's {@code element_value} reads every module attribute),
     * so the rest are read from the constants, after the registry, as the Python reads them.
     */
    private static final Map<String, Locator> LOCATORS_BY_NAME = locatorsByName();

    /** The content families by name, in name order (the catalogue has no ordered registry of them). */
    private static final Map<String, ContentFamily> FAMILIES_BY_NAME = familiesByName();

    /** The members of every content family, each held by the first family to name it in name order. */
    private static final Map<String, ContentFamily> FAMILY_MEMBERS = familyMembers();

    private Elements() {
    }

    /** {@link #resolve(String, String)} for an element of type {@code ID}. */
    public static Locator resolve(String raw) {
        return resolve(raw, "ID");
    }

    /**
     * The catalogue locator for PATS's {@code WEB ELEMENT} {@code raw} of type {@code kind} ({@code ID} or
     * {@code XPATH}, as PATS's {@code TYPE[ID/XPATH]} field writes it).
     *
     * @throws UnknownElementException when the catalogue holds no such element
     */
    public static Locator resolve(String raw, String kind) {
        String wanted = raw.strip();
        boolean xpath = isXpath(kind);
        Locator exact = Locators.BY_VALUE.get(wanted);
        if (exact != null && !exact.isTemplate() && (exact.kind() == Locator.Kind.XPATH) == xpath) {
            return exact;
        }
        if (!xpath) {
            for (Template template : TEMPLATES) {
                Matcher match = template.pattern().matcher(wanted);
                if (match.matches()) {
                    // The digits as written: KEY_00 is not KEY_0 (an int() once keyed one zero per 00 press).
                    return template.locator().at((Object[]) groups(match));
                }
            }
            ContentFamily family = FAMILY_MEMBERS.get(wanted);
            if (family != null) {
                return Locator.of(wanted, Locator.Kind.ID, family.name() + "(" + PyRepr.reprString(wanted) + ")",
                        family.group(), 0, false, false, null, null, List.of(), "");
            }
        }
        throw new UnknownElementException(kind + " " + PyRepr.reprString(wanted)
                + " is not in petrotest.pos.locators; regenerate the catalogue (tools/analysis/gen_locators.py)"
                + " rather than naming it anywhere else");
    }

    /**
     * The PATS literal {@code raw} of kind {@code kind} ({@code ID} or {@code XPATH}) as its catalogue name, which a
     * step carries: {@code ITEM_PANEL_N(145421)}, never the raw id.
     *
     * @throws UnknownElementException when the catalogue does not hold {@code raw}, or it has no name a step can carry
     */
    public static String elementName(String raw, String kind) {
        String text = raw.strip();
        boolean xpath = isXpath(kind);
        resolve(text, kind);
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
        throw new UnknownElementException(kind + " " + PyRepr.reprString(text) + " has no catalogue name a step can carry");
    }

    /**
     * A catalogue name a step carries, as the literal and kind the POS driver needs.
     *
     * @throws IllegalArgumentException when {@code name} is not a control in the catalogue
     */
    public static Ref elementValue(String name) {
        Matcher match = NAME.matcher(name.strip());
        String control = match.matches() ? match.group(1) : "";
        Locator locator = LOCATORS_BY_NAME.get(control);
        ContentFamily family = locator == null ? FAMILIES_BY_NAME.get(control) : null;
        if (locator == null && family == null) {
            throw new IllegalArgumentException(PyRepr.reprString(name) + " is not a control in petrotest.pos.locators");
        }
        String arg = match.group(2);
        if (arg == null) {
            if (locator != null) {
                return new Ref(locator.value(), kindOf(locator));
            }
            throw new IllegalArgumentException(PyRepr.reprString(name) + " names a content family with no member");
        }
        if (family != null) {
            return new Ref(arg, "ID"); // the member is the literal itself
        }
        Locator located = locator.at((Object[]) arg.split(","));
        return new Ref(located.value(), kindOf(located));
    }

    /** Every content family's members, for the test that asserts no member is held by two families. */
    static List<String> familyMemberList() {
        List<String> out = new ArrayList<>();
        for (ContentFamily family : FAMILIES_BY_NAME.values()) {
            out.addAll(family.members());
        }
        return out;
    }

    /** The content families by name, for the test that asserts the order the index is built in. */
    static List<String> familyNames() {
        return List.copyOf(FAMILIES_BY_NAME.keySet());
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

    private static Map<String, Locator> locatorsByName() {
        Map<String, Locator> byName = new LinkedHashMap<>(Locators.BY_NAME);
        for (Field field : catalogueConstants(Locator.class)) {
            byName.putIfAbsent(field.getName(), constant(field, Locator.class));
        }
        return Collections.unmodifiableMap(byName);
    }

    private static Map<String, ContentFamily> familiesByName() {
        Map<String, ContentFamily> byName = new TreeMap<>();
        for (Field field : catalogueConstants(ContentFamily.class)) {
            ContentFamily family = constant(field, ContentFamily.class);
            byName.put(family.name(), family);
        }
        return Collections.unmodifiableMap(byName);
    }

    private static Map<String, ContentFamily> familyMembers() {
        Map<String, ContentFamily> members = new TreeMap<>();
        for (ContentFamily family : FAMILIES_BY_NAME.values()) {
            for (String member : family.members()) {
                members.putIfAbsent(member, family);
            }
        }
        return Map.copyOf(members);
    }

    /** The public static constants of {@link Locators} of type {@code type}, in the order the class reports them. */
    private static List<Field> catalogueConstants(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field field : Locators.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && type.isAssignableFrom(field.getType())) {
                out.add(field);
            }
        }
        return out;
    }

    private static <T> T constant(Field field, Class<T> type) {
        try {
            return type.cast(field.get(null));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read the catalogue's " + field.getName(), e);
        }
    }
}
