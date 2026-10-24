package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.Map;

import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The register's pairing screen: what an unpaired POS shows after a fresh install. Port of the
 * Python {@code petrotest.pos.pages.pairing.PairingScreen}, step for step as PATS's
 * {@code RegisterPairingUtil}. No test case pairs a register, so no corpus case uses these.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class PairingPage extends BasePage {

    public PairingPage(PosDriver driver) {
        super(driver);
    }

    /** Unpaired: "Pair device", or the page a refused tap leads to. */
    public boolean isShowing() {
        return element(Locators.PAIRING_PAIR_DEVICE).isPresent() || isRefused();
    }

    /** The pairingrefused page: a tap came before the pairing service was up. */
    public boolean isRefused() {
        return element(Locators.PAIRING_REFUSED_OK).isPresent();
    }

    /** The refused page's OK, back to "Pair device". */
    public void backToStart(Duration timeout) {
        element(Locators.PAIRING_REFUSED_OK).tap(timeout);
        element(Locators.PAIRING_PAIR_DEVICE).expectVisible(timeout);
    }

    /** The code keypad is up: the tap was accepted. */
    public boolean awaitingCode() {
        return element(Locators.PAIRING_KEYPAD_OK).isPresent();
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
