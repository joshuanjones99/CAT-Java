package com.verifone.cat.pos.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.verifone.cat.pos.Locator;

/**
 * {@link CdpPage} against a local headless Chromium (Playwright's, skipped when absent): finding an element by ID and
 * by XPath, a tap that runs the page's own {@code onclick}, and what is not showing. Evidence about this repository
 * only, not about a register's Chrome 67.
 */
class CdpPageBrowserTest {

    private static Locator locator(String value, Locator.Kind kind) {
        return Locator.of(value, kind, "TEST_" + kind, "test", 0, false, false, null, null, List.of(), "test");
    }

    @Test
    void aTapRunsTheElementsClickHandlerAndMissingOrHiddenIsNotShowing() throws Exception {
        try (LocalDevTools chromium = LocalDevTools.openOrNull()) {
            Assumptions.assumeTrue(chromium != null,
                    "Chromium is not installed for Playwright; skipping. Install it with: " + LocalDevTools.INSTALL_COMMAND);
            Locator byId = locator("press", Locator.Kind.ID);
            // XPath's id() form: document.evaluate is exercised without a path the catalogue gate reads as a POS one.
            Locator byXpath = locator("id('press')", Locator.Kind.XPATH);
            Locator missing = locator("no-such-element", Locator.Kind.ID);

            try (CdpPage page = CdpPage.open(chromium.cdpUrl(), Duration.ofSeconds(8))) {
                assertTrue(page.isShowing(byId));
                assertTrue(page.isShowing(byXpath));
                assertFalse(page.isShowing(missing));

                page.tap(byId, Duration.ofSeconds(5));
                assertEquals("1", chromium.page().evaluate("document.getElementById('press').dataset.n"));
                page.tap(byXpath, Duration.ofSeconds(5));
                assertEquals("2", chromium.page().evaluate("document.getElementById('press').dataset.n"));

                assertThrows(InspectError.class, () -> page.tap(missing, Duration.ofMillis(600)));
                assertEquals("2", chromium.page().evaluate("document.getElementById('press').dataset.n"));

                chromium.page().evaluate("document.getElementById('press').style.visibility = 'hidden'");
                assertFalse(page.isShowing(byId));
            }
        }
    }
}
