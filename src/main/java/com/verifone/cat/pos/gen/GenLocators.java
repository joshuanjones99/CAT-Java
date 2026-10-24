package com.verifone.cat.pos.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates {@code com.verifone.cat.pos.Locators} from {@code locators.json}.
 *
 * <p>Port of {@code tools/analysis/gen_locators.py} in the Python CAT. The same names come out:
 * {@code const_name}, the name allocator, the content families, the XPath row families, the text
 * families, the frame candidates, the deprecation lists and the setup locators are all taken from
 * the Python generator, in the same order, so the parity test can compare them name by name.
 *
 * <p>Four transformations, as in the Python: constant slots are re-pinned from the JSON; the
 * FuelButton drift becomes a build variant; indexed XPath selectors collapse into a row family;
 * product-slug groups become content families. The emitted Java formatting is this class's own,
 * not Python's {@code ruff format}: the staleness test compares a fresh generation with the
 * committed file, so only determinism matters.
 *
 * <p>Run after a build: {@code java -cp target\classes;target\lib\* com.verifone.cat.pos.gen.GenLocators}
 * (from the project root). {@code --check} fails if the committed file is stale.
 * UNVERIFIED AGAINST HARDWARE: the output is measurement data, not device behaviour.
 */
public final class GenLocators {

    /** The corpus catalogue, copied byte for byte from the Python CAT's {@code analysis/locators.json}. */
    public static final Path DEFAULT_SOURCE = Path.of("src", "main", "resources", "pos", "locators.json");

    /** Where the generated class is written. */
    public static final Path DEFAULT_TARGET =
            Path.of("src", "main", "java", "com", "verifone", "cat", "pos", "Locators.java");

    /** Python's {@code _CHAR_MAP}, in its insertion order (the order of the replacements matters). */
    private static final String[][] CHAR_MAP = {
        {"&amp;", "_AND_"},
        {"&", "_AND_"},
        {"#", "_HASH_"},
        {"+", "_PLUS_"},
        {"=", "_EQ_"},
        {"$", "_S_"},
        {"/", "_"},
        {"'", ""},
        {"(", "_"},
        {")", "_"},
        {"-", "_"},
        {".", "_"},
        {";", "_"},
        {",", "_"},
        {" ", "_"},
        {"{n}", "_N_"},
    };

    private static final Pattern SNAKE_1 = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern SNAKE_2 = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    private static final Pattern NON_IDENT = Pattern.compile("[^A-Za-z0-9_]");
    private static final Pattern UNDERSCORES = Pattern.compile("_+");
    private static final Pattern EDGE_UNDERSCORES = Pattern.compile("^_+|_+$");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("_\\d+$");
    private static final Pattern INDEX_RE =
            Pattern.compile("^\\((.+)\\)\\[(\\d+)\\]$", Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
    private static final Pattern TAG_RE = Pattern.compile("//(\\*|\\w+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TEXT_EXACT = Pattern.compile(
            "//(\\w+)\\[text\\(\\)\\s*=\\s*'[^']*'\\]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TEXT_CONTAINS = Pattern.compile(
            "//(\\w+)\\[contains\\s*\\(\\s*text\\(\\)\\s*,\\s*'[^']*'\\)\\]", Pattern.UNICODE_CHARACTER_CLASS);

    /** Python's {@code SETUP_LOCATORS}: (constant, selector, kind, PATS source). Copied verbatim. */
    private static final String[][] SETUP_LOCATORS = {
        {"PAIRING_PAIR_DEVICE", "vfi_pairdevice_lbl", "id", "RegisterPairingUtil.PAIR_POS_WEBELEMENT_ID"},
        {"PAIRING_KEYPAD_DIGIT", "//button[@class=\"keypad_btn\" and @value=\"{digit}\"]", "xpath",
            "RegisterPairingUtil.POS_KEYPAD_BUTTON_XPATH"},
        {"PAIRING_KEYPAD_OK", "vfi_ok_btn", "id", "RegisterPairingUtil.PAIR_POS_KEYPAD_WEBELEMENT_OK"},
        {"PAIRING_PAIRED", "//label[@class='instruction' and text()='Successfully Paired']", "xpath",
            "RegisterPairingUtil.SUCCESSFULLY_PAIRED_POS_XPATH"},
        {"PAIRING_PAIRED_OK", "//button[@class='vfi-grnButton' and text()='OK']", "xpath",
            "RegisterPairingUtil.SUCCESSFULLY_PAIRED_OK_XPATH"},
        {"PAIRING_REFUSED_OK", "//button[@class='vfi-button' and text()='OK']", "xpath",
            "Reg101's pairingrefused.html, 2026-09-29; no PATS code names it"},
        {"SYSTEM_MESSAGES_PANEL_VIEW_LABEL2", "SystemMessagesPanelView_Label2", "id",
            "Reg101's DOM, 2026-09-21; no corpus case names it"},
        {"FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_STACKED", "FuelPanel_0_FuelButton_{n}_LabelStacked", "id",
            "Reg101's DOM, 2026-10-01; no corpus case names it"},
        {"APP_SELECTOR_SWITCHER_VIEW_MAIN_PANEL", "AppSelectorSwitcherView_MainPanel", "id",
            "POSWebDriver.APP_SELECTOR_LOCATOR_VALUE, PATS's appSelector.locatorValue"},
    };

    private GenLocators() {}

    /** Command line: {@code [--check] [--source PATH] [--target PATH]}. */
    public static void main(String[] args) throws IOException {
        boolean check = false;
        Path source = DEFAULT_SOURCE;
        Path target = DEFAULT_TARGET;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> check = true;
                case "--source" -> source = Path.of(args[++i]);
                case "--target" -> target = Path.of(args[++i]);
                default -> {
                    System.err.println("usage: GenLocators [--check] [--source PATH] [--target PATH]");
                    System.exit(2);
                }
            }
        }
        JsonNode data = new ObjectMapper().readTree(Files.readString(source, StandardCharsets.UTF_8));
        String text = build(data);
        if (check) {
            String existing = Files.exists(target)
                    ? Files.readString(target, StandardCharsets.UTF_8).replace("\r\n", "\n")
                    : "";
            if (!existing.equals(text)) {
                System.err.println("STALE: " + target + " differs from a fresh generation");
                System.exit(1);
            }
            System.out.println("OK: " + target + " is up to date");
            return;
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
        System.out.println("wrote " + target + " (" + text.length() + " chars, "
                + countDefinitions(text) + " definitions)");
    }

