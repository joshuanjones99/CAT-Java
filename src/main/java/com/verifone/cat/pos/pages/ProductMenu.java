package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * Merchandise tabs and the products inside them. Port of the Python {@code pages.menus.ProductMenu}.
 *
 * <p>Both spellings of {@code &} occur in the corpus for one tab ({@code Pizza&amp;Burger} and
 * {@code Pizza&Burger}); the catalogue carries them as alternates on one locator, so
 * {@code tab("Pizza&Burger")} finds either.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class ProductMenu extends BasePage {

    public ProductMenu(PosDriver driver) {
        super(driver);
    }

    /** A category tab, for example {@code "Beverages"} or {@code "Pizza&Burger"}. */
    public BaseElement tab(String name) {
        return element(Locators.PRODUCT_MENU_PANEL_TAB_ANY.of(name));
    }

    /** A product inside a tab, for example {@code product("Fruits", "Banana", "Label")}. */
    public BaseElement product(String tab, String name, String suffix) {
        return element(Locators.PRODUCT_MENU_PANEL_ANY.of("Tab_" + tab + "_" + name, suffix));
    }

    public BaseElement product(String tab, String name) {
        return product(tab, name, "Label");
    }

    public void select(String tab, String name, Duration timeout) {
        tab(tab).tap(timeout);
        product(tab, name).tap(timeout);
    }
}
