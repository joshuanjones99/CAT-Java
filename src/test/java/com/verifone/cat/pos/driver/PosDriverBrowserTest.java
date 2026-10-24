package com.verifone.cat.pos.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.recovery.Timeouts;

/**
 * {@link PosDriver} against a real Chromium, on local pages only. Port of the browser-backed
 * sections of the Python CAT's {@code test_pos_driver.py}, {@code test_pos_driver_press.py} and
 * {@code test_pos_blocking_overlays.py}. Element IDs come from the catalogue; the pages are the
 * fixtures.
 *
 * <p>Playwright's Chromium must be installed for this Playwright version. If it is not, every test
 * here is SKIPPED (not failed) with the install command. Install it with
 * {@code mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"}.
 * Setting {@code CAT_TEST_CHROMIUM} to a Chromium binary runs the same tests against that binary
 * instead (used to check the tests against a newer installed Chromium; never a register's).
 *
 * <p>Evidence about this repository's scripts only. Not evidence about a register.
 */
class PosDriverBrowserTest {

    /** The install command, named in every skip message. */
    static final String INSTALL_HINT = "Playwright's Chromium is not installed for this Playwright "
            + "version. Install it with: mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI "
            + "-Dexec.args=\"install chromium\" (or set CAT_TEST_CHROMIUM to a Chromium binary)";

    private static final Timeouts TIMEOUTS = Timeouts.parse(Map.of("default_wait", "5s"));

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

    private PosDriver driver() {
        return PosDriver.builder(page).timeouts(TIMEOUTS).registerId("local").settleOverlays(false).build();
    }

    private static String firstLine(String text) {
        return text.lines().findFirst().orElse("");
    }

    /** A catalogue ID as it appears in the page: the element's own literal. */
    private static String idOf(Locator locator, Object... args) {
        return locator.at(args).literals().get(0);
    }

    @Test
    void tapPressesAnElementByItsCatalogueId() {
        String id = idOf(Locators.ITEM_PANEL_N, 145397);
        page.setContent("<button id='" + id + "' onclick=\"window.hit='item'\">Item</button>");

        driver().tap(Locators.ITEM_PANEL_N, 145397);

        assertEquals("item", page.evaluate("window.hit"));
    }

    @Test
    void pressChoosesTheLastVisibleOfDuplicateIds() {
        String id = idOf(Locators.ITEM_PANEL_N, 145397);
        page.setContent("<button id='" + id + "' onclick=\"window.hit='first dup'\">A</button>"
                + "<button id='" + id + "' onclick=\"window.hit='last dup'\">B</button>");

        driver().press(Locators.ITEM_PANEL_N.at(145397), Duration.ofSeconds(5));

        assertEquals("last dup", page.evaluate("window.hit"));
    }

    @Test
    void aHiddenOnlyMatchIsNotPressedBeforeTheHiddenGrace() {
        String id = idOf(Locators.ITEM_PANEL_N, 145397);
        page.setContent("<button id='" + id + "' style='display:none' onclick=\"window.hit='hidden'\">H</button>");

        assertThrows(DriverTimeout.class,
                () -> driver().press(Locators.ITEM_PANEL_N.at(145397), Duration.ofMillis(500)));
        assertEquals(Boolean.FALSE, page.evaluate("window.hit !== undefined"));
    }

    @Test
    void anXPathPressesTheFirstVisibleMatchAsSeleniumDoes() {
        page.setContent("<button style='display:none' onclick=\"window.hit='hidden cash'\">CASH</button>"
                + "<button onclick=\"window.hit='cash'\">CASH</button>"
                + "<button onclick=\"window.hit='ten'\">$10<br>CASH</button>");

        driver().press(Locators.XP_BUTTON_TEXT_CASH, Duration.ofSeconds(5));

        assertEquals("cash", page.evaluate("window.hit"));
    }

    @Test
    void screenTextSeesAKeypadDigit() {
        page.setContent("<body>Amount <input value='123'></body>");

        String text = driver().screenText();

        assertTrue(text.contains("123"), text);
    }

    @Test
    void aFamilyIsReadInOneLookByIndex() {
        String first = idOf(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N, 0);
        String second = idOf(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N, 1);
        page.setContent("<div id='" + first + "'>COKE</div><div id='" + second + "'>  DIET   COKE </div>");

        Map<Long, List<String>> texts = driver().familyTexts(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N);

        assertEquals(Map.of(0L, List.of("COKE"), 1L, List.of("DIET COKE")), texts);
    }

    @Test
    void theReceiptIsCapturedThroughTheDriver() {
        String description = idOf(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N, 0);
        String amount = idOf(Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N, 0);
        page.setContent("<div id='" + description + "'>COKE</div><div id='" + amount + "'>1.50</div>");

        Receipt receipt = new DomReceiptCapture(new PosDomDriver(driver()), PosDomDriver.itemFamilies()).capture();

        assertEquals(1, receipt.lines().size());
        assertEquals("COKE", receipt.lines().get(0).description());
        assertEquals("1.50", receipt.lines().get(0).amount());
    }

