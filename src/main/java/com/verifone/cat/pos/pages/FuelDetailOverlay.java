package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.Locale;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The fuel point detail overlay: prepay and pump function buttons. Port of the Python
 * {@code FuelDetail}. Package-private helper of the fuel slice, reached through
 * {@link FuelPage#detail()}.
 *
 * <p>The buttons' indices are build-specific; their labels are what a cashier reads, so
 * {@link #pressLabelled} matches on the label.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class FuelDetailOverlay extends BasePage {

    /** How many function-button slots are searched for a label. Reg101 has 12. */
    static final int MAX_FUNCTION_BUTTONS = 20;

    FuelDetailOverlay(PosDriver driver) {
        super(driver);
    }

    /** A prepay button: {@code pos.fuel_detail.prepay(2).tap()}. */
    BaseElement prepay(int index) {
        return element(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_BUTTONS_PANEL_PREPAY_BUTTONS_N, index);
    }

    /** A pump function button (Stop among them). */
    BaseElement function(int index) {
        return element(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_FUNCTION_BUTTON_LABEL_N, index);
    }

    BaseElement transactionStatus() {
        return element(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_TRANSACTION_PANEL_TRANS_STATUS_PANEL_TRANS_LBL);
    }

    BaseElement pumpStatus() {
        return element(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_PUMP_STATUS_PANEL_LABEL);
    }

    /**
     * Press the visible function button whose label reads {@code label} (case and spacing
     * ignored). Returns false when none does. One look per slot, no wait for the label itself.
     */
    boolean pressLabelled(String label, Duration timeout) {
        String wanted = normalise(label);
        for (int index = 0; index < MAX_FUNCTION_BUTTONS; index++) {
            for (PosDriver.ElementState state : driver().elementStates(
                    Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_MAIN_PANEL_FUNCTION_BUTTON_LABEL_N.at(index))) {
                if (state.visible() && normalise(state.text()).equals(wanted)) {
                    function(index).tap(timeout);
                    return true;
                }
            }
        }
        return false;
    }

    void close() {
        element(Locators.FUEL_POINT_DETAIL_OVERLAY_VIEW_CLOSE_BUTTON).tap(null);
    }

    /** The overlay is up: its first prepay button is visible. */
    void expectOpen(Duration timeout) {
        prepay(1).expectVisible(timeout);
    }

    private static String normalise(String text) {
        return com.verifone.cat.pos.driver.PosText.normalise(text).toLowerCase(Locale.ROOT);
    }
}
