package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.pos.fake.FakePos;
import com.verifone.cat.recovery.Timeouts;

/**
 * The page objects against a real Chromium: static fixtures for the fuel and detail pages, and the
 * Java fake POS for the express and item panels. Port of the browser-backed parts of the Python
 * CAT's page tests. Element IDs come from the catalogue.
 *
 * <p>Chromium must be installed for this Playwright version. Without it every test here is SKIPPED
 * (not failed), with the install command in the skip message. Nothing downloads a browser.
 *
 * <p>Evidence about this repository only. Not evidence about a register.
 */
class PagesBrowserTest {

    static final String INSTALL_HINT = "Playwright's Chromium is not installed for this Playwright version. "
            + "Install it with: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI "
            + "-Dexec.args=\"install chromium\" (or set CAT_TEST_CHROMIUM to a Chromium binary)";

    private static final Timeouts TIMEOUTS = Timeouts.parse(Map.of("default_wait", "5s"));
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static Playwright playwright;
    private static Browser browser;
    private static String launchFailure;

    private Page page;

    @BeforeAll
    static void launch() {
        try {
            playwright = Playwright.create();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            String override = System.getenv("CAT_TEST_CHROMIUM");
            if (override != null && !override.isBlank()) {
                options.setExecutablePath(Path.of(override));
            }
            browser = playwright.chromium().launch(options);
        } catch (PlaywrightException e) {
            launchFailure = e.getMessage();
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        }
    }

    @AfterAll
    static void stop() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void newPage() {
        assumeTrue(browser != null, INSTALL_HINT + ". Launch error: "
                + (launchFailure == null ? "none" : firstLine(launchFailure)));
        page = browser.newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) {
            page.close();
        }
    }

    private PosDriver driver(String buildVersion) {
        return PosDriver.builder(page).timeouts(TIMEOUTS).registerId("local")
                .buildVersion(buildVersion).settleOverlays(false).build();
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("");
    }

    /** A catalogue element's literal id, as the page holds it. */
    private static String idOf(Locator locator, Object... args) {
        return locator.at(args).literals().get(0);
    }

    // -- fuel, on a static fixture -------------------------------------------

    private void fuelFixture() {
        String current1 = idOf(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT, 1);
        String stacked1 = idOf(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_STACKED, 1);
        String current2 = idOf(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT, 2);
        String current3 = idOf(Locators.FUEL_PANEL_0_FUEL_BUTTON_N_LABEL_CURRENT, 3);
        page.setContent("<html><body>"
                + "<div id=\"" + current1 + "\">+10.00</div>"
                + "<div id=\"" + stacked1 + "\">+6.00</div>"
                + "<div id=\"" + current2 + "\">32.40</div>"
                + "<div id=\"" + current3 + "\">+20.00</div>"
                + "</body></html>", new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));
    }

    @Test
    void theDueFillsOfAPumpAreThoseReadWithAPlus() {
        fuelFixture();
        assertEquals(List.of("+10.00", "+6.00"), new FuelPage(driver("057.00")).due(1));
        assertEquals(List.of("+20.00"), new FuelPage(driver("057.00")).due(3));
    }

    @Test
    void aReserveIsAnAmountWithNoPlusAndNothingDue() {
        fuelFixture();
        FuelPage fuel = new FuelPage(driver("057.00"));
        assertEquals(Optional.of("32.40"), fuel.reserved(2));
        assertEquals(Optional.empty(), fuel.reserved(3));
        assertEquals(Optional.empty(), fuel.reserved(1));
    }

    @Test
    void expectPumpAmountWaitsForTheTileToShowTheAmount() {
        fuelFixture();
        new FuelPage(driver("057.00")).expectPumpAmount(1, "+10.00", WAIT);
    }

    @Test
    void theDetailOverlayPressesAFunctionButtonByItsLabel() {
        String stop = idOf(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_FUNCTION_BUTTON_LABEL_N, 3);
        String approve = idOf(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_FUNCTION_BUTTON_LABEL_N, 0);
        page.setContent("<html><body>"
                + "<button id=\"" + approve + "\" onclick=\"window.pressed = 'approve'\">Approve</button>"
                + "<button id=\"" + stop + "\" onclick=\"window.pressed = 'stop'\">Stop</button>"
                + "</body></html>", new Page.SetContentOptions().setWaitUntil(WaitUntilState.LOAD));

        assertTrue(new FuelPage(driver("057.00")).detail().pressLabelled("  stop ", WAIT));
        assertEquals("stop", page.evaluate("window.pressed"));
        assertFalse(new FuelPage(driver("057.00")).detail().pressLabelled("Nonesuch", WAIT));
    }

    // -- express and item panels, on the Java fake POS -----------------------

    @Test
    void theExpressPanelReadsIdleThenActiveThenIdleAsTheSaleRuns() throws IOException {
        try (FakePos pos = FakePos.open(FakePos.Hooks.NONE)) {
            page.navigate(pos.url().toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            PosDriver driver = driver(null);
            ItemPanel items = new ItemPanel(driver);
            ExpressPanel express = new ExpressPanel(driver);

            express.expectIdle(WAIT);
            assertTrue(express.isIdle());

            items.select(ItemPanel.MOP.PAYMENT_GATEWAY);
            express.expectTransactionActive(WAIT);
            assertFalse(express.isIdle());

            items.select(ItemPanel.MOP.CASH);
            express.expectIdle(WAIT);
        }
    }

    @Test
    void theItemPanelPressOpensTheSaleOnTheFakePos() throws IOException {
        try (FakePos pos = FakePos.open(FakePos.Hooks.NONE)) {
            page.navigate(pos.url().toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            ItemPanel items = new ItemPanel(driver(null));

            items.select(ItemPanel.MOP.PAYMENT_GATEWAY);
            new ExpressPanel(driver(null)).expectTransactionActive(WAIT);
            assertTrue(pos.state().transactionActive());
        }
    }
}