    private static int countDefinitions(String text) {
        int n = 0;
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("    public static final Locator ")
                    || line.startsWith("    public static final ContentFamily ")
                    || line.startsWith("    public static final BuildVariant ")) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------------
    // Naming (Python: const_name, NameAllocator, _tidy)
    // ---------------------------------------------------------------------

    /** Python's {@code const_name}: a pronounceable upper-case identifier, optionally prefixed. */
    static String constName(String raw, String prefix) {
        String text = raw;
        for (String[] kv : CHAR_MAP) {
            text = text.replace(kv[0], kv[1]);
        }
        text = SNAKE_2.matcher(SNAKE_1.matcher(text).replaceAll("$1_$2")).replaceAll("$1_$2");
        text = NON_IDENT.matcher(text).replaceAll("_").toUpperCase(Locale.ROOT);
        text = EDGE_UNDERSCORES.matcher(UNDERSCORES.matcher(text).replaceAll("_")).replaceAll("");
        if (text.isEmpty() || Character.isDigit(text.charAt(0))) {
            text = "X_" + text;
        }
        return prefix.isEmpty() ? text : prefix + text;
    }

    /** Python's {@code NameAllocator}: unique, deterministic names, first come first served. */
    static final class Alloc {
        private final Set<String> taken = new HashSet<>();

        String take(String base) {
            String name = base;
            int n = 2;
            while (taken.contains(name)) {
                name = base + "__" + n;
                n++;
            }
            taken.add(name);
            return name;
        }
    }

    /** Python's {@code _tidy}: drop repeated words, cap the length. */
    static String tidy(String name) {
        Set<String> seen = new HashSet<>();
        List<String> words = new ArrayList<>();
        for (String word : name.split("_", -1)) {
            if (!word.isEmpty() && seen.add(word)) {
                words.add(word);
            }
        }
        return String.join("_", words.subList(0, Math.min(10, words.size())));
    }

    // ---------------------------------------------------------------------
    // Element IDs (Python: repin_constant_slots, dominant_frame, entity_twin, common_prefix)
    // ---------------------------------------------------------------------

    /** Substitute back any {@code {n}} slot the corpus shows is always the same constant value. */
    static String repinConstantSlots(String template, JsonNode slots) {
        StringBuilder out = new StringBuilder();
        int slotIndex = 0;
        int pos = 0;
        while (true) {
            int hit = template.indexOf("{n}", pos);
            if (hit < 0) {
                out.append(template, pos, template.length());
                break;
            }
            out.append(template, pos, hit);
            JsonNode constant = slotIndex < slots.size() ? slots.get(slotIndex).get("constant") : null;
            out.append(constant == null || constant.isNull() ? "{n}" : constant.asText());
            slotIndex++;
            pos = hit + 3;
        }
        return out.toString();
    }

