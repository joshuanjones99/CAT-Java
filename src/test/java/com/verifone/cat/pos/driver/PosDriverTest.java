package com.verifone.cat.pos.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.verifone.cat.pos.BuildVariant;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.recovery.Timeouts;

/**
 * The POS driver's parts that run without a browser. Port of the non-browser sections of the Python
 * CAT's {@code tests/unit/test_pos_driver.py}. DESIGN.md 6.3 (no unbounded wait) and 8 (hosts and
 * timeouts come only from the rig file).
 *
 * <p>Evidence about this repository only. The recording {@link FakePage} is not a Playwright mock:
 * it records what the driver asks for. Browser-backed behaviour is in {@link PosDriverBrowserTest}.
 */
class PosDriverTest {

    private static final Timeouts RIG_TIMEOUTS = Timeouts.parse(Map.of("default_wait", "15s"));
    private static final String TOPAZ = "topaz101";

    /** The catalogue's selector for an element, read from the catalogue itself. */
    private static String selectorOf(com.verifone.cat.pos.Locator locator, Object... args) {
        return locator.at(args).selector();
    }

    private static PosDriver driverOn(FakePage fake) {
        return PosDriver.builder(fake.page())
                .timeouts(RIG_TIMEOUTS)
                .registerId(TOPAZ)
                .settleOverlays(false) // the fake has no DOM to ask
                .build();
    }

    // -- rig configuration (DESIGN.md 8) -----------------------------------

    @Test
    void timeoutsLoadFromTheYamlBlock() {
        assertEquals(Duration.ofSeconds(15), RIG_TIMEOUTS.defaultWait());
        assertEquals(Duration.ofMillis(500), Timeouts.parse(Map.of("default_wait", "500ms")).defaultWait());
    }

    @Test
    void aZeroTimeoutIsRefusedBecauseItMeansWaitForever() {
        assertThrows(IllegalArgumentException.class, () -> Timeouts.parse(Map.of("default_wait", "0s")));
    }

    @Test
    void cdpUrlIsBuiltFromTheRigFile() {
        assertEquals("http://192.0.2.101:9222", new RegisterEndpoint(TOPAZ, "192.0.2.101", 9222).cdpUrl());
    }

    @Test
    void aRegisterWithNoHostFailsLoudly() {
        DriverError error = assertThrows(DriverError.class,
                () -> new RegisterEndpoint(TOPAZ, "", 9222).cdpUrl());
        assertTrue(error.getMessage().contains("rigs/"), error.getMessage());
    }

    // -- bounded waits (DESIGN.md 6.3) -------------------------------------

    @Test
    void everyActionCarriesATimeout() {
        FakePage fake = new FakePage();
        PosDriver driver = driverOn(fake);

        driver.tap(Locators.ITEM_PANEL_N, 145397);
        driver.textOf(Locators.OVERLAY_MSG_VIEW_LINE1);

        assertFalse(fake.calls.isEmpty(), "the driver did nothing");
        for (FakePage.Call call : fake.calls) {
            if (call.name().equals("evaluate") || call.name().startsWith("mouse.")) {
                continue; // one synchronous dispatch, not a bounded wait; the wait before it is bounded
            }
            assertTrue(call.timeout() != null && call.timeout() > 0,
                    call.name() + " was issued without a timeout (DESIGN.md 6.3)");
        }
    }

    @Test
    void theTimeoutIsTheRigsNumberNotALiteral() {
        FakePage fake = new FakePage();
        driverOn(fake).tap(Locators.ITEM_PANEL_N, 145397);
        assertEquals(Double.valueOf(15_000), fake.named("waitFor").get(0).timeout());
    }

    @Test
    void anExplicitTimeoutOverridesTheDefault() {
        FakePage fake = new FakePage();
        driverOn(fake).tapWithin(Duration.ofSeconds(90), Locators.ITEM_PANEL_N, 145397);
        assertEquals(Double.valueOf(90_000), fake.named("waitFor").get(0).timeout());
    }

    @Test
    void aZeroOverrideIsRefused() {
        FakePage fake = new FakePage();
        assertThrows(IllegalArgumentException.class,
                () -> driverOn(fake).tapWithin(Duration.ZERO, Locators.ITEM_PANEL_N, 145397));
    }

    @Test
    void theSelectorReachingPlaywrightIsTheCatalogueOne() {
        FakePage fake = new FakePage();
        driverOn(fake).tap(Locators.ITEM_PANEL_N, 145397);
        assertEquals(selectorOf(Locators.ITEM_PANEL_N, 145397), fake.named("waitFor").get(0).selector());
    }

