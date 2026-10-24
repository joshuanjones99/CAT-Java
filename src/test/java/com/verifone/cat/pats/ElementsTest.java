package com.verifone.cat.pats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;

/**
 * {@link Elements}: a PATS element literal resolves through the generated catalogue or is refused. Every ID here is
 * read from {@link Locators}, so the test names no element itself.
 */
class ElementsTest {

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
}
