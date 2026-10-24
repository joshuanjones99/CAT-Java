package com.verifone.cat.pats.runner.handlers.sale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.fake.FakePos;
import com.verifone.cat.pos.pages.Pos;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The production {@link SaleScreen} reader, over the Pos page objects, against the fake POS in headless Chromium.
 * Evidence about this repository only. Skipped, not failed, when Chromium is not installed for Playwright.
 */
class SaleScreenBrowserTest {

    @Test
    void theRealReaderSeesTheFakePosWithNoOverlayUp() throws IOException {
        try (FakePos fake = FakePos.open(FakePos.Hooks.NONE);
                Playwright playwright = Playwright.create()) {
            Browser browser = launchOrNull(playwright);
            assumeTrue(browser != null, "Chromium is not installed for Playwright; skipping");
            try (browser) {
                Page page = browser.newPage();
                page.navigate(fake.url().toString(),
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                PosDriver driver = PosDriver.builder(page).registerId("local").settleOverlays(false).build();
                SaleScreen screen = SaleScreen.of(new Pos(driver));

                assertNull(screen.overlay(), "a fresh fake POS shows no overlay");
                assertFalse(screen.screenText().isBlank(), "the fake POS renders text");
            }
        }
    }

    private static Browser launchOrNull(Playwright playwright) {
        try {
            return playwright.chromium().launch();
        } catch (PlaywrightException missing) {
            return null;
        }
    }
}
