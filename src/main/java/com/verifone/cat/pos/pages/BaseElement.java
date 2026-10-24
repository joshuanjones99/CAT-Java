package com.verifone.cat.pos.pages;

import java.time.Duration;

import com.microsoft.playwright.Locator;
import com.verifone.cat.pos.driver.BoundAssertion;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * One resolved POS element. Port of the Python {@code petrotest.pos.pages.base.Element}: the small
 * verb set the corpus uses on elements (TOUCH_EVENT, VALIDATION_BY_TEXT, IS_ELEMENT_PRESENT,
 * IS_ELEMENT_CLICKABLE, WIDGET_VALIDATION). Every wait takes the rig's budget unless a caller
 * overrides it (DESIGN.md 6.3).
 *
 * <p>Takes a resolved catalogue {@code Locator} (see {@code PosDriver.resolve}). The verbs carry
 * no selector of their own.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public record BaseElement(PosDriver driver, com.verifone.cat.pos.Locator locator) {

    public BaseElement {
        if (driver == null || locator == null) {
            throw new IllegalArgumentException("an element needs a driver and a locator");
        }
    }

    /** The underlying Playwright locator, for the rare case a page object needs it. */
    public Locator pw() {
        return driver.find(locator);
    }

    /** TOUCH_EVENT, with the rig's default wait. */
    public void tap() {
        tap(null);
    }

    /** TOUCH_EVENT, waiting at most {@code timeout} for the element to be visible. */
    public void tap(Duration timeout) {
        driver.tapWithin(timeout, locator);
    }

    /** VALIDATION_BY_TEXT: the element's text, read rather than asserted. */
    public String text(Duration timeout) {
        return driver.textOfWithin(timeout, locator);
    }

    /** IS_ELEMENT_PRESENT: a snapshot, no wait (DESIGN.md 6.3). */
    public boolean isPresent() {
        return driver.isPresent(locator);
    }

    public BaseElement expectVisible(Duration timeout) {
        driver.expectVisibleWithin(timeout, locator);
        return this;
    }

    public BaseElement expectHidden(Duration timeout) {
        driver.expectHiddenWithin(timeout, locator);
        return this;
    }

    /** IS_ELEMENT_CLICKABLE. */
    public BaseElement expectEnabled(Duration timeout) {
        driver.expectation(pw(), timeout).toBeEnabled();
        return this;
    }

    /** Retry until the element reads {@code text}; substring unless {@code exact}. */
    public BaseElement expectText(String text, boolean exact, Duration timeout) {
        driver.expectTextWithin(timeout, locator, text, exact);
        return this;
    }

    /** A bounded assertion for anything the verbs above omit. */
    public BoundAssertion expect(Duration timeout) {
        return driver.expectation(pw(), timeout);
    }
}
