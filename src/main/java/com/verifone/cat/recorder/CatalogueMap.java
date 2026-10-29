package com.verifone.cat.recorder;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.LocatorTypes;
import com.verifone.cat.pos.Locators;

/**
 * Observed element to catalogue name: the reverse map (CAT_RESULTS_AND_GENERATE.md 3.3). Port of
 * {@code petrotest.recorder.catalogue}.
 *
 * <p>A recorded tap is a DOM fact: an element id, or an id-less button's text. A generated case may only speak in
 * catalogue names, so each observation is looked up in {@link Locators}, read at class load and never copied.
 *
 * <p><b>Never a guess.</b> An id or text nothing in the catalogue names is {@code unmapped}; one two entries name is
 * {@code ambiguous} and lists both. Precedence: an exact literal beats a template ({@code {n}} only ever matches digits).
 * An element with an id is looked up by that id only: an unknown id is {@code unmapped}, never retried by its text. Text
 * is for id-less elements, and only the {@code //tag[text()='...']} shape is matched by it.
 */
public final class CatalogueMap {

    /** {@code //button[text()='Total']} or {@code //button[text()='{label}']}. */
    private static final Pattern TEXT_XPATH = Pattern.compile("^//(\\w+)\\[text\\(\\)='([^']*)'\\]$");

    private static final List<Locator> ALL = allLocators();
    private static final CatalogueMap STANDARD = new CatalogueMap(ALL);
    private static final Map<String, Locator> BY_NAME = byName(ALL);

    private record Template(Pattern pattern, Locator locator) {}

    private record TextKey(String tag, String text) {}

    /** How many locators the map was built from: the recording header's {@code catalogue.entries}. */
    private final int entries;
    private final Map<String, List<Locator>> exactIds = new LinkedHashMap<>();
    private final List<Template> idTemplates = new ArrayList<>();
    private final Map<TextKey, List<Locator>> exactText = new LinkedHashMap<>();
    private final Map<String, List<Locator>> textTemplates = new LinkedHashMap<>();

    /** The index over {@code catalogue}. */
    public CatalogueMap(List<Locator> catalogue) {
        this.entries = catalogue.size();
        for (Locator loc : catalogue) {
            if (loc.kind() == Locator.Kind.ID) {
                for (String literal : loc.literals()) {
                    if (loc.isTemplate()) {
                        idTemplates.add(new Template(LocatorTypes.templateRegex(literal), loc));
                    } else {
                        exactIds.computeIfAbsent(literal, k -> new ArrayList<>()).add(loc);
                    }
                }
                continue;
            }
            Matcher match = TEXT_XPATH.matcher(loc.value());
            if (!match.matches()) {
                continue;
            }
            String tag = match.group(1);
            String text = match.group(2);
            if (loc.placeholders().equals(List.of("label")) && text.equals("{label}")) {
                textTemplates.computeIfAbsent(tag, k -> new ArrayList<>()).add(loc);
            } else if (!loc.isTemplate()) {
                exactText.computeIfAbsent(new TextKey(tag, text), k -> new ArrayList<>()).add(loc);
            }
        }
    }

    /** The map over the whole catalogue, built once. */
    public static CatalogueMap standard() {
        return STANDARD;
    }

    public int entries() {
        return entries;
    }

