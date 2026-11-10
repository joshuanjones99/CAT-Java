package com.verifone.cat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.verifone.cat.pos.driver.PlaywrightHome;
import com.verifone.cat.pos.inspect.LocalDevTools;
import com.verifone.cat.posshim.FakeShim;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.runconfig.ConfigFixture;
import com.verifone.cat.runconfig.ConfigForm;

import io.javalin.Javalin;

/**
 * Waiting on CAT, in a real browser (Playwright's Chromium; skipped when it is not installed): the Run tab's Overview
 * and Config panels load after their pages, the parts inside them load in turn, a slow click shows the bar along the
 * top of the window until its answer lands, and a part that fails to load says so. A fake plan seed in a temp folder;
 * nothing here reaches the rig, Confluence or the home directory.
 */
class WaitingBrowserTest {

    @TempDir
    Path tmp;

    private FakeShim shim;
    private Javalin app;
    private final AtomicBoolean slowTick = new AtomicBoolean();
    private final AtomicBoolean configFails = new AtomicBoolean();

    @BeforeEach
    void setUp() throws IOException {
        shim = new FakeShim();
        PosShimClient client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
        ConfigForm form = ConfigFixture.form(tmp);
        app = WebApp.create(client, "CAT-Java: test / topaz101", form);
        // A tick that takes CAT a second, and a Config panel that will not draw: the two waits the page must show.
        app.before("/config/plan", ctx -> {
            if (slowTick.get()) {
                Thread.sleep(1000);
            }
        });
        app.before("/fragments/config", ctx -> {
            if (configFails.get()) {
                throw new IllegalStateException("the panel will not draw");
            }
        });
        app.start(WebApp.BIND_HOST, 0);
    }

    @AfterEach
    void tearDown() {
        app.stop();
        shim.close();
    }

    private String url(String path) {
        return "http://" + WebApp.BIND_HOST + ":" + app.port() + path;
    }

    /** Runs {@code test} on a page of Chromium, or skips when Playwright has no Chromium here. */
    private void inBrowser(PageTest test) throws Exception {
        try (Playwright playwright = PlaywrightHome.create()) {
            Browser browser;
            try {
                browser = playwright.chromium().launch();
            } catch (PlaywrightException missing) {
                Assumptions.abort("Chromium is not installed for Playwright; skipping. Install it with: "
                        + LocalDevTools.INSTALL_COMMAND);
                return;
            }
            try (browser) {
                test.run(browser.newPage());
            }
        }
    }

    interface PageTest {
        void run(Page page) throws Exception;
    }

    @Test
    void theConfigTabsPanelAndThePartsInsideItLoadAfterThePage() throws Exception {
        inBrowser(page -> {
            page.navigate(url("/run/config"));
            page.waitForSelector("#config-filter");
            // The sections after Run scope and the Refresh control load from inside the loaded panel.
            page.waitForSelector("#config-controls");
            page.waitForSelector("#config-refresh");
            assertEquals(0, page.locator("[data-deferred]").count());
            assertEquals(0, page.locator(".cat-waiting").count());
            assertTrue(page.locator("#cat-busy").isHidden());
        });
    }

    @Test
    void theOverviewsPanelLoadsAndBringsTheLiveControls() throws Exception {
        inBrowser(page -> {
            page.navigate(url("/run"));
            page.waitForSelector("h2:has-text('Run Summary')");
            assertEquals(0, page.locator("[data-deferred]").count());
            // The panel's answer replaced the waiting controls with the live ones (out of band).
            assertTrue(page.locator("#run-controls button[hx-post='/run/start']").count() == 1);
            assertTrue(page.locator("#run-controls button[hx-post='/run/preflight']").count() == 1);
        });
    }

    @Test
    void aSlowClickShowsTheBarUntilItsAnswerLands() throws Exception {
        inBrowser(page -> {
            page.navigate(url("/run/config"));
            page.waitForSelector("#config-filter");
            assertTrue(page.locator("#cat-busy").isHidden());

            slowTick.set(true);
            page.locator("#plan-list input[type=checkbox]").first().click();
            page.waitForSelector("#cat-busy", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE)
                    .setTimeout(900));
            assertTrue((Boolean) page.evaluate("document.body.classList.contains('cat-waiting-on')"));
            page.waitForSelector("#cat-busy", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN)
                    .setTimeout(5000));
            assertFalse((Boolean) page.evaluate("document.body.classList.contains('cat-waiting-on')"));
            assertTrue(page.locator("#plan-list input[type=checkbox]").first().isChecked());
        });
    }

    @Test
    void aPartThatFailsToLoadSaysSoInsteadOfSpinning() throws Exception {
        configFails.set(true);
        inBrowser(page -> {
            page.navigate(url("/run/config"));
            page.waitForSelector(".cat-waiting.failed");
            String words = page.locator(".cat-waiting.failed").textContent();
            assertTrue(words.contains("did not load (CAT answered 500)"), words);
            assertEquals(0, page.locator("#run-config .cat-loader").count());
            assertEquals("false", page.locator("#run-config").getAttribute("aria-busy"));
        });
    }
}
