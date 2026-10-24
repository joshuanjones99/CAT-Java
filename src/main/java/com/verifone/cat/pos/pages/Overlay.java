package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * {@code OverlayMsgView}, the POS modal message box. Port of the Python {@code pages.overlay.Overlay}
 * (the replacement for VALIDATE_OVERLAY, DESIGN.md 5.1).
 *
 * <p>{@link #dismiss} presses whichever button is present rather than assuming one: the corpus closes
 * its overlays with the CANCEL button far more often than OK, even where the expected key is OK.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class Overlay extends BasePage {

    public Overlay(PosDriver driver) {
        super(driver);
    }

    public BaseElement title() {
        return element(Locators.OVERLAY_MSG_VIEW_TITLE);
    }

    public BaseElement line1() {
        return element(Locators.OVERLAY_MSG_VIEW_LINE1);
    }

    public BaseElement line2() {
        return element(Locators.OVERLAY_MSG_VIEW_LINE2);
    }

    public BaseElement line3() {
        return element(Locators.OVERLAY_MSG_VIEW_LINE3);
    }

    public BaseElement okButton() {
        return element(Locators.OVERLAY_MSG_VIEW_OK_BTN);
    }

    public BaseElement cancelButton() {
        return element(Locators.OVERLAY_MSG_VIEW_CANCEL_BTN);
    }

    /**
     * Assert the overlay's contents. A null line is not checked. Each named line retries on its own, so
     * the first assertion absorbs the overlay's appearance latency. Substring unless {@code exact}.
     */
    public Overlay expect(String title, String line1, String line2, String line3, boolean exact,
            Duration timeout) {
        if (title != null) {
            title().expectText(title, exact, timeout);
        }
        if (line1 != null) {
            line1().expectText(line1, exact, timeout);
        }
        if (line2 != null) {
            line2().expectText(line2, exact, timeout);
        }
        if (line3 != null) {
            line3().expectText(line3, exact, timeout);
        }
        return this;
    }

    /** Presses whichever button is present, then waits for the overlay to go away. */
    public void dismiss(Duration timeout) {
        BaseElement button = cancelButton().isPresent() ? cancelButton() : okButton();
        button.tap(timeout);
        expectClosed(timeout);
    }

    public void ok(Duration timeout) {
        okButton().tap(timeout);
        expectClosed(timeout);
    }

    public void cancel(Duration timeout) {
        cancelButton().tap(timeout);
        expectClosed(timeout);
    }

    public void expectClosed(Duration timeout) {
        title().expectHidden(timeout);
    }

    /** Used by recovery rung 1, "dismiss overlays". A snapshot, no wait. */
    public boolean isOpen() {
        return title().isPresent() || cancelButton().isPresent();
    }
}
