package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.UnresolvedLocatorError;
import com.verifone.cat.pos.driver.PosDriver;
import com.verifone.cat.recovery.Timeouts;

/**
 * The page objects' selector routing and snapshot reads, against {@link PagesFakePage}. Port of the
 * page-object parts of the Python CAT's {@code tests/unit/test_pos_driver.py} (section 5.1 calls,
 * {@code expect_idle}/{@code is_idle}, the grade tab). The Python had no unit tests for the device,
 * pairing or sign-on pages; those are covered here as they are written.
 *
 * <p>Evidence about this repository only. Waits that retry through Playwright's matchers are in
 * {@link PagesBrowserTest}.
 */
class PagesRoutingTest {

    private static final Timeouts RIG = Timeouts.parse(Map.of("default_wait", "15s"));

    private static PosDriver driverOn(PagesFakePage fake, String buildVersion) {
        return PosDriver.builder(fake.page())
                .timeouts(RIG)
                .registerId("topaz101")
                .buildVersion(buildVersion)
                .settleOverlays(false)
                .build();
    }

    private static PosDriver driverOn(PagesFakePage fake) {
        return driverOn(fake, null);
    }

    /** The selector the catalogue gives for an element, read from the catalogue itself. */
    private static String selectorOf(Locator locator, Object... args) {
        return locator.at(args).selector();
    }

    // -- fuel ----------------------------------------------------------------

    @Test
    void aPumpTapTargetsTheBuildVariantSelectorOfTheSessionsBuild() {
        PagesFakePage fake = new PagesFakePage();
        new FuelPage(driverOn(fake, "057.00")).pump(1).tap(null);

        assertEquals(List.of(Locators.FUEL_BUTTON.resolve("057.00").at(1).selector()), fake.waitedSelectors());
        assertTrue(fake.waitedSelectors().get(0).contains("LabelPosition"), fake.waitedSelectors().toString());
    }

    @Test
    void aPumpTapOnAnOlderBuildTargetsTheDeprecatedSuffix() {
        PagesFakePage fake = new PagesFakePage();
        new FuelPage(driverOn(fake, "056.00")).pump(1).tap(null);

        assertTrue(fake.waitedSelectors().get(0).endsWith("_Data\"]"), fake.waitedSelectors().toString());
    }

