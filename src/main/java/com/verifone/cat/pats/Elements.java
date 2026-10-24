package com.verifone.cat.pats;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pos.ContentFamily;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.LocatorTypes;
import com.verifone.cat.pos.Locators;

/**
 * A PATS {@code WEB ELEMENT} literal, mapped to the catalogue {@link Locator} that vouches for it. Port of
 * {@code petrotest.pats.elements}.
 *
 * <p>DESIGN.md 4.2 rule 1: nothing outside {@code pos.Locators} may name an element. A PATS case names one, in its
 * XML or its {@code .scenario}, so every literal it names is looked up here and must be one the generated catalogue
 * already carries: as an exact literal, as a member of a templated family ({@code ItemPanel_{n}}, with pinned panel
 * numbers matched literally), or as a member of a {@link ContentFamily}. An unknown literal raises
 * {@link UnknownElementException}; nothing is guessed. The returned locator is templated to the case's own concrete
 * ID, so what is clicked is exactly what PATS clicks.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Elements {

    private record Template(Pattern pattern, Locator locator) {
    }

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
        boolean xpath = kind.strip().toUpperCase(Locale.ROOT).equals("XPATH");
        Locator exact = Locators.BY_VALUE.get(wanted);
        if (exact != null && !exact.isTemplate() && (exact.kind() == Locator.Kind.XPATH) == xpath) {
            return exact;
        }
        if (!xpath) {
            for (Template template : Templates.ALL) {
                Matcher match = template.pattern().matcher(wanted);
                if (match.matches()) {
                    Object[] indices = new Object[match.groupCount()];
                    for (int i = 0; i < indices.length; i++) {
                        // The digits as written: KEY_00 is not KEY_0 (an int() once keyed one zero per 00 press).
                        indices[i] = match.group(i + 1);
                    }
                    return template.locator().at(indices);
                }
            }
            ContentFamily family = Families.MEMBERS.get(wanted);
            if (family != null) {
                return Locator.of(wanted, Locator.Kind.ID, family.name() + "(" + LocatorTypes.pyRepr(wanted) + ")",
                        family.group(), 0, false, false, "", "", List.of(), "");
            }
        }
        throw new UnknownElementException(kind + " " + LocatorTypes.pyRepr(wanted)
                + " is not in petrotest.pos.locators; regenerate the catalogue (tools/analysis/gen_locators.py)"
                + " rather than naming it anywhere else");
    }

    /** The templated ID families, built once: a family with only {@code {n}} slots, each literal spelling. */
    private static final class Templates {

        static final List<Template> ALL = build();

        private static List<Template> build() {
            List<Template> out = new ArrayList<>();
            for (Locator loc : Locators.ELEMENT_IDS) {
                if (!loc.isTemplate() || loc.placeholders().stream().anyMatch(slot -> !slot.equals("n"))) {
                    continue;
                }
                for (String literal : loc.literals()) {
                    out.add(new Template(LocatorTypes.templateRegex(literal), loc));
                }
            }
            return List.copyOf(out);
        }
    }

    /** The members of every {@link ContentFamily} the catalogue declares; the first family to name a member wins. */
    private static final class Families {

        static final Map<String, ContentFamily> MEMBERS = build();

        private static Map<String, ContentFamily> build() {
            Map<String, ContentFamily> members = new LinkedHashMap<>();
            for (Field field : Locators.class.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !ContentFamily.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    ContentFamily family = (ContentFamily) field.get(null);
                    for (String member : family.members()) {
                        members.putIfAbsent(member, family);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot read the catalogue's " + field.getName(), e);
                }
            }
            return Map.copyOf(members);
        }
    }
}
