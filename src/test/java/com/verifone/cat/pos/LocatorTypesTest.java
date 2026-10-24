package com.verifone.cat.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The locator types, ported from the Python CAT's {@code tests/unit/test_pos_locators.py}
 * (the tests of {@code petrotest.pos.locator_types}). These run against the generated catalogue,
 * so they also exercise the names the parity test compares.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the selectors are strings, checked here as strings only.
 */
class LocatorTypesTest {

    // -- templating ----------------------------------------------------------

    @Test
    void indexSubstitution() {
        assertEquals("ItemPanel_145397", Locators.ITEM_PANEL_N.at(145397).value());
    }

    @Test
    void selectorIsAnAttributeSelectorNotAHash() {
        assertEquals("[id=\"ItemPanel_145397\"]", Locators.ITEM_PANEL_N.at(145397).selector());
    }

    @Test
    void unrenderedTemplateRefusesToProduceASelector() {
        LocatorError error = assertThrows(LocatorError.class, () -> Locators.ITEM_PANEL_N.selector());
        assertTrue(error.getMessage().contains("unresolved slot"), error.getMessage());
    }

    @Test
    void arityIsChecked() {
        LocatorError error = assertThrows(LocatorError.class, () -> Locators.ITEM_PANEL_N.at(1, 2));
        assertTrue(error.getMessage().contains("expected 1 index"), error.getMessage());
    }

    @Test
    void twoSlotTemplate() {
        assertEquals("ReceiptView_DiscountItemAmountLbl_0_3",
                Locators.RECEIPT_VIEW_DISCOUNT_ITEM_AMOUNT_LBL_N_N.at(0, 3).value());
    }

    @Test
    void constantSlotsAreRepinnedByTheGenerator() {
        assertEquals(List.of("n"), Locators.FUEL_BUTTON.defaultLocator().placeholders());
        assertEquals("FuelPanel_0_FuelButton_1_LabelPosition",
                Locators.FUEL_BUTTON.at(1).defaultLocator().value());
    }

    @Test
    void prefixSelectorForAnIndexedFamily() {
        assertEquals("[id^=\"ReceiptView_ItemLineDescription_\"]",
                Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.prefixSelector());
    }

    @Test
    void prefixSelectorRejectsANonFamily() {
        LocatorError error = assertThrows(LocatorError.class,
                () -> Locators.OVERLAY_MSG_VIEW_CANCEL_BTN.prefixSelector());
        assertTrue(error.getMessage().contains("not an indexed family"), error.getMessage());
    }

    @Test
    void indexOfRecoversTheRowNumber() {
        Locator family = Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N;
        assertEquals(OptionalLong.of(12), family.indexOf("ReceiptView_ItemLineDescription_12"));
        assertEquals(OptionalLong.empty(), family.indexOf("ReceiptView_ItemLineAmount_12"));
    }

    @Test
    void templateRegexMatchesDigitsAtNAndAnyTextAtANamedSlot() {
        Matcher keyed = LocatorTypes.templateRegex("KEY_{n}").matcher("KEY_00");
        assertTrue(keyed.matches());
        assertEquals("00", keyed.group(1));
        assertFalse(LocatorTypes.templateRegex("KEY_{n}").matcher("KEY_OK").matches());

        Matcher mixed = LocatorTypes.templateRegex("A.{n}_{label}").matcher("A.3_Pizza&Burger");
        assertTrue(mixed.matches());
        assertEquals("3", mixed.group(1));
        assertEquals("Pizza&Burger", mixed.group(2));

        // the literal part is quoted, so the dot is a dot
        assertFalse(LocatorTypes.templateRegex("A.{n}").matcher("AB3").matches());
    }

    @Test
    void xpathSelectorIsPrefixedForPlaywright() {
        assertEquals("xpath=//button[text()='Total']",
                Locators.XP_BUTTON_BY_TEXT.atNamed(Map.of("label", "Total")).selector());
    }

    // -- entity twins and content families -----------------------------------

    @Test
    void theAmpersandTabIsOneLocatorSpeltAsParsed() {
        Locator tab = Locators.PRODUCT_MENU_PANEL_TAB_PIZZA_AND_BURGER;
        assertEquals(Set.of("ProductMenuPanelTab_Pizza&Burger"), Set.copyOf(tab.literals()));
        assertTrue(Locators.ALL.stream().noneMatch(loc -> loc.value().contains("&amp;")));
    }

    @Test
    void contentFamilyBuildsIdsFromProductNames() {
        Locator built = Locators.SCO_EXPANDED_MENU_ANY.of("Reese's puff", "Label");
        assertEquals("SCO_EXPANDED_MENU_ANY(\"Reese's puff\")", built.name());
    }

    @Test
    void contentFamilyOffersBothAmpersandSpellings() {
        Locator built = Locators.PRODUCT_MENU_PANEL_TAB_ANY.of("Pizza&Burger");
        assertTrue(built.literals().contains("ProductMenuPanelTab_Pizza&amp;Burger"), built.literals().toString());
    }

    // -- build variants ------------------------------------------------------

