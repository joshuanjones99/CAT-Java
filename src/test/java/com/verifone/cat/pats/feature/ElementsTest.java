package com.verifone.cat.pats.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Port of the element half of {@code tests/unit/test_pats_feature.py}: a PATS literal round-trips through its catalogue
 * name, and a name never carries the raw id. Drives the real catalogue ({@link Locators}).
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
        assertThrows(UnreadableActionException.class,
                () -> Elements.elementName("no-such-catalogue-literal", "ID"));
    }

    @Test
    void aNameThatIsNotAControlIsRefusedOnTheWayBack() {
        assertThrows(IllegalArgumentException.class, () -> Elements.elementValue("NOT_A_CONTROL_ANYWHERE"));
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

    @Test
    void theRawIdIsNeverTheNameAndTheNameIsNeverEmpty() {
        String name = Elements.elementName(Locators.ITEM_PANEL_N.at(145397).value(), "ID");
        assertNotEquals("", name);
        assertEquals("ITEM_PANEL_N(145397)", name);
    }
}
