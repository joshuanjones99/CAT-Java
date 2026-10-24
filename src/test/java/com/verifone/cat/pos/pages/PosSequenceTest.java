package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.microsoft.playwright.Page;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.DriverError;
import com.verifone.cat.pos.driver.PosDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The multi-step {@link Pos} flows (sign-on, clearing a sale, settling due fuel, freeing a reserve) in
 * headless Chromium, against markup that records each press and moves state the way the register does.
 * Ported from the sign-on, clear-open-sale, settle-due-fuel and clear-reserve cases of the Python suite,
 * with the mocks replaced by pages that answer.
 *
 * <p>The build version is fixed so the fuel tile's build variant resolves the same way on every run.
 * Evidence about this repository only.
 */
class PosSequenceTest {

    private static final String BUILD = "057.00";

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

    // -- sign-on ----------------------------------------------------------

    @Test
    void signOnTypesTheIdThenThePasswordAndReturnsWhenNoReviewMenuShows() {
        try (Page page = browsers.newPage()) {
            page.setContent(login(false));
            Pos pos = new Pos(PagesBrowser.driverOn(page));

            pos.signOn("1", "2");

            assertEquals("1/2", page.evaluate("window.signedOn"));
        }
    }

    @Test
    void signOnRefusesAKeypadThatTookThePasswordForTheId() {
        try (Page page = browsers.newPage()) {
            page.setContent(login(true));
            Pos pos = new Pos(PagesBrowser.driverOn(page));

            DriverError refused = assertThrows(DriverError.class, () -> pos.signOn("1", "2"));
            assertTrue(refused.getMessage().contains("not '1'"), refused.getMessage());
        }
    }

    // -- clearing the open sale -------------------------------------------

