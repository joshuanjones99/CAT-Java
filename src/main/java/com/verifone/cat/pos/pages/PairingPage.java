package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The register's pairing screen: what an unpaired POS shows after a fresh install. Port of the
 * Python {@code petrotest.pos.pages.pairing.PairingScreen}, step for step as PATS's
 * {@code RegisterPairingUtil}. No test case pairs a register, so no corpus case uses these.
 *
 * <p>The three snapshot queries ({@link #isShowing}, {@link #isRefused}, {@link #awaitingCode}) tolerate the one
 * fault the live register raised on 2026-10-28 (run 1a158f28d9cd): right after the Commander's pairing service
 * started, the register's page navigated while the count ran, and Playwright threw "Execution context was
 * destroyed, most likely because of a navigation". The Python's {@code is_present} would throw the same; here
 * the count is retried a few times over about two seconds before the fault is rethrown. Deviation, recorded.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the pairing itself has not completed from Java yet.
 */
public class PairingPage extends BasePage {

    /** How many times a snapshot query is retried across a navigation, and the pause between tries. */
    static final int NAVIGATION_RETRIES = 4;
    static final Duration NAVIGATION_PAUSE = Duration.ofMillis(500);
    static final String CONTEXT_DESTROYED = "Execution context was destroyed";

    /** {@code isPresent} across a page navigation: "not yet" rather than a fault, for a bounded moment. */
    private boolean present(Locator locator) {
        PlaywrightException last = null;
        for (int attempt = 0; attempt <= NAVIGATION_RETRIES; attempt++) {
            try {
                return element(locator).isPresent();
            } catch (PlaywrightException e) {
                if (e.getMessage() == null || !e.getMessage().contains(CONTEXT_DESTROYED)) {
                    throw e;
                }
                last = e;
                LockSupport.parkNanos(NAVIGATION_PAUSE.toNanos());
            }
        }
        throw last;
    }

    public PairingPage(PosDriver driver) {
        super(driver);
    }

    /** Unpaired: "Pair device", or the page a refused tap leads to. */
    public boolean isShowing() {
        return present(Locators.PAIRING_PAIR_DEVICE) || isRefused();
    }

    /** The pairingrefused page: a tap came before the pairing service was up. */
    public boolean isRefused() {
        return present(Locators.PAIRING_REFUSED_OK);
    }

    /** The refused page's OK, back to "Pair device". */
    public void backToStart(Duration timeout) {
        element(Locators.PAIRING_REFUSED_OK).tap(timeout);
        element(Locators.PAIRING_PAIR_DEVICE).expectVisible(timeout);
    }

    /** The code keypad is up: the tap was accepted. */
    public boolean awaitingCode() {
        return present(Locators.PAIRING_KEYPAD_OK);
    }

    /** clickPairPOSButton: the POS then waits for a code. */
    public void start(Duration timeout) {
        element(Locators.PAIRING_PAIR_DEVICE).tap(timeout);
    }

    /** enterPairingOTPOnPOS: one keypad button per character, then OK. */
    public void enterCode(String code, Duration timeout) {
        for (char character : code.toCharArray()) {
            element(Locators.PAIRING_KEYPAD_DIGIT.atNamed(Map.of("digit", String.valueOf(character))))
                    .tap(timeout);
        }
        element(Locators.PAIRING_KEYPAD_OK).tap(timeout);
    }

    /** clickOKonPOSAfterSuccessfulPairing. */
    public void confirm(Duration timeout) {
        element(Locators.PAIRING_PAIRED).expectVisible(timeout);
        element(Locators.PAIRING_PAIRED_OK).tap(timeout);
    }
}
