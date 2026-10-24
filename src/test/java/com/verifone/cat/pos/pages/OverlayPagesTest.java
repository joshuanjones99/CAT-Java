package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.NoSuchElementException;

import com.microsoft.playwright.Page;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/**
 * The overlay and review-menu page objects in headless Chromium. Ported from the overlay and stacked-menu
 * cases of the Python suite (test_pos_stacked_menus, the overlay tests), with the mocks replaced by markup.
 *
 * <p>Evidence about this repository only.
 */
class OverlayPagesTest {

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
    void dismissPressesCancelWhenItIsPresentAndWaitsForTheOverlayToGo() throws IOException {
        try (Page page = browsers.newPage()) {
            page.setContent(overlay(true, true));
            PosDriver driver = PagesBrowser.driverOn(page);

            new Overlay(driver).dismiss(null);

            assertEquals("cancel", page.evaluate("window.pressed"));
        }
    }

    @Test
    void dismissPressesOkWhenCancelIsAbsent() {
        try (Page page = browsers.newPage()) {
            page.setContent(overlay(true, false));
            PosDriver driver = PagesBrowser.driverOn(page);

            new Overlay(driver).dismiss(null);

            assertEquals("ok", page.evaluate("window.pressed"));
        }
    }

    @Test
    void expectMatchesSubstringsAndRefusesAMismatch() {
        try (Page page = browsers.newPage()) {
            page.setContent(overlay(true, true));
            Overlay overlay = new Overlay(PagesBrowser.driverOn(page));

            overlay.expect("CRED", "VOLUME LIMIT", null, null, false, null);

            assertThrows(AssertionError.class,
                    () -> overlay.expect(null, "NOT THERE", null, null, false, SHORT));
        }
    }

    @Test
    void isOpenFollowsTheTitle() {
        try (Page page = browsers.newPage()) {
            page.setContent(overlay(true, true));
            Overlay overlay = new Overlay(PagesBrowser.driverOn(page));
            assertTrue(overlay.isOpen());

            page.setContent(overlay(false, false));
            assertFalse(overlay.isOpen());
        }
    }

    @Test
    void selectByTextFindsTheRowWhoseTextContainsTheSubstring() {
        try (Page page = browsers.newPage()) {
            page.setContent(reviewMenu("CHECK", "Coupon", "Gift Card"));
            ReviewOverlay review = new ReviewOverlay(PagesBrowser.driverOn(page));

            review.selectByText("coupon", null);

            assertEquals("Coupon", page.evaluate("window.picked"));
        }
    }

    @Test
    void selectByTextWaitsForRowsThatRenderLate() {
        try (Page page = browsers.newPage()) {
            page.setContent(overlayMenuLater());
            ReviewOverlay review = new ReviewOverlay(PagesBrowser.driverOn(page));

            review.selectByText("late", null);

            assertEquals("Late Row", page.evaluate("window.picked"));
        }
    }

    @Test
    void selectByTextNamesWhatItSawWhenNoRowMatches() {
        try (Page page = browsers.newPage()) {
            page.setContent(reviewMenu("CHECK"));
            ReviewOverlay review = new ReviewOverlay(PagesBrowser.driverOn(page));

            NoSuchElementException refused = assertThrows(NoSuchElementException.class,
                    () -> review.selectByText("VOUCHER", SHORT));
            assertTrue(refused.getMessage().contains("CHECK"), refused.getMessage());
        }
    }

    @Test
    void closeToMainMenuPopsStackedSubMenusUntilOnlyTheMainMenuShows() {
        try (Page page = browsers.newPage()) {
            page.setContent(stackedMenus());
            FunctionMenu menu = new FunctionMenu(PagesBrowser.driverOn(page));
            assertEquals(2, menu.headers().size());

            menu.closeToMainMenu(null);

            assertEquals(java.util.List.of(FunctionMenu.MAIN_MENU), menu.headers());
        }
    }

    private static String overlay(boolean titled, boolean cancel) {
        String title = titled
                ? "<div id=\"" + Locators.OVERLAY_MSG_VIEW_TITLE.value() + "\">CREDIT</div>"
                : "";
        String line1 = "<div id=\"" + Locators.OVERLAY_MSG_VIEW_LINE1.value() + "\">VOLUME LIMIT 30</div>";
        String cancelButton = cancel
                ? "<button id=\"" + Locators.OVERLAY_MSG_VIEW_CANCEL_BTN.value()
                        + "\" onclick=\"window.pressed='cancel'; hide()\">Cancel</button>"
                : "";
        String okButton = "<button id=\"" + Locators.OVERLAY_MSG_VIEW_OK_BTN.value()
                + "\" onclick=\"window.pressed='ok'; hide()\">OK</button>";
        return "<html><body>" + title + line1 + cancelButton + okButton
                + "<script>function hide(){ var t=document.getElementById('"
                + Locators.OVERLAY_MSG_VIEW_TITLE.value() + "'); if (t) t.remove(); }</script>"
                + "</body></html>";
    }

    private static String reviewMenu(String... rows) {
        StringBuilder html = new StringBuilder("<html><body>");
        int n = 1;
        for (String row : rows) {
            html.append("<div id=\"").append(Locators.REVIEW_OVERLAY_VIEW_ITEM_N.at(n++).value())
                    .append("\" onclick=\"window.picked='").append(row).append("'\">")
                    .append(row).append("</div>");
        }
        return html.append("</body></html>").toString();
    }

    private static String overlayMenuLater() {
        String id = Locators.REVIEW_OVERLAY_VIEW_ITEM_N.at(7).value();
        return "<html><body><script>setTimeout(function(){ var d=document.createElement('div');"
                + "d.id='" + id + "'; d.textContent='Late Row';"
                + "d.onclick=function(){ window.picked='Late Row'; };"
                + "document.body.appendChild(d); }, 300);</script></body></html>";
    }

    private static String stackedMenus() {
        String header = Locators.FUNCTION_MENU_HEADER_LABEL.value();
        String cross = Locators.FUNCTION_MENU_CROSS_BTN.value();
        return "<html><body>"
                + "<div id=\"" + header + "\">Main Menu</div>"
                + "<div id=\"" + header + "\">EPS Network Functions</div>"
                + "<button id=\"" + cross + "\" onclick=\"var hs=Array.from(document.querySelectorAll('*'))"
                + ".filter(function(e){ return e.id === '" + header + "'; }); var last=hs[hs.length-1];"
                + " if (last && hs.length > 1) last.style.display='none';\">x</button>"
                + "</body></html>";
    }
}