    /** An element by its id. */
    public Mapping byId(String elementId) {
        List<Locator> exact = exactIds.getOrDefault(elementId, List.of());
        if (!exact.isEmpty()) {
            return one(exact, "id");
        }
        List<Locator> hitLocators = new ArrayList<>();
        List<List<String>> hitGroups = new ArrayList<>();
        for (Template template : idTemplates) {
            Matcher match = template.pattern().matcher(elementId);
            if (match.matches()) {
                List<String> groups = new ArrayList<>();
                for (int i = 1; i <= match.groupCount(); i++) {
                    groups.add(match.group(i));
                }
                hitLocators.add(template.locator());
                hitGroups.add(groups);
            }
        }
        TreeSet<String> names = new TreeSet<>();
        hitLocators.forEach(loc -> names.add(loc.name()));
        if (names.size() > 1) {
            return Mapping.of("ambiguous", List.copyOf(names), "two templates match");
        }
        if (hitLocators.isEmpty()) {
            return Mapping.of("unmapped", List.of(), "no catalogue entry has this id");
        }
        Locator loc = hitLocators.get(0);
        List<String> groups = hitGroups.get(0);
        List<String> slots = loc.placeholders();
        List<String> args = new ArrayList<>();
        Map<String, String> kwargs = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            if ("n".equals(slots.get(i))) {
                args.add(groups.get(i));
            } else {
                kwargs.put(slots.get(i), groups.get(i));
            }
        }
        return Mapping.mapped(loc.name(), loc.group(), args, kwargs, "id");
    }

    /** An id-less element, by its own text nodes: what {@code text()='...'} compares. */
    public Mapping byText(String tag, List<String> texts) {
        String lower = tag.toLowerCase(java.util.Locale.ROOT);
        TreeMap<String, Locator> exact = new TreeMap<>();
        for (String text : texts) {
            for (Locator loc : exactText.getOrDefault(new TextKey(lower, text), List.of())) {
                exact.put(loc.name(), loc);
            }
        }
        if (!exact.isEmpty()) {
            return one(new ArrayList<>(exact.values()), "text");
        }
        List<Locator> families = textTemplates.getOrDefault(lower, List.of());
        List<String> labels = texts.stream().filter(t -> t != null && !t.isEmpty()).toList();
        if (families.size() == 1 && labels.size() == 1) {
            Locator loc = families.get(0);
            return Mapping.mapped(loc.name(), loc.group(), List.of(), Map.of("label", labels.get(0)), "text");
        }
        if (families.size() > 1 || labels.size() > 1) {
            TreeSet<String> names = new TreeSet<>();
            families.forEach(loc -> names.add(loc.name()));
            return Mapping.of("ambiguous", List.copyOf(names),
                    labels.size() + " text node(s), " + families.size() + " text famil(ies)");
        }
        return Mapping.of("unmapped", List.of(), "no catalogue entry names a <" + lower + "> by this text");
    }

    /** By id when there is one (an unknown id is {@code unmapped}, not retried by text), else by its own text. */
    public Mapping observe(String elementId, String tag, List<String> texts) {
        if (elementId != null && !elementId.isEmpty()) {
            return byId(elementId);
        }
        if (tag != null && !tag.isEmpty() && texts != null && !texts.isEmpty()) {
            return byText(tag, texts);
        }
        return Mapping.of("unmapped", List.of(), "no id, and no text of its own");
    }

    private static Mapping one(List<Locator> found, String via) {
        TreeSet<String> names = new TreeSet<>();
        found.forEach(loc -> names.add(loc.name()));
        if (names.size() > 1) {
            return Mapping.of("ambiguous", List.copyOf(names), "two entries share it");
        }
        Locator loc = found.get(0);
        return Mapping.mapped(loc.name(), loc.group(), List.of(), Map.of(), via);
    }

    /** The text a literal {@code //tag[text()='...']} locator names, else null. */
    public static String textOf(String name) {
        Locator loc = BY_NAME.get(name);
        if (loc == null || loc.kind() != Locator.Kind.XPATH) {
            return null;
        }
        Matcher match = TEXT_XPATH.matcher(loc.value());
        return match.matches() && !loc.isTemplate() ? match.group(2) : null;
    }

    /** Every {@link Locator} the catalogue defines: {@code ALL}, then the text families and other constants after it. */
    static List<Locator> allLocators() {
        Map<String, Locator> seen = new LinkedHashMap<>();
        for (Locator loc : Locators.ALL) {
            seen.putIfAbsent(loc.name(), loc);
        }
        for (Field field : Locators.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Locator.class) {
                try {
                    Locator loc = (Locator) field.get(null);
                    if (loc != null && loc.name() != null && !loc.name().isEmpty()) {
                        seen.putIfAbsent(loc.name(), loc);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot read " + field.getName(), e);
                }
            }
        }
        return List.copyOf(seen.values());
    }

    private static Map<String, Locator> byName(List<Locator> all) {
        Map<String, Locator> out = new LinkedHashMap<>();
        for (Locator loc : all) {
            out.put(loc.name(), loc);
        }
        return out;
    }
}