    @Test
    void buildVariantIsResolvedOncePerSession() {
        BuildVariant variant = Locators.FUEL_BUTTON;
        PosDriver old = PosDriver.builder(new FakePage().page()).buildVersion("056.00").build();
        PosDriver current = PosDriver.builder(new FakePage().page()).buildVersion("057.00").build();

        assertEquals(variant.legacy().at(1).selector(), old.resolve(variant, 1).selector());
        assertEquals(variant.defaultLocator().at(1).selector(), current.resolve(variant, 1).selector());
        assertFalse(old.resolve(variant, 1).selector().equals(current.resolve(variant, 1).selector()));
    }

    @Test
    void domTextIsNormalisedOnTheWayOut() {
        FakePage fake = new FakePage();
        assertEquals("CSR Func", driverOn(fake).textOf(Locators.OVERLAY_MSG_VIEW_LINE1));
    }

    @Test
    void normaliseCollapsesNonBreakingPadding() {
        String padded = "A" + "  " + "B" + " " + "C ";
        assertEquals("A B C", PosText.normalise(padded));
    }

    // -- frames (DESIGN.md 5.2) --------------------------------------------

    @Test
    void aFrameScopedLocatorIsLookedUpInsideItsFrame() {
        String candidate = Locators.FRAMES.get("DEVICE_MONITORING").get(0);
        FakePage fake = new FakePage();
        fake.present = setOf(candidate);

        driverOn(fake).tap(Locators.XP_DIV_DUE);

        String selector = fake.named("waitFor").get(0).selector();
        assertTrue(selector.startsWith(candidate), selector);
        assertTrue(selector.endsWith("xpath=//div[@class='due']"), selector);
    }

    @Test
    void anUnattachableFrameRaisesInsteadOfMatchingNothing() {
        FakePage fake = new FakePage();
        fake.present = setOf();
        FrameNotFoundError error = assertThrows(FrameNotFoundError.class,
                () -> driverOn(fake).tap(Locators.XP_DIV_DUE));
        assertTrue(error.getMessage().contains("Phase 0"), error.getMessage());
    }

    // -- rung 0: capture before recovery (DESIGN.md 6.3) -------------------

    @Test
    void captureWritesTheEvidenceBundle(@TempDir Path tmp) throws Exception {
        FakePage fake = new FakePage();
        PosDriver driver = PosDriver.builder(fake.page()).registerId(TOPAZ)
                .artifacts(new DirectoryArtifacts(tmp)).settleOverlays(false).build();

        Map<String, Path> written = driver.capture("failure");

        assertEquals(java.util.Set.of("screenshot", "dom"), written.keySet());
        assertEquals("<html><body>POS</body></html>", Files.readString(written.get("dom")));
        assertTrue(written.get("screenshot").getFileName().toString().startsWith("failure-topaz101"));
    }

    @Test
    void captureNeverRaises(@TempDir Path tmp) {
        FakePage fake = new FakePage();
        fake.failContent = true;
        fake.failScreenshot = true;
        PosDriver driver = PosDriver.builder(fake.page()).registerId(TOPAZ)
                .artifacts(new DirectoryArtifacts(tmp)).settleOverlays(false).build();

        assertTrue(driver.capture("failure").isEmpty());
    }

    @Test
    void captureWithoutAnArtifactSinkIsANoOp() {
        assertTrue(driverOn(new FakePage()).capture("failure").isEmpty());
    }

    // -- overlays and the watch (DESIGN.md 5.2) ----------------------------

    @Test
    void theOverlayWatchIsBoundedBySettleBudget() {
        // The busy window never clears: the wait must end at its own bound, not run on.
        FakePage fake = new FakePage();
        fake.evaluator = (script, arg) -> List.of(false, false, false, List.of("busy"));
        PosDriver driver = PosDriver.builder(fake.page()).timeouts(RIG_TIMEOUTS).build();
        assertThrows(DriverTimeout.class,
                () -> driver.clearBlockingOverlays(Duration.ofMillis(300)));
    }

    // -- the assertions carry the budget too (DESIGN.md 6.3) ----------------

    @Test
    void boundAssertionSuppliesTheTimeoutToEveryCall() {
        java.util.List<Double> seen = new java.util.ArrayList<>();
        LocatorAssertions fake = FakePage.proxy(LocatorAssertions.class, (proxy, method, args) -> {
            if (method.getName().equals("isVisible")) {
                seen.add(((LocatorAssertions.IsVisibleOptions) args[0]).timeout);
                return null;
            }
            if (method.getName().equals("not")) {
                return proxy;
            }
            return null;
        });
        BoundAssertion bound = new BoundAssertion(fake, 15_000);
        bound.toBeVisible();
        bound.not().toBeVisible();
        assertEquals(List.of(15_000.0, 15_000.0), seen);
    }

