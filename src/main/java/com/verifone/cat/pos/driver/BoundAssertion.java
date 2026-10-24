package com.verifone.cat.pos.driver;

import com.microsoft.playwright.assertions.LocatorAssertions;

/**
 * A Playwright assertion with the rig's timeout applied to every call. Port of the Python
 * {@code _BoundAssertion}: a caller cannot forget the timeout, which is the DESIGN.md 6.3 rule
 * stated as code. Java has no keyword argument, so each verb carries the budget explicitly.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class BoundAssertion {

    private final LocatorAssertions assertion;
    private final double timeoutMs;

    BoundAssertion(LocatorAssertions assertion, double timeoutMs) {
        this.assertion = assertion;
        this.timeoutMs = timeoutMs;
    }

    public void toBeVisible() {
        assertion.isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeoutMs));
    }

    public void toBeHidden() {
        assertion.isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(timeoutMs));
    }

    public void toBeEnabled() {
        assertion.isEnabled(new LocatorAssertions.IsEnabledOptions().setTimeout(timeoutMs));
    }

    public void toHaveText(String text) {
        assertion.hasText(text, new LocatorAssertions.HasTextOptions().setTimeout(timeoutMs));
    }

    public void toContainText(String text) {
        assertion.containsText(text, new LocatorAssertions.ContainsTextOptions().setTimeout(timeoutMs));
    }

    /** The negated assertion, keeping its timeout: {@code expect(x).not().toBeVisible()}. */
    public BoundAssertion not() {
        return new BoundAssertion(assertion.not(), timeoutMs);
    }
}
