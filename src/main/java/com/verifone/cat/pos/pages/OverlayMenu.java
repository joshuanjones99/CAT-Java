package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * {@code OverlayMenu}, the modal menu list. Port of the Python {@code pages.menus.OverlayMenu}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class OverlayMenu extends BasePage {

    public OverlayMenu(PosDriver driver) {
        super(driver);
    }

    public BaseElement item(int menuId) {
        return element(Locators.OVERLAY_MENU_N, menuId);
    }

    public void select(int menuId, Duration timeout) {
        item(menuId).tap(timeout);
    }

    public boolean isOpen() {
        return element(Locators.OVERLAY_MENU_MAIN_PANEL).isPresent();
    }
}
