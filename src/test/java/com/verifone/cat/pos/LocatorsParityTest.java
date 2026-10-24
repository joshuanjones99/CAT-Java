package com.verifone.cat.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The key evidence: the Java catalogue has the same names, and the same selectors, as the Python
 * CAT's {@code petrotest.pos.locators}.
 *
 * <p>The Python side is imported, not parsed: {@code src/test/resources/pos/dump_locators.py} runs
 * under the Python CAT's interpreter and prints every locator's fields, its selectors (plain and
 * rendered with sample indices and labels), its prefix selector, its recovered index, the content
 * families, the build variants resolved at fifteen versions, and the registries in order. Both
 * sides build the same JSON shape, and the test compares them name by name and field by field.
 *
 * <p>Skipped (JUnit assumption, not failure) when the Python CAT is not checked out at
 * {@code CAT_PYTHON_SRC} (default {@code C:\gitrepos\CAT\src}) or its interpreter is not at
 * {@code CAT_PYTHON} (default {@code C:\gitrepos\CAT\.venv\Scripts\python.exe}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: agreement here is agreement with the Python catalogue, which is
 * itself unverified against a live Topaz.
 */
class LocatorsParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path PYTHON_SRC = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON_SRC", "C:\\gitrepos\\CAT\\src"));

    private static final Path PYTHON = Path.of(
            System.getenv().getOrDefault("CAT_PYTHON", "C:\\gitrepos\\CAT\\.venv\\Scripts\\python.exe"));

    private static final Path DUMP = Path.of("src", "test", "resources", "pos", "dump_locators.py");

    /** Mirrors {@code VERSIONS} in dump_locators.py, in order. */
    private static final String[] VERSIONS = {
        null, "057.00", "057.01", "058.00", "100.00", "056.99", "056.00", "012.34", "57", "057.1",
        "9.0", "10.0", " 057.00 ", "", "release-candidate", "57.x.3",
    };

    private static final int SAMPLE_INDEX = 7;
    private static final String SAMPLE_LABEL = "Lbl";

    @Test
    void javaCatalogueHasThePythonCatalogueNamesAndSelectors() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PYTHON_SRC.resolve("petrotest").resolve("pos").resolve("locators.py")),
                "the Python CAT's locators.py is not present under " + PYTHON_SRC);
        Assumptions.assumeTrue(Files.isRegularFile(PYTHON), "the Python CAT's interpreter is not at " + PYTHON);
        Assumptions.assumeTrue(Files.isRegularFile(DUMP), "dump_locators.py is missing from " + DUMP);

        JsonNode python = runPythonDump();
        List<String> diffs = new ArrayList<>();
        Map<String, Object> java = javaDump(diffs);
        JsonNode javaTree = MAPPER.valueToTree(java);

        // 1. the same public names, in the same catalogue
        assertEquals(python.get("public_names"), javaTree.get("public_names"),
                "the public constant names differ between Python and Java");

        // 2. every locator, family and build variant, field by field
        compareObjects("locators", python.get("locators"), javaTree.get("locators"), diffs);
        compareObjects("content_families", python.get("content_families"), javaTree.get("content_families"), diffs);
        compareObjects("build_variants", python.get("build_variants"), javaTree.get("build_variants"), diffs);

        // 3. the registries: order and membership. The catalogue's provenance keys are the one
        // deliberate difference: they name the tool that produced the file, which is the Java
        // generator here and the Python one there. Every other catalogue value must match.
        for (JsonNode catalogue : List.of(python.at("/registries/catalogue"), javaTree.at("/registries/catalogue"))) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) catalogue).remove(List.of("generator", "source"));
        }
        compareObjects("registries", python.get("registries"), javaTree.get("registries"), diffs);

        // 4. the counts the header quotes
        compareObjects("counts", python.get("counts"), javaTree.get("counts"), diffs);

        JsonNode counts = python.get("counts");
        System.out.println("POS locator parity: " + counts.get("locators").asInt() + " locators, "
                + counts.get("content_families").asInt() + " content families, "
                + counts.get("build_variants").asInt() + " build variants, "
                + counts.get("element_ids").asInt() + " element IDs, "
                + counts.get("xpaths").asInt() + " XPaths, "
                + counts.get("all").asInt() + " in ALL; "
                + (diffs.isEmpty() ? "0 differences" : diffs.size() + " differences"));
        assertTrue(diffs.isEmpty(), () -> diffs.size() + " parity difference(s):\n" + String.join("\n", diffs));
    }

    // -- the Python side --------------------------------------------------------

    private static JsonNode runPythonDump() throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                PYTHON.toString(), "-I", DUMP.toAbsolutePath().toString(), PYTHON_SRC.toString());
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        byte[] out = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the Python dump did not finish in 120 s");
        assertEquals(0, process.exitValue(), "the Python dump failed");
        return MAPPER.readTree(new String(out, StandardCharsets.UTF_8));
    }

    // -- the Java side, shaped like dump_locators.py -----------------------------

    private static Map<String, Object> javaDump(List<String> diffs) throws ReflectiveOperationException {
        List<String> publicNames = new ArrayList<>();
        Map<String, Object> locators = new LinkedHashMap<>();
        Map<String, Object> families = new LinkedHashMap<>();
        Map<String, Object> variants = new LinkedHashMap<>();
        for (Field field : Locators.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            publicNames.add(field.getName());
            Object value = field.get(null);
            if (value instanceof Locator loc) {
                checkFieldName(field, loc.name(), diffs);
                locators.put(field.getName(), dumpLocator(loc));
            } else if (value instanceof ContentFamily family) {
                checkFieldName(field, family.name(), diffs);
                families.put(field.getName(), dumpFamily(family));
            } else if (value instanceof BuildVariant variant) {
                checkFieldName(field, variant.name(), diffs);
                variants.put(field.getName(), dumpVariant(variant));
            }
        }
        publicNames.sort(Comparator.naturalOrder());

        Map<String, Object> registries = new LinkedHashMap<>();
        registries.put("element_ids", names(Locators.ELEMENT_IDS));
        registries.put("xpaths", names(Locators.XPATHS));
        registries.put("all", names(Locators.ALL));
        registries.put("indexed_xpath_families", names(Locators.INDEXED_XPATH_FAMILIES));
        List<Object> frames = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : Locators.FRAMES.entrySet()) {
            frames.add(List.of(e.getKey(), e.getValue()));
        }
        registries.put("frames", frames);
        registries.put("deprecated_id_prefixes", Locators.DEPRECATED_ID_PREFIXES);
        List<Object> templates = new ArrayList<>();
        for (Map.Entry<String, String> e : Locators.DEPRECATED_ID_TEMPLATES.entrySet()) {
            templates.add(List.of(e.getKey(), e.getValue()));
        }
        registries.put("deprecated_id_templates", templates);
        Map<String, Object> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Locator>> e : Locators.GROUPS.entrySet()) {
            groups.put(e.getKey(), names(e.getValue()));
        }
        registries.put("groups", groups);
        Map<String, Object> byValue = new LinkedHashMap<>();
        for (Map.Entry<String, Locator> e : Locators.BY_VALUE.entrySet()) {
            byValue.put(e.getKey(), e.getValue().name());
        }
        registries.put("by_value", byValue);
        Map<String, Object> byName = new LinkedHashMap<>();
        for (Map.Entry<String, Locator> e : Locators.BY_NAME.entrySet()) {
            byName.put(e.getKey(), e.getValue().name());
        }
        registries.put("by_name", byName);
        registries.put("catalogue", new LinkedHashMap<>(Locators.CATALOGUE));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("locators", locators.size());
        counts.put("content_families", families.size());
        counts.put("build_variants", variants.size());
        counts.put("element_ids", Locators.ELEMENT_IDS.size());
        counts.put("xpaths", Locators.XPATHS.size());
        counts.put("all", Locators.ALL.size());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("public_names", publicNames);
        doc.put("locators", locators);
        doc.put("content_families", families);
        doc.put("build_variants", variants);
        doc.put("registries", registries);
        doc.put("counts", counts);
        return doc;
    }

    private static void checkFieldName(Field field, String name, List<String> diffs) {
        if (!field.getName().equals(name)) {
            diffs.add("field " + field.getName() + " carries the name " + name + " (Python: name == attribute)");
        }
    }

    private static Map<String, Object> dumpLocator(Locator loc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", loc.value());
        m.put("kind", loc.kind() == Locator.Kind.ID ? "id" : "xpath");
        m.put("name", loc.name());
        m.put("group", loc.group());
        m.put("refs", loc.refs());
        m.put("documented", loc.documented());
        m.put("deprecated", loc.deprecated());
        m.put("replacement", loc.replacement());
        m.put("frame", loc.frame());
        m.put("alternates", loc.alternates());
        m.put("note", loc.note());
        m.put("placeholders", loc.placeholders());
        m.put("selector", attempt(loc::selector));
        m.put("rendered_selector", renderedSelector(loc));
        m.put("prefix_selector", attempt(loc::prefixSelector));
        m.put("rendered_index", renderedIndex(loc));
        return m;
    }

    private static Map<String, Object> dumpFamily(ContentFamily family) {
        Map<String, Object> of = new LinkedHashMap<>();
        of.put("Veg Pizza|Label", dumpBuilt(family.of("Veg Pizza", "Label")));
        of.put("Reese's puff|Label", dumpBuilt(family.of("Reese's puff", "Label")));
        of.put("Pizza&Burger", dumpBuilt(family.of("Pizza&Burger")));
        of.put("Pizza&amp;Burger", dumpBuilt(family.of("Pizza&amp;Burger")));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix", family.prefix());
        m.put("name", family.name());
        m.put("group", family.group());
        m.put("members", family.members());
        m.put("of", of);
        return m;
    }

    private static Map<String, Object> dumpBuilt(Locator built) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", built.value());
        m.put("name", built.name());
        m.put("group", built.group());
        m.put("alternates", built.alternates());
        return m;
    }

    private static Map<String, Object> dumpVariant(BuildVariant variant) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (String version : VERSIONS) {
            String key = version == null ? "<none>" : version;
            try {
                resolved.put(key, variant.resolve(version).value());
            } catch (LocatorError e) {
                resolved.put(key, null);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", variant.name());
        m.put("since", variant.since());
        m.put("default", dumpLocator(variant.defaultLocator()));
        m.put("legacy", dumpLocator(variant.legacy()));
        m.put("resolve", resolved);
        return m;
    }

    /** The sample arguments: positional index slots take 7, 8, ...; every named slot takes "Lbl". */
    private static Object[] sampleIndices(Locator loc) {
        long count = loc.placeholders().stream().filter("n"::equals).count();
        Object[] indices = new Object[(int) count];
        for (int i = 0; i < count; i++) {
            indices[i] = SAMPLE_INDEX + i;
        }
        return indices;
    }

    private static Map<String, String> sampleNamed(Locator loc) {
        Map<String, String> named = new LinkedHashMap<>();
        for (String slot : loc.placeholders()) {
            if (!"n".equals(slot)) {
                named.put(slot, SAMPLE_LABEL);
            }
        }
        return named;
    }

    private static String renderedSelector(Locator loc) {
        if (!loc.isTemplate()) {
            return attempt(loc::selector);
        }
        try {
            return loc.atNamed(sampleNamed(loc), sampleIndices(loc)).selector();
        } catch (LocatorError e) {
            return null;
        }
    }

    private static Integer renderedIndex(Locator loc) {
        if (!loc.isTemplate()) {
            return null;
        }
        try {
            Locator rendered = loc.atNamed(sampleNamed(loc), sampleIndices(loc));
            OptionalLong index = loc.indexOf(rendered.value());
            return index.isPresent() ? Math.toIntExact(index.getAsLong()) : null;
        } catch (LocatorError | ArithmeticException e) {
            return null;
        }
    }

    /** A selector or prefix that raises is null on both sides, as the Python dump records it. */
    private static String attempt(Selector selector) {
        try {
            return selector.get();
        } catch (LocatorError e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface Selector {
        String get();
    }

    private static List<String> names(List<Locator> locators) {
        List<String> names = new ArrayList<>();
        for (Locator loc : locators) {
            names.add(loc.name());
        }
        return names;
    }

    // -- comparison ------------------------------------------------------------

    private static void compareObjects(String where, JsonNode python, JsonNode java, List<String> diffs) {
        if (python == null || java == null) {
            diffs.add(where + ": absent on one side (python " + (python == null ? "absent" : "present")
                    + ", java " + (java == null ? "absent" : "present") + ")");
            return;
        }
        if (python.isObject() && java.isObject()) {
            Set<String> keys = new TreeSet<>();
            python.fieldNames().forEachRemaining(keys::add);
            java.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode p = python.get(key);
                JsonNode j = java.get(key);
                if (p == null) {
                    diffs.add(where + "." + key + ": only in Java");
                } else if (j == null) {
                    diffs.add(where + "." + key + ": only in Python");
                } else {
                    compareObjects(where + "." + key, p, j, diffs);
                }
            }
        } else if (!python.equals(java)) {
            diffs.add(where + ": python=" + python + " java=" + java);
        }
    }
}
