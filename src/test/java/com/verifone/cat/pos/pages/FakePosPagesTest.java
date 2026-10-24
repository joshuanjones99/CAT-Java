package com.verifone.cat.pos.pages;

import java.io.IOException;
import java.time.Duration;

import com.microsoft.playwright.Page;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.fake.FakePos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The input overlay and the keypad against the Java fake POS: CASH asks for the amount on the VFI keypad,
 * and the page objects read that prompt and key the amount. Evidence about this repository only; the fake
 * POS serves the sale surface, not the overlays of a register.
 */
class FakePosPagesTest {

    private static final int CASH = 145394;
    private static final Duration WAIT = Duration.ofSeconds(3);

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
    void cashAsksForTheAmountAndTheKeypadKeysItAndHidesTheOverlay() throws IOException {
        try (FakePos fake = FakePos.open(FakePos.Hooks.NONE); Page page = browsers.newPage()) {
            page.navigate(fake.url().toString());
            PosDriver driver = PagesBrowser.driverOn(page);

            driver.tap(Locators.ITEM_PANEL_N, CASH);
            InputOverlay input = new InputOverlay(driver);
            input.expectPrompt("Enter Amount", WAIT);

            Keypad keypad = new Keypad(driver);
            keypad.expectOpen(WAIT);
            keypad.enter("1250", false, WAIT);
            keypad.ok(WAIT);

            input.label1().expectHidden(WAIT);
        }
    }
}
