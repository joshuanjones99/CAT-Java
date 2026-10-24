package com.verifone.cat.pos.pages;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The cashier sign-on screens: the idle screen, the ID and password prompt, and the till-open
 * title. Port of the Python {@code petrotest.pos.pages.sign_on_screen}. Pos.sign_on drives these
 * as one flow; this exposes each widget for a test that asserts on the way through.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class SignOnScreen extends BasePage {

    public SignOnScreen(PosDriver driver) {
        super(driver);
    }

    /** The IdleScreenView product name. */
    public BaseElement productName() {
        return element(Locators.IDLE_SCREEN_VIEW_PRODUCT_NAME);
    }

    /** The ID/password prompt text. */
    public BaseElement prompt() {
        return element(Locators.USER_ID_PASSWORD_SCREEN_VIEW_PROMPT_TEXT);
    }

    /** The CalculatorView title shown when the till opens. */
    public BaseElement openDrawerTitle() {
        return element(Locators.CALCULATOR_VIEW_TITLE_TEXT);
    }
}
