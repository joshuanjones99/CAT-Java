package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.Map;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The POS item and MOP button grid. Port of the Python {@code petrotest.pos.pages.item_panel}.
 *
 * <p>{@code ItemPanel_{n}} is the busiest locator in the corpus (9,338 references behind 101
 * numeric literals). Its numbers are configuration-specific and opaque, so the MOP ids that the
 * authoring guide publishes for the cadence base config are named in {@link MOP}; the rest stay
 * raw ids through {@link #select(int)}.
 *
 * <p>NOT YET PORTED: {@code select_with_quantity} and {@code pay_cash} need the Python's
 * {@code Keypad} and {@code InputOverlay}, which are the other POS slice's and are not in this
 * tree. They belong here once those classes exist.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The MOP ids are the Python's, verified there on
 * Reg101 for the base config only.
 */
public class ItemPanel extends BasePage {

    /**
     * MOP button ids from the authoring guide. CASH is 145394, not the guide's 145448: on Reg101
     * 145394 is labelled "CASH" and a sale through 145448 tendered "$20 CASH". The Discount, Food
     * Stamp and Loyalty members are gone because the guide's ids read "CASH", "$10 CASH" and
     * "$50 CASH" there.
     */
    public enum MOP {
        /** First MOP step: payment gateway / loyalty selector, labelled "Total" on Reg101. */
        PAYMENT_GATEWAY(145397),
        CREDIT(145395),
        /** Needs an amount on the lab config (isTenderAmtReqd). */
        CASH(145394),
        OTHER(145398);

        private final int id;

        MOP(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public ItemPanel(PosDriver driver) {
        super(driver);
    }

    /** The button for item {@code itemId}. */
    public BaseElement button(int itemId) {
        return element(Locators.ITEM_PANEL_N, itemId);
    }

    public void select(int itemId) {
        select(itemId, null);
    }

    /** Press an item button; a null timeout takes the rig's default. */
    public void select(int itemId, Duration timeout) {
        button(itemId).tap(timeout);
    }

    public void select(MOP mop) {
        select(mop.id(), null);
    }

    public void select(MOP mop, Duration timeout) {
        select(mop.id(), timeout);
    }

    /** The documented two-press credit sequence, as one call. */
    public void payCredit() {
        select(MOP.PAYMENT_GATEWAY);
        select(MOP.CREDIT);
    }

    /** Press Total, by its visible text. */
    public void total(Duration timeout) {
        element(Locators.XP_BUTTON_BY_TEXT.atNamed(Map.of("label", "Total"))).tap(timeout);
    }

    /** The Quantity button, by its visible text. */
    public BaseElement quantityButton() {
        return element(Locators.XP_BUTTON_BY_TEXT.atNamed(Map.of("label", "Quantity")));
    }
}
