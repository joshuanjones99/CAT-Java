package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * {@code ParkedMenuPanel}, the parked and recalled transactions. Port of the Python
 * {@code pages.menus.ParkedMenu}. Relevant to recovery rung 2, "clear any parked transaction".
 *
 * <p>The typo in the id is the POS's, not ours.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class ParkedMenu extends BasePage {

    public ParkedMenu(PosDriver driver) {
        super(driver);
    }

    public BaseElement slot(int index) {
        return element(Locators.PARKED_MENU_PANEL_PAKED_MENU_BTN_N, index);
    }

    public void select(int index, Duration timeout) {
        slot(index).tap(timeout);
    }
}