    @Test
    void buildVariantAcceptsTheDesignMdCallVerbatim() {
        BuildVariant variant = LocatorTypes.buildVariant(
                "FuelPanel_0_FuelButton_{n}_LabelPosition",
                "FuelPanel_0_FuelButton_{n}_Data",
                "057.00", "", "", 0, 0);
        assertEquals("FuelPanel_0_FuelButton_{n}_LabelPosition", variant.resolve("057.00").value());
        assertEquals("FuelPanel_0_FuelButton_{n}_Data", variant.resolve("056.99").value());
    }

    @ParameterizedTest
    @CsvSource({
        "057.00, _LabelPosition",
        "057.01, _LabelPosition",
        "058.00, _LabelPosition",
        "100.00, _LabelPosition",
        "056.99, _Data",
        "056.00, _Data",
        "012.34, _Data",
    })
    void fuelButtonResolvesByBuildVersion(String version, String expectedSuffix) {
        assertTrue(Locators.FUEL_BUTTON.resolve(version).value().endsWith(expectedSuffix),
                version + " -> " + Locators.FUEL_BUTTON.resolve(version).value());
    }

    @Test
    void unknownBuildVersionTakesTheDocumentedSpelling() {
        assertTrue(Locators.FUEL_BUTTON.resolve(null).value().endsWith("_LabelPosition"));
    }

    @Test
    void variantTemplatingSurvivesResolutionEitherWayRound() {
        assertEquals("FuelPanel_0_FuelButton_4_Data", Locators.FUEL_BUTTON.at(4).resolve("056.00").value());
        assertEquals("FuelPanel_0_FuelButton_4_Data", Locators.FUEL_BUTTON.resolve("056.00").at(4).value());
    }

    @Test
    void paddedVersionsCompareNumericallyNotLexically() {
        assertTrue(LocatorTypes.isBefore(LocatorTypes.parseVersion("057.00"),
                LocatorTypes.parseVersion("057.1")));
        assertFalse(LocatorTypes.isBefore(LocatorTypes.parseVersion("057.1"),
                LocatorTypes.parseVersion("057.00")));
        assertTrue(LocatorTypes.isBefore(LocatorTypes.parseVersion("9.0"), LocatorTypes.parseVersion("10.0")));
    }

    @Test
    void unparseableVersionIsReportedNotGuessed() {
        LocatorError error = assertThrows(LocatorError.class,
                () -> LocatorTypes.parseVersion("release-candidate"));
        assertTrue(error.getMessage().contains("cannot parse build version"), error.getMessage());
    }

    @Test
    void parseVersionDropsTrailingJunkAndKeepsDigits() {
        assertEquals(List.of(BigInteger.valueOf(57), BigInteger.ZERO), LocatorTypes.parseVersion("057.00"));
        assertEquals(List.of(BigInteger.valueOf(57)), LocatorTypes.parseVersion("57.x.3"));
    }

    // -- deprecation and unresolved controls ---------------------------------

    @Test
    void unresolvedLocatorRaisesWithItsReason() {
        Locator loc = LocatorTypes.unresolved("GRADE_TAB", "Phase 0 rig inspection (DESIGN.md section 10).");
        UnresolvedLocatorError error = assertThrows(UnresolvedLocatorError.class, loc::selector);
        assertTrue(error.getMessage().contains("Phase 0"), error.getMessage());
        assertEquals(loc, loc.at(1));
    }

    @Test
    void deprecatedLocatorsNameTheirReplacement() {
        BuildVariant variant = Locators.FUEL_BUTTON;
        assertTrue(variant.legacy().deprecated());
        assertEquals(variant.defaultLocator().value(), variant.legacy().replacement());
        assertFalse(variant.defaultLocator().deprecated());
    }

    @Test
    void byValueKeepsTheFirstLocatorThatClaimsALiteral() {
        Map<String, Locator> index = LocatorTypes.byValue(List.of(
                Locator.of("X", Locator.Kind.ID, "FIRST", "g", 1, false, false, null, null, List.of(), ""),
                Locator.of("X", Locator.Kind.ID, "SECOND", "g", 1, false, false, null, null, List.of(), "")));
        assertEquals("FIRST", index.get("X").name());
    }

    // -- immutability and value equality -------------------------------------

    @Test
    void templatingReturnsANewLocatorAndNeverMutatesTheCatalogueEntry() {
        Locator original = Locators.ITEM_PANEL_N;
        original.at(1);
        assertEquals("ItemPanel_{n}", original.value());
        assertThrows(UnsupportedOperationException.class, () -> original.alternates().add("nope"));
    }

    @Test
    void locatorEqualityIsByValue() {
        Locator a = Locator.of("ItemPanel_1", Locator.Kind.ID, "", "", 0, false, false, null, null, List.of(), "");
        Locator b = Locator.of("ItemPanel_1", Locator.Kind.ID, "", "", 0, false, false, null, null, List.of(), "");
        assertEquals(a, b);
    }

    @Test
    void pyReprMatchesPythonForQuotedLabels() {
        assertEquals("\"Reese's puff\"", LocatorTypes.pyRepr("Reese's puff"));
        assertEquals("'Veg Pizza'", LocatorTypes.pyRepr("Veg Pizza"));
        assertEquals("'a\\\\b'", LocatorTypes.pyRepr("a\\b"));
    }
}
