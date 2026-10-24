package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * {@code SCOExpandedMenu}, the self-checkout expanded menu. Port of the Python {@code pages.menus.SCOMenu}.
 *
 * <p>Every id is a product name, so the item is parameterised by name rather than given a constant per
 * product. The self-checkout surface is undocumented in the catalogue; this is the whole of what is known.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class SCOMenu extends BasePage {

    public SCOMenu(PosDriver driver) {
        super(driver);
    }

    public BaseElement item(String name, String suffix) {
        return element(Locators.SCO_EXPANDED_MENU_ANY.of(name, suffix));
    }

    public BaseElement item(String name) {
        return item(name, "Label");
    }

    public void select(String name, Duration timeout) {
        item(name).tap(timeout);
    }
}
