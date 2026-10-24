package com.verifone.cat.pos.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.pos.Locators;
import java.io.IOException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The page's own script, run in headless Chromium through Playwright for Java: the buttons call the
 * server, and the receipt and the state read back on the page.
 *
 * <p>Needs the Chromium build Playwright 1.49 expects. Without it the test is skipped, not failed,
 * and never downloads a browser. To install it, run:
 * {@code java -cp "target\lib\*" com.microsoft.playwright.CLI install chromium}
 *
 * <p>Evidence about this repository only.
 */
class FakePosBrowserTest {

    static final String INSTALL_COMMAND =
            "java -cp \"target\\lib\\*\" com.microsoft.playwright.CLI install chromium";

    private static final int TEST_ITEM = 9999;
    private static final int PAYMENT_GATEWAY = 145397;
    private static final int CASH = 145394;

    @Test
    void theScriptDrivesTheSaleAndTheStateOnTheRealPage() throws IOException {
        try (FakePos pos = FakePos.open(FakePos.Hooks.NONE);
                Playwright playwright = Playwright.create()) {
            Browser browser = launchOrNull(playwright);
            Assumptions.assumeTrue(browser != null,
                    "Chromium is not installed for Playwright; skipping. Install it with: " + INSTALL_COMMAND);
            try (browser) {
                Page page = browser.newPage();
                page.navigate(pos.url().toString(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                assertEquals("Fake POS", page.title());

                click(page, Locators.ITEM_PANEL_N.at(TEST_ITEM).selector());
                page.waitForFunction("id => document.getElementById(id).textContent === 'Item " + TEST_ITEM + "'",
                        FakePosPage.SELECTED_ITEMS_ID);
                String description = Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.at(0).selector();
                assertEquals("Item " + TEST_ITEM, text(page, description));

                click(page, Locators.ITEM_PANEL_N.at(PAYMENT_GATEWAY).selector());
                page.waitForFunction("id => document.getElementById(id).textContent === 'TRANSACTION_ACTIVE'",
                        FakePosPage.STATE_ID);
                assertEquals("Error Correct", text(page, Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7).selector()));

                click(page, Locators.ITEM_PANEL_N.at(CASH).selector());
                page.waitForFunction("id => document.getElementById(id).textContent === 'IDLE'",
                        FakePosPage.STATE_ID);
                assertEquals("CSR Func", text(page, Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7).selector()));
            }
        }
    }

    /** The browser, or null when Chromium is not installed for this Playwright. */
    private static Browser launchOrNull(Playwright playwright) {
        try {
            return playwright.chromium().launch();
        } catch (PlaywrightException missing) {
            return null;
        }
    }

    /** Clicks the element a catalogue locator's selector names; the selector is the catalogue's, not built here. */
    private static void click(Page page, String selector) {
        page.locator(selector).click();
    }

    private static String text(Page page, String selector) {
        return page.locator(selector).textContent();
    }
}
