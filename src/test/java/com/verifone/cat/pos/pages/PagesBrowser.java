package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.recovery.Timeouts;

/**
 * One Playwright and Chromium for a test class, shared by its tests. Without Chromium, {@link #available}
 * is false and each test skips through a JUnit assumption; nothing here downloads a browser.
 *
 * <p>Evidence about this repository only.
 */
final class PagesBrowser implements AutoCloseable {

    static final String INSTALL_COMMAND =
            "java -cp \"target\\lib\\*\" com.microsoft.playwright.CLI install chromium";

    static final String SKIP_REASON =
            "Chromium is not installed for Playwright; skipping. Install it with: " + INSTALL_COMMAND;

    /** Short budgets: a test that waits on a missing element should fail in seconds, not minutes. */
    static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(3), Duration.ofSeconds(5));

    private final Playwright playwright;
    private final Browser browser;

    private PagesBrowser(Playwright playwright, Browser browser) {
        this.playwright = playwright;
        this.browser = browser;
    }

    /** Starts Playwright and a Chromium, or an object that reports none is available. */
    static PagesBrowser open() {
        Playwright playwright;
        try {
            playwright = Playwright.create();
        } catch (PlaywrightException missing) {
            return new PagesBrowser(null, null);
        }
        try {
            return new PagesBrowser(playwright, playwright.chromium().launch());
        } catch (PlaywrightException missing) {
            playwright.close();
            return new PagesBrowser(null, null);
        }
    }

    boolean available() {
        return browser != null;
    }

    Page newPage() {
        return browser.newPage();
    }

    /** A driver on {@code page}, with overlay clearing off: these pages are plain markup. */
    static PosDriver driverOn(Page page) {
        return PosDriver.builder(page).timeouts(TIMEOUTS).registerId("local").settleOverlays(false).build();
    }

    @Override
    public void close() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
