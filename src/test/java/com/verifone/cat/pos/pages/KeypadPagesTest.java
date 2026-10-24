package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import com.microsoft.playwright.Page;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The numeric keypad page object in headless Chromium, over each namespace's catalogue IDs. The Python
 * suite's keypad cases ran against mocks; here the keys are markup that records what was pressed.
 *
 * <p>Evidence about this repository only.
 */
class KeypadPagesTest {

    private static final Duration SHORT = Duration.ofMillis(500);

    private static PagesBrowser browsers;

    @BeforeAll
    static void openBrowser() {
        browsers = PagesBrowser.open();
    }

    @AfterAll
    static void closeBrowser() {
        browsers.close();
    }

    @BeforeEach
    void needsChromium() {
        Assumptions.assumeTrue(browsers.available(), PagesBrowser.SKIP_REASON);
    }

    @Test
    void enterTypesEveryDigitThenPressesOk() {
        try (Page page = browsers.newPage()) {
            page.setContent(keypad(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                    Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK));
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page));

            keypad.enter("1250", null);

            assertEquals("1250", page.evaluate("window.typed"));
            assertEquals(true, page.evaluate("window.confirmed"));
        }
    }

    @Test
    void enterWithoutConfirmLeavesOkUntouched() {
        try (Page page = browsers.newPage()) {
            page.setContent(keypad(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                    Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK));
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page));

            keypad.enter("7", false, null);

            assertEquals("7", page.evaluate("window.typed"));
            assertEquals(false, page.evaluate("window.confirmed"));
        }
    }

    @Test
    void enterRefusesALetterAndPressesNothingOnIt() {
        try (Page page = browsers.newPage()) {
            page.setContent(keypad(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                    Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK));
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page));

            assertThrows(IllegalArgumentException.class, () -> keypad.enter("1a", null));
            assertEquals(false, page.evaluate("window.confirmed"));
        }
    }

    @Test
    void theVirtualKeyboardNamespaceUsesItsOwnIds() {
        try (Page page = browsers.newPage()) {
            page.setContent(keypad(Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                    Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK));
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page), KeypadNamespace.VIRTUAL_KEYBOARD);

            keypad.enter("42", null);

            assertEquals("42", page.evaluate("window.typed"));
            assertEquals(true, page.evaluate("window.confirmed"));
        }
    }

    @Test
    void theCalculatorHasNoCancelKeyAndSaysSo() {
        try (Page page = browsers.newPage()) {
            page.setContent(keypad(Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                    Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK));
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page), KeypadNamespace.CALCULATOR);

            assertThrows(UnsupportedOperationException.class, keypad::cancelKey);
            assertThrows(UnsupportedOperationException.class, () -> keypad.cancel(SHORT));
        }
    }

    @Test
    void expectOpenPassesOnceTheFirstDigitShows() {
        try (Page page = browsers.newPage()) {
            page.setContent(keypad(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N,
                    Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK));
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page));

            keypad.expectOpen(null);
            assertTrue(keypad.digit("1").isPresent());
        }
    }

    @Test
    void okHidesWhenTheKeypadIsNotOnTheScreen() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            Keypad keypad = new Keypad(PagesBrowser.driverOn(page));

            assertThrows(AssertionError.class, () -> keypad.expectOpen(SHORT));
            assertFalse(keypad.okKey().isPresent());
        }
    }

    /** Digit keys, an OK key and a CANCEL key in the namespace's own IDs, recording what was pressed. */
    private static String keypad(com.verifone.cat.pos.Locator digits, com.verifone.cat.pos.Locator ok) {
        StringBuilder html = new StringBuilder("<html><body><script>window.typed=''; window.confirmed=false;"
                + "</script>");
        for (int digit = 0; digit <= 9; digit++) {
            html.append("<button id=\"").append(digits.at(String.valueOf(digit)).value())
                    .append("\" onclick=\"window.typed += '").append(digit).append("'\">")
                    .append(digit).append("</button>");
        }
        html.append("<button id=\"").append(ok.value())
                .append("\" onclick=\"window.confirmed = true\">OK</button>");
        return html.append("</body></html>").toString();
    }
}
