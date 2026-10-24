package com.verifone.cat.pats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pos.ContentFamily;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link Elements}: a PATS element literal resolves through the generated catalogue or is refused, and a catalogue
 * name round-trips to the literal. Every ID here is read from {@link Locators}, so the test names no element itself.
 * Port of the element half of {@code tests/unit/test_pats_feature.py} and of {@code tests/unit/test_pats_elements}.
 */
class ElementsTest {

    static Stream<Arguments> elements() {
        return Stream.of(
                Arguments.of(Locators.ITEM_PANEL_N.at(145397), "ID"),
                Arguments.of(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.at("00"), "ID"),
                Arguments.of(Locators.RECEIPT_VIEW_DISCOUNT_ITEM_NAME_LBL_N_N.at(0, 1), "ID"),
                Arguments.of(Locators.XP_BUTTON_TEXT_CASH, "XPATH"),
                Arguments.of(Locators.OVERLAY_MSG_VIEW_OK_BTN, "ID"));
    }

    @Test
    void aConcreteIdResolvesThroughItsCatalogueTemplate() {
        String item = Locators.ITEM_PANEL_N.at(145397).value();
        Locator resolved = Elements.resolve(item);
        assertEquals(List.of(item), resolved.literals());
        assertEquals(Locators.ITEM_PANEL_N.name(), resolved.name());
    }

    @Test
    void aZeroPaddedIndexIsKeptAsWritten() {
        // KEY_00 became KEY_0 through an int() once: every 00 press keyed one zero.
        String doubleZero = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.at("00").value();
        assertEquals(List.of(doubleZero), Elements.resolve(doubleZero).literals());
    }

    @Test
    void anXpathResolvesOnlyAsAnXpath() {
        Locator total = Elements.resolve(Locators.XP_BUTTON_TEXT_TOTAL.value(), "XPATH");
        assertEquals(Locator.Kind.XPATH, total.kind());
    }

    @Test
    void anElementTheCatalogueDoesNotHoldIsRefusedNotGuessed() {
        String item = Locators.ITEM_PANEL_N.at(145397).value();
        assertThrows(UnknownElementException.class, () -> Elements.resolve(item + "x"));
    }

    @Test
    void aTypeMismatchIsRefused() {
        // The total is an XPath in the catalogue: it is not an ID.
        String total = Locators.XP_BUTTON_TEXT_TOTAL.value();
        assertThrows(UnknownElementException.class, () -> Elements.resolve(total, "ID"));
    }

    @ParameterizedTest
    @MethodSource("elements")
    void anElementRoundTripsThroughItsCatalogueName(Locator locator, String kind) {
        String name = Elements.elementName(locator.value(), kind);
        assertFalse(name.contains(locator.value()), "the step names the control, never its id: " + name);
        Elements.Ref ref = Elements.elementValue(name);
        assertEquals(locator.value(), ref.literal());
        assertEquals(kind, ref.kind());
    }

    @Test
    void keyZeroZeroStaysTwoZeros() {
        String raw = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.at("00").value();
        assertTrue(Elements.elementName(raw, "ID").endsWith("(00)"));
    }

    @Test
    void aLiteralTheCatalogueDoesNotHoldIsRefused() {
        assertThrows(UnknownElementException.class,
                () -> Elements.elementName("no-such-catalogue-literal", "ID"));
    }

    @Test
    void aNameThatIsNotAControlIsRefusedOnTheWayBack() {
        assertThrows(IllegalArgumentException.class, () -> Elements.elementValue("NOT_A_CONTROL_ANYWHERE"));
    }

    @Test
    void theRawIdIsNeverTheNameAndTheNameIsNeverEmpty() {
        String name = Elements.elementName(Locators.ITEM_PANEL_N.at(145397).value(), "ID");
        assertNotEquals("", name);
        assertEquals("ITEM_PANEL_N(145397)", name);
    }

    /**
     * The generated catalogue's content families carry no member lists, so the family branch of {@link Elements} has
     * nothing to resolve today; this asserts the members that exist are unique, so a member can name one family only.
     */
    @Test
    void noContentMemberIsHeldByTwoFamilies() {
        List<String> members = Elements.familyMemberList();
        assertEquals(members.size(), new HashSet<>(members).size());
    }

    /**
     * The catalogue has no ordered registry of its content families, so {@link Elements} indexes them by name, in name
     * order: the first family to hold a member is the one first in that order, on every run. This asserts the order.
     */
    @Test
    void contentFamiliesAreIndexedInNameOrder() {
        List<String> names = Elements.familyNames();
        assertFalse(names.isEmpty());
        assertEquals(names.stream().sorted().toList(), names);
    }

    /**
     * {@link Elements} names a control through {@link Locators#BY_NAME}, the catalogue's ordered registry, and every
     * registry entry is the constant of its name. Every {@link Locator} constant, registered or not, must still name
     * back to its own value, as the Python's {@code element_value} does for every module attribute.
     */
    @Test
    void everyLocatorConstantNamesBackToItsValueAndTheRegistryHoldsTheConstants()
            throws IllegalAccessException, NoSuchFieldException {
        for (var entry : Locators.BY_NAME.entrySet()) {
            assertEquals(Locators.class.getField(entry.getKey()).get(null), entry.getValue(), entry.getKey());
        }
        int constants = 0;
        for (Field field : Locators.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Locator.class.isAssignableFrom(field.getType())) {
                continue;
            }
            constants++;
            Locator locator = (Locator) field.get(null);
            assertEquals(locator.value(), Elements.elementValue(field.getName()).literal(), field.getName());
        }
        assertTrue(constants > Locators.BY_NAME.size() / 2);
    }

    /** The families the reflection finds are the catalogue's own, each under its own name. */
    @Test
    void everyContentFamilyConstantIsIndexedUnderItsName() throws IllegalAccessException {
        for (Field field : Locators.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !ContentFamily.class.isAssignableFrom(field.getType())) {
                continue;
            }
            ContentFamily family = (ContentFamily) field.get(null);
            assertTrue(Elements.familyNames().contains(family.name()), family.name());
        }
    }
}