    /** The frame the majority of references sit behind, or null for the main document. */
    static String dominantFrame(JsonNode frames) {
        if (frames == null || !frames.isObject() || frames.size() == 0) {
            return null;
        }
        String bestName = null;
        long bestCount = 0;
        long total = 0;
        Iterator<Map.Entry<String, JsonNode>> it = frames.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            long count = e.getValue().asLong();
            total += count;
            if (bestName == null || count > bestCount) {
                bestName = e.getKey();
                bestCount = count;
            }
        }
        if ("(none)".equals(bestName) || bestCount < 0.6 * total) {
            return null;
        }
        return bestName;
    }

    /** Map {@code X&amp;Y} to {@code X&Y} where both spellings of one element exist. */
    static Map<String, String> entityTwin(List<JsonNode> entries) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode e : entries) {
            values.add(text(e, "template"));
        }
        Map<String, String> twin = new LinkedHashMap<>();
        for (String value : values) {
            if (value.contains("&amp;")) {
                String plain = value.replace("&amp;", "&");
                if (values.contains(plain)) {
                    twin.put(value, plain);
                }
            }
        }
        return twin;
    }

    /** The longest shared {@code Prefix_} among a group's templates, if it is worth having. */
    static String commonPrefix(List<String> sortedMembers) {
        if (sortedMembers.size() < 5) {
            return null;
        }
        String head = sortedMembers.get(0);
        for (String other : sortedMembers.subList(1, sortedMembers.size())) {
            while (!other.startsWith(head)) {
                head = head.substring(0, head.length() - 1);
                if (head.isEmpty()) {
                    return null;
                }
            }
        }
        head = head.substring(0, head.lastIndexOf('_') + 1);
        return head.length() > 4 ? head : null;
    }

    // ---------------------------------------------------------------------
    // XPaths (Python: xpath_name, index_families, frame candidates, text families)
    // ---------------------------------------------------------------------

    /** Python's {@code xpath_name}. Early returns skip the index suffix, as the Python does. */
    static String xpathName(String selector, JsonNode shape, String group) {
        Matcher m = INDEX_RE.matcher(selector);
        String body = selector;
        String index = null;
        if (m.lookingAt()) {
            body = m.group(1);
            index = m.group(2);
        }
        Matcher tagMatch = TAG_RE.matcher(body);
        String tag = (tagMatch.find() ? tagMatch.group(1) : "node").replace("*", "any");

        String wraps = truthy(shape, "wraps_element_id");
        if (wraps != null) {
            return constName(wraps, "XP_");
        }
        String predicate = truthy(shape, "text_predicate");
        if (predicate != null) {
            return constName(tag + "_text_" + predicate, "XP_");
        }
        List<String> classes = new ArrayList<>();
        JsonNode cssClasses = shape == null ? null : shape.get("css_classes");
        if (cssClasses != null && cssClasses.isArray()) {
            for (JsonNode c : cssClasses) {
                classes.add(c.asText());
            }
        }
        String base;
        if (!classes.isEmpty()) {
            base = constName(tag + "_" + String.join("_", classes), "XP_");
        } else if (shape != null && shape.path("absolute_dom_path").asBoolean(false)) {
            base = constName("abs_" + group, "XP_");
        } else {
            base = constName(group + "_" + tag, "XP_");
        }
        base = tidy(base);
        return index != null && !index.isEmpty() ? base + "_" + index : base;
    }

    /** Selector candidates for a PATS {@code SWITCH_FRAME} target, tried in order. */
    static List<String> frameCandidates(String name) {
        boolean mixedCase = name.chars().anyMatch(c -> Character.isLowerCase((char) c));
        if (mixedCase) {
            return List.of("[id=\"" + name + "\"]", "iframe[name=\"" + name + "\"]");
        }
        return List.of("iframe[name=\"" + name + "\"]", "iframe[id=\"" + name + "\"]", "[id=\"" + name + "\"]");
    }

    // ---------------------------------------------------------------------
    // Emission
    // ---------------------------------------------------------------------

    /** A Java string literal. Non-ASCII and control characters are escaped, so the source stays ASCII. */
    static String javaStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String listOf(List<String> items) {
        if (items.isEmpty()) {
            return "List.of()";
        }
        List<String> quoted = new ArrayList<>();
        for (String item : items) {
            quoted.add(javaStr(item));
        }
        return "List.of(" + String.join(", ", quoted) + ")";
    }

    /** One {@code Locator.of(...)} declaration, wrapped when it would exceed 100 columns. */
    static String locatorDecl(
            String name,
            String value,
            String kind,
            String group,
            int refs,
            boolean documented,
            boolean deprecated,
            String replacement,
            String frame,
            List<String> alternates,
            String note) {
        List<String> args = List.of(
                javaStr(value),
                "Locator.Kind." + kind,
                javaStr(name),
                javaStr(group),
                Integer.toString(refs),
                Boolean.toString(documented),
                Boolean.toString(deprecated),
                replacement == null ? "null" : javaStr(replacement),
                frame == null ? "null" : javaStr(frame),
                listOf(alternates),
                javaStr(note));
        String head = "    public static final Locator " + name + " =";
        String oneLine = head + " Locator.of(" + String.join(", ", args) + ");";
        if (oneLine.length() <= 100) {
            return oneLine + "\n";
        }
        StringBuilder sb = new StringBuilder(head).append("\n            Locator.of(\n");
        for (int i = 0; i < args.size(); i++) {
            sb.append("                    ").append(args.get(i));
            sb.append(i < args.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("            );\n").toString();
    }

    /** Python's {@code emit_locator} for a corpus entry (an element ID or an XPath). */
    private static String emitEntry(String name, JsonNode entry, String kind, String value, List<String> alternates) {
        boolean deprecated = entry.path("deprecated").asBoolean(false);
        String replacement = deprecated ? truthy(entry, "replacement") : null;
        String note = entry.path("non_locator").asBoolean(false)
                ? "corpus corruption: a data-file name passed in a WEB ELEMENT field"
                : "";
        return locatorDecl(
                name,
                value,
                kind,
                text(entry, "group"),
                entry.path("count").asInt(),
                entry.path("documented").asBoolean(false),
                deprecated,
                replacement,
                dominantFrame(entry.get("frames")),
                alternates,
                note);
    }

    // ---------------------------------------------------------------------
    // The whole module
    // ---------------------------------------------------------------------

    /** Generate the Java source for the catalogue. Deterministic: the same JSON gives the same text. */
    public static String build(JsonNode data) {
        JsonNode meta = data.path("generated_from");
        JsonNode totals = data.path("totals");
        List<JsonNode> ids = entries(data, "element_ids");
        List<JsonNode> xpaths = entries(data, "xpaths");
        Alloc alloc = new Alloc();
        StringBuilder out = new StringBuilder();

        header(out, meta, totals);
        fuelButton(out, data.path("fuel_button_drift"));
        alloc.take("FUEL_BUTTON");

        // --- element IDs, by group, groups by total usage (stable: JSON order breaks ties) ---
        Map<String, List<JsonNode>> byGroup = new LinkedHashMap<>();
        for (JsonNode e : ids) {
            byGroup.computeIfAbsent(text(e, "group"), k -> new ArrayList<>()).add(e);
        }
        List<String> groupOrder = new ArrayList<>(byGroup.keySet());
        groupOrder.sort(Comparator.comparingLong((String g) -> -sumCounts(byGroup.get(g))));

        Map<String, String> twins = new LinkedHashMap<>();
        for (List<JsonNode> entries : byGroup.values()) {
            twins.putAll(entityTwin(entries));
        }
        Set<String> twinPlains = new HashSet<>(twins.values());

        Map<String, List<String>> namesByGroup = new LinkedHashMap<>();
        List<Object[]> families = new ArrayList<>();
        out.append("\n// ---------------------------------------------------------------------------\n")
                .append("// Element IDs: ").append(ids.size()).append(" distinct templates behind ")
                .append(totals.path("element_id_refs").asLong()).append(" corpus references.\n")
                .append("// Ordered by group, groups by total usage. refs is the measured count at\n")
                .append("// the head above: it is the blast radius of renaming that element.\n")
                .append("// ---------------------------------------------------------------------------\n");

        for (String group : groupOrder) {
            List<JsonNode> entries = new ArrayList<>(byGroup.get(group));
            entries.sort(Comparator.comparingLong((JsonNode e) -> -e.path("count").asLong()));
            long total = sumCounts(entries);
            out.append("\n// -- ").append(group).append(": ").append(entries.size())
                    .append(" element(s), ").append(String.format(Locale.ROOT, "%,d", total))
                    .append(" refs --\n");
            List<String> names = namesByGroup.computeIfAbsent(group, k -> new ArrayList<>());
            for (JsonNode entry : entries) {
                String value = repinConstantSlots(text(entry, "template"), entry.path("slots"));
                if (twinPlains.contains(value)) {
                    continue; // the &amp; spelling is the primary; the plain twin is folded in
                }
                List<String> alternates = twins.containsKey(value) ? List.of(twins.get(value)) : List.of();
                String name = alloc.take(constName(value, ""));
                names.add(name);
                out.append(emitEntry(name, entry, "ID", value, alternates));
            }
            List<String> templates = new ArrayList<>();
            for (JsonNode e : entries) {
                templates.add(text(e, "template"));
            }
            templates.sort(null);
            String prefix = commonPrefix(templates);
            if (prefix != null && entries.size() >= 8) {
                String fam = alloc.take(constName(stripTrailingUnderscores(prefix), "") + "_ANY");
                families.add(new Object[] {fam, prefix, group, entries.size()});
            }
        }

        // --- content families ----------------------------------------------------------
        if (!families.isEmpty()) {
            out.append("\n// ---------------------------------------------------------------------------\n")
                    .append("// Content families: parameterised accessors, not constants.\n")
                    .append("// These groups name site content (products, menu tabs) rather than POS structure.\n")
                    .append("// .of(\"Veg Pizza\", suffix=\"Label\") builds the concrete ID; where the slug can\n")
                    .append("// contain an ampersand, both spellings are offered as alternates.\n")
                    .append("// ---------------------------------------------------------------------------\n");
            for (Object[] f : families) {
                out.append("// ").append(f[3]).append(" distinct literals in the corpus behind this prefix.\n");
                out.append("public static final ContentFamily ").append(f[0]).append(" = new ContentFamily(")
                        .append(javaStr((String) f[1])).append(", ").append(javaStr((String) f[0])).append(", ")
                        .append(javaStr((String) f[2])).append(");\n");
            }
        }

        // --- XPaths ----------------------------------------------------------------------
        out.append("\n// ---------------------------------------------------------------------------\n")
                .append("// XPath selectors\n")
                .append("// ---------------------------------------------------------------------------\n")
                .append("// Two shapes make a naive lint miss them: a leading parenthesis, and a leading /html/.\n")
                .append("// Selectors that merely wrap an @id are named XP_<ID>.\n");
        Map<String, List<JsonNode>> xpByGroup = new LinkedHashMap<>();
        for (JsonNode e : xpaths) {
            xpByGroup.computeIfAbsent(text(e, "group"), k -> new ArrayList<>()).add(e);
        }
        List<String> xpOrder = new ArrayList<>(xpByGroup.keySet());
        xpOrder.sort(Comparator.comparingLong((String g) -> -sumCounts(xpByGroup.get(g))));
        Map<String, List<String>> xpNamesByGroup = new LinkedHashMap<>();
        for (String group : xpOrder) {
            List<JsonNode> entries = new ArrayList<>(xpByGroup.get(group));
            entries.sort(Comparator.comparingLong((JsonNode e) -> -e.path("count").asLong()));
            out.append("\n// -- ").append(group).append(": ").append(entries.size())
                    .append(" selector(s), ").append(String.format(Locale.ROOT, "%,d", sumCounts(entries)))
                    .append(" refs --\n");
            List<String> names = xpNamesByGroup.computeIfAbsent(group, k -> new ArrayList<>());
            for (JsonNode entry : entries) {
                String selector = text(entry, "selector");
                String name = alloc.take(xpathName(selector, entry.path("shape"), group));
                names.add(name);
                out.append(emitEntry(name, entry, "XPATH", selector, List.of()));
            }
        }

        // --- indexed XPath row families ---------------------------------------------------
        Map<String, List<JsonNode>> rowFamilies = new LinkedHashMap<>();
        for (JsonNode e : xpaths) {
            Matcher m = INDEX_RE.matcher(text(e, "selector"));
            if (m.lookingAt()) {
                rowFamilies.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(e);
            }
        }
        rowFamilies.values().removeIf(members -> members.size() < 2);
        List<String> rowNames = new ArrayList<>();
        if (!rowFamilies.isEmpty()) {
            out.append("\n// ---------------------------------------------------------------------------\n")
                    .append("// Indexed XPath families\n")
                    .append("// ---------------------------------------------------------------------------\n")
                    .append("// (...)[1], (...)[2], ... collapsed into one templated locator. The individual\n")
                    .append("// constants above are kept; page objects should use these, because a row is an index.\n");
            List<Map.Entry<String, List<JsonNode>>> sorted = new ArrayList<>(rowFamilies.entrySet());
            sorted.sort(Comparator.comparingLong(
                    (Map.Entry<String, List<JsonNode>> en) -> -sumCounts(en.getValue())));
            for (Map.Entry<String, List<JsonNode>> en : sorted) {
                JsonNode first = en.getValue().get(0);
                String base = xpathName("(" + en.getKey() + ")[1]", first.path("shape"), text(first, "group"));
                base = TRAILING_DIGITS.matcher(base).replaceAll("");
                String name = alloc.take(base + "_ROW");
                rowNames.add(name);
                out.append(locatorDecl(name, "(" + en.getKey() + ")[{n}]", "XPATH", text(first, "group"),
                        (int) sumCounts(en.getValue()), false, false, null, null, List.of(), ""));
            }
            out.append("\npublic static final List<Locator> INDEXED_XPATH_FAMILIES = ")
                    .append(list(rowNames)).append(";\n");
        }

        // --- text-predicate families --------------------------------------------------------
        Map<String, long[]> exactText = new LinkedHashMap<>();
        Map<String, long[]> containsText = new LinkedHashMap<>();
        for (JsonNode e : xpaths) {
            String selector = text(e, "selector");
            Matcher exact = TEXT_EXACT.matcher(selector);
            Matcher contains = TEXT_CONTAINS.matcher(selector);
            if (exact.matches()) {
                tally(exactText, exact.group(1), e.path("count").asLong());
            } else if (contains.matches()) {
                tally(containsText, contains.group(1), e.path("count").asLong());
            }
        }
        if (!exactText.isEmpty() || !containsText.isEmpty()) {
            out.append("\n// ---------------------------------------------------------------------------\n")
                    .append("// Text-predicate families: content, not structure. One templated selector\n")
                    .append("// takes a {label}, e.g. //button[text()='Total'].\n")
                    .append("// ---------------------------------------------------------------------------\n");
            for (Map.Entry<String, long[]> en : sortedByRefs(exactText)) {
                String name = alloc.take(constName(en.getKey() + "_by_text", "XP_"));
                out.append("// ").append(en.getValue()[1]).append(" literal spellings\n");
                out.append(locatorDecl(name, "//" + en.getKey() + "[text()='{label}']", "XPATH",
                        "text_predicate", (int) en.getValue()[0], false, false, null, null, List.of(), ""));
            }
            for (Map.Entry<String, long[]> en : sortedByRefs(containsText)) {
                String name = alloc.take(constName(en.getKey() + "_containing_text", "XP_"));
                out.append("// ").append(en.getValue()[1]).append(" literal spellings\n");
                out.append(locatorDecl(name, "//" + en.getKey() + "[contains(text(),'{label}')]", "XPATH",
                        "text_predicate", (int) en.getValue()[0], false, false, null, null, List.of(), ""));
            }
        }

        // --- registries ----------------------------------------------------------------------
        List<String> allIds = new ArrayList<>();
        for (String group : groupOrder) {
            allIds.addAll(namesByGroup.getOrDefault(group, List.of()));
        }
        List<String> allXp = new ArrayList<>();
        for (String group : xpOrder) {
            allXp.addAll(xpNamesByGroup.getOrDefault(group, List.of()));
        }
        out.append("\n// ---------------------------------------------------------------------------\n")
                .append("// Registries: for page objects, the patsport port and the lint gate.\n")
                .append("// ---------------------------------------------------------------------------\n");
        out.append("\npublic static final List<Locator> ELEMENT_IDS = ").append(list(allIds)).append(";\n");
        out.append("\npublic static final List<Locator> XPATHS = ").append(list(allXp)).append(";\n");
        out.append("\npublic static final List<Locator> ALL = LocatorTypes.concat(ELEMENT_IDS, XPATHS);\n");
        out.append("\npublic static final Map<String, List<Locator>> GROUPS = groups();\n");
        out.append("\nprivate static Map<String, List<Locator>> groups() {\n")
                .append("    Map<String, List<Locator>> m = new LinkedHashMap<>();\n");
        for (String group : groupOrder) {
            List<String> names = namesByGroup.getOrDefault(group, List.of());
            if (!names.isEmpty()) {
                out.append("    m.put(").append(javaStr(group)).append(", ").append(list(names)).append(");\n");
            }
        }
        for (String group : xpOrder) {
            List<String> names = xpNamesByGroup.getOrDefault(group, List.of());
            if (!names.isEmpty()) {
                out.append("    m.put(").append(javaStr("xpath:" + group)).append(", ")
                        .append(list(names)).append(");\n");
            }
        }
        out.append("    return Collections.unmodifiableMap(m);\n}\n");
        out.append("\npublic static final Map<String, Locator> BY_VALUE = LocatorTypes.byValue(ALL);\n");
        out.append("\npublic static final Map<String, Locator> BY_NAME = LocatorTypes.byName(ALL);\n");

        // --- frames -------------------------------------------------------------------------
        Map<String, Long> frameCounts = new LinkedHashMap<>();
        for (JsonNode e : concat(ids, xpaths)) {
            JsonNode frames = e.get("frames");
            if (frames == null || !frames.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> it = frames.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!"(none)".equals(f.getKey())) {
                    frameCounts.merge(f.getKey(), f.getValue().asLong(), Long::sum);
                }
            }
        }
        List<Map.Entry<String, Long>> frameOrder = new ArrayList<>(frameCounts.entrySet());
        frameOrder.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        out.append("\n// ---------------------------------------------------------------------------\n")
                .append("// Frames. A hint, not ground truth: DEVICE_MONITORING is a PATS-side logical name.\n")
                .append("// Candidates are tried in order; a miss raises rather than matching nothing.\n")
                .append("// ---------------------------------------------------------------------------\n");
        out.append("\npublic static final Map<String, List<String>> FRAMES = frames();\n");
        out.append("\nprivate static Map<String, List<String>> frames() {\n")
                .append("    Map<String, List<String>> m = new LinkedHashMap<>();\n");
        for (Map.Entry<String, Long> f : frameOrder) {
            List<String> candidates = frameCandidates(f.getKey());
            out.append("    m.put(").append(javaStr(f.getKey())).append(", ")
                    .append(listOf(candidates)).append("); // ").append(String.format(Locale.ROOT, "%,d", f.getValue()))
                    .append(" refs\n");
        }
        out.append("    return Collections.unmodifiableMap(m);\n}\n");

        // --- deprecation, for the lint -----------------------------------------------------------
        deprecation(out, ids);

        // --- setup screens, not in the corpus ---------------------------------------------------
        out.append("\n// ---------------------------------------------------------------------------\n")
                .append("// Setup screens: not in the corpus. Copied from the PATS setup code named in each note.\n")
                .append("// Left out of the registries above, which count corpus references.\n")
                .append("// ---------------------------------------------------------------------------\n");
        for (String[] s : SETUP_LOCATORS) {
            out.append(locatorDecl(s[0], s[1], "id".equals(s[2]) ? "ID" : "XPATH", "setup", 0,
                    false, false, null, null, List.of(), s[3]));
        }

        out.append("\npublic static final Map<String, Object> CATALOGUE = Map.<String, Object>of(\n")
                .append("        \"source_head\", ").append(javaStr(text(meta, "head_full"))).append(",\n")
                .append("        \"distinct_element_ids\", ").append(ids.size()).append(",\n")
                .append("        \"distinct_xpaths\", ").append(xpaths.size()).append(",\n")
                .append("        \"element_id_refs\", ").append(totals.path("element_id_refs").asLong()).append(",\n")
                .append("        \"xpath_refs\", ").append(totals.path("xpath_refs").asLong()).append(",\n")
                .append("        \"generator\", \"com.verifone.cat.pos.gen.GenLocators\",\n")
                .append("        \"source\", \"src/main/resources/pos/locators.json\"\n")
                .append("    );\n");

        out.append("}\n");
        return moduleText(out);
    }

    /** Begin the file: the package, the imports and the class opening come before the body. */
    private static String moduleText(StringBuilder body) {
        String head = "package com.verifone.cat.pos;\n\n"
                + "import java.util.Collections;\n"
                + "import java.util.LinkedHashMap;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n";
        return head + body.toString();
    }

    private static void header(StringBuilder out, JsonNode meta, JsonNode totals) {
        out.append("/*\n")
                .append(" * GENERATED by com.verifone.cat.pos.gen.GenLocators from src/main/resources/pos/locators.json.\n")
                .append(" * DO NOT EDIT BY HAND. Re-run the generator instead (after a build; see the class Javadoc).\n")
                .append(" *\n")
                .append(" * Port of tools/analysis/gen_locators.py in the Python CAT. Source corpus: petroapps at ")
                .append(text(meta, "head_full")).append(".\n")
                .append(" * ").append(totals.path("element_id_refs").asLong()).append(" element-ID references, ")
                .append(totals.path("xpath_refs").asLong()).append(" raw XPath references;\n")
                .append(" * ").append(totals.path("distinct_element_ids").asLong()).append(" distinct element IDs, ")
                .append(totals.path("distinct_xpaths").asLong()).append(" distinct XPaths.\n")
                .append(" *\n")
                .append(" * This is the only class allowed to hold a raw POS element ID or XPath. refs is the\n")
                .append(" * blast radius of a rename. frame is a hint from the corpus, not ground truth.\n")
                .append(" * UNVERIFIED AGAINST HARDWARE: selectors are proven equal to the Python CAT's, not\n")
                .append(" * matched against a live Topaz.\n")
                .append(" */\n")
                .append("public final class Locators {\n\n")
                .append("    private Locators() {}\n");
    }

    private static void fuelButton(StringBuilder out, JsonNode drift) {
        JsonNode current = drift.path("FuelPanel_N_FuelButton_N_LabelPosition");
        JsonNode legacy = drift.path("FuelPanel_N_FuelButton_N_Data");
        out.append("\n// ---------------------------------------------------------------------------\n")
                .append("// Build-version drift (DESIGN.md section 5.2, the fix for section 3.1)\n")
                .append("// ---------------------------------------------------------------------------\n")
                .append("// The corpus has ").append(String.format(Locale.ROOT, "%,d", legacy.path("total").asLong()))
                .append(" references to the deprecated _Data suffix against ")
                .append(current.path("total").asLong()).append(" to the current one; in TestCases/ it is ")
                .append(legacy.path("tree").path("TestCases").asLong()).append(" against ")
                .append(current.path("tree").path("TestCases").asLong()).append(".\n")
                .append("// Resolved against the rig's build.expected_version by BuildVariant.resolve.\n")
                .append("public static final BuildVariant FUEL_BUTTON = LocatorTypes.buildVariant(\n")
                .append("        \"FuelPanel_0_FuelButton_{n}_LabelPosition\",\n")
                .append("        \"FuelPanel_0_FuelButton_{n}_Data\",\n")
                .append("        \"057.00\",\n")
                .append("        \"FUEL_BUTTON\",\n")
                .append("        \"fuel_panel\",\n")
                .append("        ").append(current.path("total").asLong()).append(",\n")
                .append("        ").append(legacy.path("total").asLong()).append(");\n");
    }

    private static void deprecation(StringBuilder out, List<JsonNode> ids) {
        Map<String, String> repinned = new LinkedHashMap<>();
        for (JsonNode e : ids) {
            repinned.put(text(e, "template"), repinConstantSlots(text(e, "template"), e.path("slots")));
        }
        List<String> live = new ArrayList<>();
        List<JsonNode> deprecated = new ArrayList<>();
        for (JsonNode e : ids) {
            if (e.path("deprecated").asBoolean(false)) {
                deprecated.add(e);
            } else {
                live.add(repinned.get(text(e, "template")));
            }
        }
        TreeSet<String> prefixes = new TreeSet<>();
        List<String[]> templates = new ArrayList<>();
        for (JsonNode entry : deprecated) {
            String value = repinned.get(text(entry, "template"));
            int brace = value.indexOf('{');
            String head = brace < 0 ? value : value.substring(0, brace);
            boolean prefixOnly = !head.equals(value) && live.stream().noneMatch(o -> o.startsWith(head));
            if (prefixOnly) {
                prefixes.add(head);
            } else {
                String replacement = truthy(entry, "replacement");
                String repl = replacement == null ? "" : replacement;
                templates.add(new String[] {value, repinned.getOrDefault(repl, repl)});
            }
        }
        templates.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        List<String> quotedPrefixes = new ArrayList<>();
        for (String prefix : prefixes) {
            quotedPrefixes.add(javaStr(prefix));
        }
        out.append("\n// Deprecation, for the lint gate. Match by PREFIX: {n} stands for any run of digits.\n")
                .append("public static final List<String> DEPRECATED_ID_PREFIXES = ")
                .append(list(quotedPrefixes)).append(";\n");
        out.append("\npublic static final Map<String, String> DEPRECATED_ID_TEMPLATES = deprecatedIdTemplates();\n");
        out.append("\nprivate static Map<String, String> deprecatedIdTemplates() {\n")
                .append("    Map<String, String> m = new LinkedHashMap<>();\n");
        for (String[] t : templates) {
            out.append("    m.put(").append(javaStr(t[0])).append(", ").append(javaStr(t[1])).append(");\n");
        }
        out.append("    return Collections.unmodifiableMap(m);\n}\n");
    }

    private static String list(List<String> names) {
        if (names.isEmpty()) {
            return "List.of()";
        }
        return "List.of(\n" + String.join(",\n", names.stream().map(n -> "        " + n).toList()) + ")";
    }

    private static String stripTrailingUnderscores(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '_') {
            end--;
        }
        return s.substring(0, end);
    }

    private static void tally(Map<String, long[]> into, String tag, long count) {
        long[] acc = into.computeIfAbsent(tag, k -> new long[2]);
        acc[0] += count;
        acc[1] += 1;
    }

    private static List<Map.Entry<String, long[]>> sortedByRefs(Map<String, long[]> counts) {
        List<Map.Entry<String, long[]>> out = new ArrayList<>(counts.entrySet());
        out.sort(Comparator.comparingLong((Map.Entry<String, long[]> en) -> -en.getValue()[0]));
        return out;
    }

    private static List<JsonNode> concat(List<JsonNode> first, List<JsonNode> second) {
        List<JsonNode> all = new ArrayList<>(first);
        all.addAll(second);
        return Collections.unmodifiableList(all);
    }

    private static List<JsonNode> entries(JsonNode data, String field) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : data.path(field)) {
            out.add(e);
        }
        return out;
    }

    private static long sumCounts(List<JsonNode> entries) {
        long total = 0;
        for (JsonNode e : entries) {
            total += e.path("count").asLong();
        }
        return total;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** The field's text when it is present and non-empty (Python truthiness), else null. */
    private static String truthy(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isEmpty()) {
            return null;
        }
        return v.asText();
    }
}