    // -- polling (DESIGN.md 4.2 rule 2) -----------------------------------

    @Test
    void pollUntilTimesOutWithAReason() {
        DriverTimeout error = assertThrows(DriverTimeout.class,
                () -> PollUntil.until(() -> false, Duration.ofMillis(60), Duration.ofMillis(10), "the thing"));
        assertTrue(error.getMessage().contains("the thing"), error.getMessage());
    }

    @Test
    void pollUntilReturnsTheFirstValueItSees() {
        int[] calls = {0};
        String value = PollUntil.value(() -> ++calls[0] < 3 ? null : "there", Duration.ofSeconds(5),
                Duration.ofMillis(5), "value");
        assertEquals("there", value);
    }

    @Test
    void aBrokenProbeIsNotATimeout() {
        assertThrows(IllegalStateException.class, () -> PollUntil.until(() -> {
            throw new IllegalStateException("probe is broken");
        }, Duration.ofSeconds(5), Duration.ofMillis(5), "probe"));
    }

    // -- the live page (DESIGN.md 5.2, 2026-10-09) -------------------------

    @Test
    void theLivePosPageIsTheNewestHoldingThePosUiFrame() {
        String selector = "http://127.0.0.1:8080/appselectorclient/?platform=pythonLarge&appMode=pos";
        Page dead = pageWithFrames("about:blank");
        Page deadWithSelector = pageWithFrames(selector, "about:blank");
        Page live = pageWithFrames(selector, "http://127.0.0.1:8080/posuiapp/?platform=pythonLarge");
        Page loading = pageWithFrames(selector);

        assertTrue(PosDriver.livePosPage(List.of(FakePage.context(List.of(deadWithSelector, dead, live))))
                == live);
        assertTrue(PosDriver.livePosPage(List.of(FakePage.context(List.of(live, deadWithSelector))))
                == live);
        assertTrue(PosDriver.livePosPage(List.of(FakePage.context(List.of(dead, loading)))) == loading);
        assertNull(PosDriver.livePosPage(List.of(FakePage.context(List.of()))));
    }

    private static Page pageWithFrames(String... urls) {
        FakePage fake = new FakePage();
        for (String url : urls) {
            fake.frame("", url, "");
        }
        return fake.page();
    }

    // -- the receipt's DOM driver (DESIGN.md 5.3) --------------------------

    @Test
    void theReceiptReadsItsFamiliesThroughTheCatalogue() {
        String description = Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.prefixSelector();
        String amount = Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N.prefixSelector();
        String quantity = Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N.prefixSelector();
        String whole = Locators.RECEIPT_VIEW_ITEM_LINE_N.prefixSelector();
        FakePage fake = new FakePage();
        fake.evaluator = (script, arg) -> {
            String selector = (String) ((List<?>) arg).get(0);
            List<?> pairs = List.of();
            if (selector.equals(description)) {
                pairs = List.of(List.of(itemId(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N, 0), "COKE"));
            } else if (selector.equals(amount)) {
                pairs = List.of(List.of(itemId(Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N, 0), "1.50"));
            }
            return List.of(false, false, false, List.of(pairs));
        };
        PosDriver driver = driverOn(fake);

        Receipt receipt = new DomReceiptCapture(new PosDomDriver(driver), PosDomDriver.itemFamilies()).capture();

        assertEquals(1, receipt.lines().size());
        assertEquals("COKE", receipt.lines().get(0).description());
        assertEquals("1.50", receipt.lines().get(0).amount());
        assertEquals(List.of(), fake.named("waitFor"), "the receipt read is one query per family, no waits");
    }

    private static String itemId(com.verifone.cat.pos.Locator family, int index) {
        return family.at(index).literals().get(0);
    }

    // -- sources (DESIGN.md 4.2 rules 1 and 2) -----------------------------

    @Test
    void noModuleInThePosDriverSleeps() throws Exception {
        for (String sub : List.of("driver", "pages")) {
            Path root = Paths.get("src", "main", "java", "com", "verifone", "cat", "pos", sub);
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file);
                    assertFalse(text.contains("Thread.sleep("), file + " sleeps");
                    assertFalse(text.contains("waitForTimeout("), file + " waits by timeout");
                }
            }
        }
    }

    private static java.util.Set<String> setOf(String... values) {
        return java.util.Set.of(values);
    }
}