    @Test
    void clearOpenSaleLeavesAnIdleRegisterAlone() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, true, "", "", "", false));
            assertEquals("idle", new Pos(driver).clearOpenSale(null));
        }
    }

    @Test
    void clearOpenSaleVoidsAnOrdinarySale() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, false, "COKE", "2", "", false));
            assertEquals("voided", new Pos(driver).clearOpenSale(null));
            assertEquals(true, page.evaluate("window.voided"));
        }
    }

    @Test
    void clearOpenSalePaysAFillOffInCash() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, false, "PETR-UNLD1 CA #03", "27.788", "", false));
            assertEquals("settled", new Pos(driver).clearOpenSale(null));
            assertEquals("CASH", page.evaluate("window.mop"));
            assertEquals(false, page.evaluate("window.voided === true"));
        }
    }

    // -- settling due fuel and freeing a reserve --------------------------

    @Test
    void settleDueFuelWithNothingDueLeavesThePumpAlone() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, true, "", "", "", false));
            assertEquals(List.of(), new Pos(driver).settleDueFuel(1, null));
        }
    }

    @Test
    void settleDueFuelRingsTheDueFillInAndPaysItInCash() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, true, "", "", "+10.00", true));
            Pos pos = new Pos(driver);

            assertEquals(List.of("+10.00"), pos.settleDueFuel(1, null));
            assertEquals("CASH", page.evaluate("window.mop"));
        }
    }

    @Test
    void clearReservePressesClearReserveAndTheTileLetsGo() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, true, "", "", "32.40", false));
            Pos pos = new Pos(driver);

            assertEquals("pump 1: 32.40 reserved; Clear Reserve pressed", pos.clearReserve(1));
        }
    }

    @Test
    void clearReserveDoesNothingWhenThePumpHoldsNoReserve() {
        try (Page page = browsers.newPage()) {
            PosDriver driver = PagesBrowser.driverOn(page, BUILD);
            page.setContent(surface(driver, true, "", "", "", false));
            assertEquals("", new Pos(driver).clearReserve(1));
        }
    }

    // -- markup -----------------------------------------------------------

    /** The sales screen: express slots, the MOP and item buttons, the VFI keypad, the receipt line, pump 1. */
    private static String surface(PosDriver driver, boolean idle, String description, String quantity, String tileLabel,
            boolean dueFill) {
        String slot7 = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7).value();
        String slot4 = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(4).value();
        String pg = Locators.ITEM_PANEL_N.at(ItemPanel.MOP.PAYMENT_GATEWAY.id()).value();
        String cash = Locators.ITEM_PANEL_N.at(ItemPanel.MOP.CASH.id()).value();
        String desc = Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.at(0).value();
        String qty = Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N.at(0).value();
        String label = Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT.at(1).value();
        String dataTile = Locators.FUEL_PANEL_0_FUEL_BUTTON_N_DATA.at(1).value();
        String close = Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON.value();
        String clearReserve = Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_FUNCTION_BUTTON_LABEL_N
                .at(0).value();
        String ok = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value();

        StringBuilder html = new StringBuilder("<html><body>");
        html.append("<button id=\"").append(slot7).append("\">")
                .append(idle ? "CSR Func" : "Error Correct").append("</button>");
        html.append("<button id=\"").append(slot4)
                .append("\" onclick=\"window.voided=true; setSlot7('CSR Func')\">Void Ticket</button>");
        html.append("<button id=\"").append(pg).append("\" onclick=\"window.mop='PG'\">Total</button>");
        html.append("<button id=\"").append(cash).append("\" onclick=\"window.mop='CASH'\">Cash</button>");
        for (int digit = 0; digit <= 9; digit++) {
            html.append("<button id=\"").append(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N
                    .at(String.valueOf(digit)).value())
                    .append("\" onclick=\"window.typed=(window.typed||'') + '").append(digit).append("'\">")
                    .append(digit).append("</button>");
        }
        html.append("<button id=\"").append(ok)
                .append("\" onclick=\"if (window.mop==='CASH') setSlot7('CSR Func'); window.confirmed=true\">"
                        + "OK</button>");
        html.append("<div id=\"").append(desc).append("\">").append(description).append("</div>");
        html.append("<div id=\"").append(qty).append("\">").append(quantity).append("</div>");
        html.append("<div id=\"").append(label).append("\">").append(tileLabel).append("</div>");

        // The tile's id is build-variant: the driver resolves it, as the page object does.
        String tileId = driver.resolve(Locators.FUEL_BUTTON, 1).value();
        html.append("<button id=\"").append(tileId).append("\" onclick=\"ringFill()\">Pump 1</button>");
        html.append("<button id=\"").append(dataTile).append("\" onclick=\"showDetail()\">Data</button>");
        html.append("<div id=\"DETAIL\" style=\"display:none\">");
        html.append("<button id=\"").append(close).append("\" onclick=\"hideDetail()\">Close</button>");
        html.append("<button id=\"").append(clearReserve)
                .append("\" onclick=\"clearTile()\">Clear Reserve</button>");
        html.append("</div>");

        html.append("<script>")
                .append("function setSlot7(t){ document.getElementById('").append(slot7)
                .append("').textContent = t; }")
                .append("function showDetail(){ document.getElementById('DETAIL').style.display='block'; }")
                .append("function hideDetail(){ document.getElementById('DETAIL').style.display='none'; }")
                .append("function clearTile(){ document.getElementById('").append(label)
                .append("').textContent=''; hideDetail(); }")
                .append("function ringFill(){ document.getElementById('").append(label)
                .append("').textContent='10.00'; document.getElementById('").append(desc)
                .append("').textContent='PETR-UNLD1 CA #03'; document.getElementById('").append(qty)
                .append("').textContent='10.000'; setSlot7('Error Correct'); }")
                .append("</script></body></html>");
        return html.toString();
    }

    /** The login screen: an idle message, the ID and password prompt, and the VFI keypad. */
    private static String login(boolean wrongEcho) {
        String idle = Locators.IDLE_SCREEN_VIEW_IDLE_MSG.value();
        String prompt = Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT.value();
        String ok = Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value();
        StringBuilder html = new StringBuilder("<html><body>");
        html.append("<button id=\"").append(idle)
                .append("\" onclick=\"this.style.display='none'; document.getElementById('").append(prompt)
                .append("').style.display='block'\">Press Enter</button>");
        html.append("<div id=\"").append(prompt).append("\" style=\"display:none\">Enter ID</div>");
        for (int digit = 0; digit <= 9; digit++) {
            html.append("<button id=\"").append(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N
                    .at(String.valueOf(digit)).value())
                    .append("\" onclick=\"window.typed=(window.typed||'') + '").append(digit).append("'\">")
                    .append(digit).append("</button>");
        }
        html.append("<button id=\"").append(ok).append("\" onclick=\"loginOk()\">OK</button>");
        html.append("<script>")
                .append("var stage='id', idTyped='';")
                .append("function loginOk(){")
                .append("  var typed = window.typed || ''; window.typed = '';")
                .append("  if (stage === 'id') { idTyped = typed; stage = 'pw';")
                .append("    var echo = ").append(wrongEcho ? "'9'" : "typed").append(";")
                .append("    document.getElementById('").append(prompt)
                .append("').textContent = 'Enter Password For ID: ' + echo; }")
                .append("  else { window.signedOn = idTyped + '/' + typed; stage = 'done';")
                .append("    document.getElementById('").append(prompt).append("').style.display='none'; }")
                .append("}")
                .append("</script></body></html>");
        return html.toString();
    }
}