    @Test
    void anExpectedPumpAmountIsRefusedWithoutTheRigsFuelBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new FuelPage(driverOn(new PagesFakePage())).expectPumpAmount(1, "+10.00", null));
    }

    @Test
    void theGradeTabRefusesToGuessAnElementId() {
        UnresolvedLocatorError error = assertThrows(UnresolvedLocatorError.class,
                () -> new FuelPage(driverOn(new PagesFakePage())).gradeTab().select(1));
        assertTrue(error.getMessage().contains("Phase 0"), error.getMessage());
    }

    @Test
    void theGradeTabByNameUsesTheCorpusTextPredicate() {
        PagesFakePage fake = new PagesFakePage();
        new FuelPage(driverOn(fake)).gradeTab().selectByName("PETR-UNLD1").tap(null);

        assertEquals(List.of(selectorOf(Locators.XP_BUTTON_BY_TEXT.atNamed(Map.of("label", "PETR-UNLD1")))),
                fake.waitedSelectors());
    }

    @Test
    void theDetailOverlayPrepayAndCloseRouteToTheirCatalogueIds() {
        PagesFakePage fake = new PagesFakePage();
        FuelPage page = new FuelPage(driverOn(fake));
        page.detail().prepay(2).tap(null);
        page.detail().close();

        assertEquals(List.of(
                Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_BUTTONS_PANEL_PREPAY_BUTTONS_N.at(2).selector(),
                Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON.at().selector()), fake.waitedSelectors());
    }

    // -- item panel ----------------------------------------------------------

    @Test
    void anItemPressIsTheItemPanelIdForThatItem() {
        PagesFakePage fake = new PagesFakePage();
        ItemPanel panel = new ItemPanel(driverOn(fake));
        panel.select(145397);
        panel.select(ItemPanel.MOP.CREDIT);

        assertEquals(List.of(selectorOf(Locators.ITEM_PANEL_N, 145397), selectorOf(Locators.ITEM_PANEL_N, 145395)),
                fake.waitedSelectors());
    }

    @Test
    void theCreditSequenceIsTheTwoDocumentedPresses() {
        PagesFakePage fake = new PagesFakePage();
        new ItemPanel(driverOn(fake)).payCredit();

        assertEquals(List.of(selectorOf(Locators.ITEM_PANEL_N, 145397), selectorOf(Locators.ITEM_PANEL_N, 145395)),
                fake.waitedSelectors());
    }

    @Test
    void theMopIdsAreTheOnesTheAuthoringGuidePublishes() {
        assertEquals(145397, ItemPanel.MOP.PAYMENT_GATEWAY.id());
        assertEquals(145395, ItemPanel.MOP.CREDIT.id());
        assertEquals(145394, ItemPanel.MOP.CASH.id());
        assertEquals(145398, ItemPanel.MOP.OTHER.id());
    }

    @Test
    void theTotalButtonIsFoundByItsVisibleText() {
        PagesFakePage fake = new PagesFakePage();
        new ItemPanel(driverOn(fake)).total(null);

        assertEquals(List.of(selectorOf(Locators.XP_BUTTON_BY_TEXT.atNamed(Map.of("label", "Total")))),
                fake.waitedSelectors());
    }

    // -- express panel -------------------------------------------------------

    @Test
    void theExpressSlotsAreTheDocumentedOnes() {
        assertEquals(7, ExpressPanel.CSR_FUNCTION_SLOT);
        assertEquals(3, ExpressPanel.NO_SALE_SLOT);
        assertEquals(4, ExpressPanel.VOID_TICKET_SLOT);
        assertEquals(5, ExpressPanel.LOG_OUT_SLOT);
        PagesFakePage fake = new PagesFakePage();
        new ExpressPanel(driverOn(fake)).csrFunction().tap(null);
        assertEquals(List.of(selectorOf(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N, 7)), fake.waitedSelectors());
    }

    @Test
    void isIdleReadsTheCsrFunctionText() {
        PagesFakePage fake = new PagesFakePage();
        assertTrue(new ExpressPanel(driverOn(fake)).isIdle());

        fake.text = "Error Correct";
        assertFalse(new ExpressPanel(driverOn(fake)).isIdle());
    }

    @Test
    void isIdleIsFalseWhenTheSlotIsAbsent() {
        PagesFakePage fake = new PagesFakePage();
        fake.present = Set.of();
        assertFalse(new ExpressPanel(driverOn(fake)).isIdle());
    }

    // -- device monitoring ---------------------------------------------------

    @Test
    void aCardWithNoIndexIsTheFirstCard() {
        PagesFakePage fake = new PagesFakePage();
        DeviceMonitoring page = new DeviceMonitoring(driverOn(fake));
        page.leftCard().tap(null);
        page.rightCard(2).tap(null);

        assertEquals(List.of(
                selectorOf(Locators.XP_DIV_LEFT_CARD_CONTAINER_ROW, 1),
                selectorOf(Locators.XP_DIV_RIGHT_CARD_CONTAINER_ROW, 2)), fake.waitedSelectors());
    }

    @Test
    void theDeviceMonitoringSelectorsAreReachedThroughItsFrame() {
        PagesFakePage fake = new PagesFakePage();
        new DeviceMonitoring(driverOn(fake)).amountDue().tap(null);

        assertFalse(fake.waitedSelectors().isEmpty());
        assertTrue(fake.waitedSelectors().get(0).endsWith(selectorOf(Locators.XP_DIV_DUE)),
                fake.waitedSelectors().toString());
    }

    @Test
    void hasErrorIsASnapshotNotAWait() {
        // The frame must attach first: its owner is the first candidate of the frame's family.
        String owner = Locators.FRAMES.get("DEVICE_MONITORING").get(0);
        String error = selectorOf(Locators.XP_DIV_ERROR_PROMPT_MESSAGE_IS_FONT_48);
        PagesFakePage fake = new PagesFakePage();
        fake.present = Set.of(owner, owner + " >> " + error);
        assertTrue(new DeviceMonitoring(driverOn(fake)).hasError());
        assertTrue(fake.waitedSelectors().isEmpty(), "a snapshot must not wait");

        fake.present = Set.of(owner);
        assertFalse(new DeviceMonitoring(driverOn(fake)).hasError());
    }

    // -- pairing -------------------------------------------------------------

    @Test
    void aCodeIsEnteredOneDigitAtATimeThenOk() {
        PagesFakePage fake = new PagesFakePage();
        new PairingPage(driverOn(fake)).enterCode("12", null);

        assertEquals(List.of(
                Locators.PAIRING_KEYPAD_DIGIT.atNamed(Map.of("digit", "1")).selector(),
                Locators.PAIRING_KEYPAD_DIGIT.atNamed(Map.of("digit", "2")).selector(),
                selectorOf(Locators.PAIRING_KEYPAD_OK)), fake.waitedSelectors());
    }

    @Test
    void theUnpairedScreenIsShowingWhenPairDeviceIsThere() {
        PagesFakePage fake = new PagesFakePage();
        fake.present = Set.of(selectorOf(Locators.PAIRING_PAIR_DEVICE));
        PairingPage page = new PairingPage(driverOn(fake));
        assertTrue(page.isShowing());
        assertFalse(page.isRefused());
        assertFalse(page.awaitingCode());
    }

    // -- sign-on -------------------------------------------------------------

    @Test
    void theSignOnWidgetsAreTheCatalogueOnes() {
        PagesFakePage fake = new PagesFakePage();
        SignOnScreen screen = new SignOnScreen(driverOn(fake));
        screen.productName().tap(null);
        screen.prompt().tap(null);
        screen.openDrawerTitle().tap(null);

        assertEquals(List.of(
                selectorOf(Locators.IDLE_SCREEN_VIEW_PRODUCT_NAME),
                selectorOf(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT),
                selectorOf(Locators.CALCULATOR_VIEW_TITLE_TEXT)), fake.waitedSelectors());
    }
}
