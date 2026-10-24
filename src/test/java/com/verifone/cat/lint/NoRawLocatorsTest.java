package com.verifone.cat.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.verifone.cat.lint.JavaSource.Literal;

/**
 * The Java counterpart of the Python CAT's no-raw-locators gate (DESIGN.md 4.2 rule 1; {@code tools/lint/no_raw_locators.py}).
 * A POS element ID or an XPath is named in one place, {@code pos/Locators.java} (generated into {@code pos/gen}), and
 * reached through a page object everywhere else.
 *
 * <p>Both halves, as the Python does: element IDs (a shape matcher, since the Python's measured catalogue is not in
 * this repository) and XPath or CSS-attribute selectors. Matching is on string literals only, as the Python's AST walk
 * does, so a comment that names an ID is not reported.
 *
 * <p>Scope is {@code src/main/java} and {@code src/test/java}. The catalogue, its generator's output and this gate's
 * own tests are not scanned. {@link #EXEMPT} names the few whole files that are the locator layer or its unit tests,
 * with a reason each, as {@code tools/lint/allowlist.py} does.
 */
class NoRawLocatorsTest {

    private static final List<Path> ROOTS = List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));

    /** The one file that may hold a raw element ID or XPath, and the generator's output beside it. */
    private static final String CATALOGUE = "src/main/java/com/verifone/cat/pos/Locators.java";
    private static final String GENERATED = "src/main/java/com/verifone/cat/pos/gen/";
    private static final String OWN_GATE = "src/test/java/com/verifone/cat/lint/";

    /**
     * An exemption, as the Python allowlist's: a path, and the literal fragment it covers (an empty fragment is the
     * whole file), with the reason. Whole files are the locator layer and its unit tests; fragments are the few
     * literals that are not yet catalogue-backed (see the reasons).
     */
    record Exemption(String path, String fragment, String reason) {}

    static final List<Exemption> EXEMPT = List.of(
            new Exemption("src/main/java/com/verifone/cat/pos/Locator.java", "",
                    "renders a catalogue entry's selector ('xpath=' prefix): the locator layer itself, which the Python"
                            + " gate's scope (tests/) leaves out"),
            new Exemption("src/main/java/com/verifone/cat/pos/LocatorTypes.java", "",
                    "renders the [id=...] and [id^=...] selectors the driver builds: the locator layer itself"),
            new Exemption("src/test/java/com/verifone/cat/pos/LocatorTypesTest.java", "",
                    "unit test of the locator layer: it must name locators to assert on them; drives no POS"),
            new Exemption("src/test/java/com/verifone/cat/pos/driver/PosDriverTest.java", "",
                    "unit test of the CDP driver: asserts on the selectors it builds; drives no POS"),
            new Exemption("src/main/java/com/verifone/cat/pos/driver/PosDriver.java",
                    "SecondaryWebAppView_HostedApp_Frame",
                    "OPEN: the nested frame's name is a literal here, not yet read from the catalogue's FRAMES map"),
            new Exemption("src/main/java/com/verifone/cat/pos/driver/Scripts.java", "[id=\"${id}\"]",
                    "OPEN: the in-page script builds [id=...] selectors in JavaScript; the ids are passed in, not named"),
            new Exemption("src/main/java/com/verifone/cat/pos/fake/FakePosPage.java", "PosDouble_QuantityOverlay",
                    "the POS double's own chrome, which its comment says is not a catalogue ID; the double names it"));

    private static boolean exemptLiteral(String path, String value) {
        return EXEMPT.stream().anyMatch(e -> e.path().equals(path) && value.contains(e.fragment()));
    }

    /** The words a POS widget name carries (the Python's WIDGET_WORDS). */
    private static final String WIDGET_WORDS = String.join("|", "View", "Panel", "Widget", "Keypad", "KeyPad",
            "Overlay", "Btn", "Button", "Lbl", "Label", "Icon", "Container", "Frame", "Popup", "Dialog", "Menu",
            "Grid", "Txt", "Field", "Img", "Header", "Footer", "Row", "Cell", "List", "Box", "Screen", "Page",
            "Toolbar", "Card", "Dropdown", "Checkbox", "Input", "Bar", "Tab");

    /** Identifier-shaped element IDs the catalogue has not measured: mixed case, '_' segments, a widget word. */
    static final Pattern ELEMENT_ID = Pattern.compile(
            "(?<![A-Za-z0-9_])(?=[A-Za-z0-9_{}]*(?:" + WIDGET_WORDS + "))(?=[A-Za-z0-9_{}]*[a-z])"
                    + "[A-Z][A-Za-z0-9]*(?:_[A-Za-z0-9{}]+)+(?![A-Za-z0-9_])");

    /** XPath and raw-selector shapes, as the Python's _XPATH_PATTERNS (the element-ID shape is last). */
    private static final List<Pattern> SHAPES = List.of(
            // //div[...], //button, //*[@id=...]; http://host is not an XPath (the lookbehind rejects ':').
            Pattern.compile("(?<![:\\w])//(?:\\*|@?[A-Za-z][\\w.-]*)"),
            Pattern.compile("\\(\\s*//"),
            Pattern.compile("(?<!\\w)/html(?:/|\\[)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<!\\w)\\.//"),
            Pattern.compile("\\b(?:descendant|descendant-or-self|ancestor|ancestor-or-self|following-sibling"
                    + "|preceding-sibling|child|parent)::"),
            Pattern.compile("\\bxpath\\s*=\\s*"),
            Pattern.compile("\\[\\s*id\\s*[\\^*$~|]?="),
            ELEMENT_ID);

    @Test
    void noSourceOutsideTheCatalogueNamesAnElementIdOrAnXPath() throws IOException {
        List<String> found = new ArrayList<>();
        int scanned = 0;
        for (Path root : ROOTS) {
            for (Path file : LintFiles.files(root, name -> name.endsWith(".java"), NoRawLocatorsTest::exempt)) {
                scanned++;
                found.addAll(findingsIn(LintFiles.rel(file), Files.readString(file)));
            }
        }
        assertTrue(scanned > 0, "no sources scanned: the gate would pass on nothing");
        assertTrue(found.isEmpty(), "raw POS element ID or XPath outside pos/Locators.java; name it in the catalogue and"
                + " reach it through a page object:\n  " + String.join("\n  ", found));
    }

    private static boolean exempt(String rel) {
        return rel.equals(CATALOGUE) || rel.startsWith(GENERATED) || rel.startsWith(OWN_GATE)
                || EXEMPT.stream().anyMatch(e -> e.path().equals(rel) && e.fragment().isEmpty());
    }

    /** Each string literal in {@code source} that has a shape, as {@code path:line shape in "literal"}. */
    static List<String> findingsIn(String path, String source) {
        List<String> found = new ArrayList<>();
        for (Literal literal : JavaSource.of(source).literals) {
            if (exemptLiteral(path, literal.value())) {
                continue;
            }
            for (Pattern shape : SHAPES) {
                var m = shape.matcher(literal.value());
                if (m.find()) {
                    found.add(path + ":" + literal.line() + " " + m.group() + " in \"" + literal.value() + "\"");
                    break;
                }
            }
        }
        return found;
    }

    @Test
    void theGateCatchesTheShapesThePythonGateCatches() {
        String source = """
                class X {
                    String a = "ItemPanel_145397";
                    String b = "(//div[@class='left-card-container'])[1]";
                    String c = "[id=\\"ReceiptView_ItemLineDescription_0\\"]";
                    String d = "xpath=//button[text()='Total']";
                    String e = "/html/body/div";
                    String f = "descendant::span";
                }
                """;

        List<String> found = findingsIn("X.java", source);

        assertFalse(found.isEmpty());
        assertEquals(6, found.size(), found.toString());
    }

    @Test
    void ordinaryStringsCommentsAndURLsAreNotFlagged() {
        String source = """
                class Y {
                    // ItemPanel_145397 in a comment is not a selector
                    String url = "http://127.0.0.1:8801/health";
                    String ssl = "ssl://commander.invalid:8883";
                    String shim = "shim_503";
                    String cadence = "cadence_55_02";
                    String tran = "TRAN# 1010181";
                    String lower = "fuel_panel";
                }
                """;

        assertEquals(List.of(), findingsIn("Y.java", source));
    }
}
