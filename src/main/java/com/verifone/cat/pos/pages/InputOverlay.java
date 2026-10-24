package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * {@code InputOverlayView}, the amount and data entry overlay. Port of the Python
 * {@code pages.overlay.InputOverlay}. {@code InputOverlayView_Label1} is how a test knows the keypad is
 * asking for what it thinks.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class InputOverlay extends BasePage {

    public InputOverlay(PosDriver driver) {
        super(driver);
    }

    public BaseElement title() {
        return element(Locators.INPUT_OVERLAY_VIEW_TITLE);
    }

    public BaseElement label1() {
        return element(Locators.INPUT_OVERLAY_VIEW_LABEL1);
    }

    public BaseElement label2() {
        return element(Locators.INPUT_OVERLAY_VIEW_LABEL2);
    }

    /** Wait until the overlay is asking for {@code text} (for example "ENTER AMOUNT"). */
    public InputOverlay expectPrompt(String text, Duration timeout) {
        label1().expectText(text, false, timeout);
        return this;
    }

    public void back(Duration timeout) {
        element(Locators.INPUT_OVERLAY_VIEW_BACK_PNG).tap(timeout);
    }

    public void cancel(Duration timeout) {
        element(Locators.INPUT_OVERLAY_VIEW_CANCEL_PNG).tap(timeout);
    }
}