    @Test
    void theCapturedDomHoldsTheFrameNotOnlyItsHost(@TempDir Path tmp) throws IOException {
        page.setContent("<iframe name='pos' srcdoc=\"<button>Error Correct</button>\"></iframe>");
        page.waitForFunction("document.querySelector('iframe').contentDocument.body.innerHTML");
        PosDriver driver = PosDriver.builder(page).timeouts(TIMEOUTS).registerId("local")
                .artifacts(new DirectoryArtifacts(tmp)).settleOverlays(false).build();

        Path dom = driver.capture("failure").get("dom");

        String text = Files.readString(dom);
        assertTrue(text.contains("<iframe"), text);
        assertTrue(text.contains("<button>Error Correct</button>"), text);
    }

    @Test
    void aNagIsDismissedThroughItsOwnOkAndNeverRemoved() {
        String title = idOf(Locators.OVERLAY_MSG_VIEW_TITLE);
        String ok = idOf(Locators.OVERLAY_MSG_VIEW_OK_BTN);
        page.setContent("<div><div id='" + title + "'>SYSTEM NOT REGISTERED</div>"
                + "<button id='" + ok + "' onclick=\"window.dismissed=true;"
                + "this.parentNode.style.display='none'\">OK</button></div>");
        PosDriver driver = PosDriver.builder(page).timeouts(TIMEOUTS).settleOverlays(false).build();

        driver.clearBlockingOverlays(Duration.ofSeconds(10));

        assertEquals(Boolean.TRUE, page.evaluate("window.dismissed === true"));
        assertEquals(Boolean.TRUE, page.evaluate("!!document.getElementById('" + title + "')"),
                "the nag's panel is hidden, never removed (HANDOFF 1.145)");
    }

    @Test
    void closingTheDriverLeavesTheBrowserRunning(@TempDir Path tmp) throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        try (Playwright own = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true)
                    .setArgs(List.of("--remote-debugging-port=" + port));
            String override = System.getenv("CAT_TEST_CHROMIUM");
            if (override != null && !override.isBlank()) {
                options.setExecutablePath(Path.of(override));
            }
            try (Browser local = own.chromium().launch(options)) {
                Page ours = local.newPage();
                ours.setContent("<h1>mid-sale</h1>");
                RegisterEndpoint endpoint = new RegisterEndpoint("local", "localhost", port);

                PosDriver attached = PosDriver.connect(endpoint, TIMEOUTS, null, new DirectoryArtifacts(tmp), null,
                        false);
                assertTrue(attached.page() != null);
                attached.close();

                assertTrue(local.isConnected(), "closing the driver must not close the browser");
                assertEquals(Integer.valueOf(2), ours.evaluate("1 + 1"));
            }
        }
    }

    @Test
    void settleScreenWaitsForAChangeAndThenForQuiet() {
        page.setContent("<body>start<script>setTimeout(() => document.body.append('later'), 100);</script></body>");
        PosDriver driver = driver();
        String before = driver.screenText();

        PosDriver.Settled settled = driver.settleScreen(before, Duration.ofMillis(300), Duration.ofSeconds(5), false);

        assertTrue(settled.changed(), "the screen changed after 100 ms");
        assertTrue(settled.text().contains("later"), settled.text());
    }

    @Test
    void elementStatesAndClickableStatesReadVisibilityAndEnablement() {
        String id = idOf(Locators.ITEM_PANEL_N, 145397);
        page.setContent("<button id='" + id + "'>Shown</button>");
        String hidden = idOf(Locators.ITEM_PANEL_N, 145398);
        page.setContent(page.content() + "<button id='" + hidden + "' style='display:none' disabled>Hidden</button>");
        PosDriver driver = driver();

        assertEquals(List.of(new PosDriver.ElementState(true, "Shown")),
                driver.elementStates(Locators.ITEM_PANEL_N.at(145397)));
        assertEquals(List.of(new PosDriver.ElementState(false, "Hidden")),
                driver.elementStates(Locators.ITEM_PANEL_N.at(145398)));
        assertEquals(List.of(new PosDriver.Clickable(true, true)),
                driver.clickableStates(Locators.ITEM_PANEL_N.at(145397)));
        assertEquals(List.of(new PosDriver.Clickable(false, false)),
                driver.clickableStates(Locators.ITEM_PANEL_N.at(145398)));
        assertTrue(driver.anyVisible(Locators.ITEM_PANEL_N.at(145397)));
        assertFalse(driver.anyVisible(Locators.ITEM_PANEL_N.at(145398)));
    }
}
