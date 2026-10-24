package com.verifone.cat.pos.pages;

import java.time.Duration;
import java.util.Objects;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The POS overlay numeric keypad. Port of the Python {@code pages.keypad.Keypad}.
 *
 * <p>The namespace decides which catalogue keys are pressed. The calculator namespace has no CANCEL
 * key in the catalogue, so {@link #cancelKey()} and {@link #cancel} refuse it rather than reaching
 * for another widget's key.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public class Keypad extends BasePage {

    private final KeypadNamespace namespace;

    public Keypad(PosDriver driver) {
        this(driver, KeypadNamespace.VFI);
    }

    public Keypad(PosDriver driver, KeypadNamespace namespace) {
        super(driver);
        this.namespace = Objects.requireNonNull(namespace, "a keypad needs a namespace");
    }

    public KeypadNamespace namespace() {
        return namespace;
    }

    /** One key by its label: {@code "1"}, or {@code "00"}, the calculator's double-zero key. */
    public BaseElement digit(String label) {
        return element(digitLocator(), label);
    }

    public BaseElement okKey() {
        return element(okLocator());
    }

    public BaseElement cancelKey() {
        return element(cancelLocator());
    }

    /** CLEAR exists only in the VFI namespace in the catalogue. */
    public BaseElement clearKey() {
        return element(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CLEAR);
    }

    /**
     * Type {@code digits}, then press OK. There is no fixed wait after OK: the next assertion waits on
     * whatever the OK press was supposed to produce (DESIGN.md 4.2 rule 2).
     */
    public void enter(String digits, Duration timeout) {
        enter(digits, true, timeout);
    }

    public void enter(String digits, boolean confirm, Duration timeout) {
        for (char c : digits.toCharArray()) {
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "numeric keypad cannot type '" + c + "' (in " + digits + ")");
            }
            digit(String.valueOf(c)).tap(timeout);
        }
        if (confirm) {
            ok(timeout);
        }
    }

    public void ok(Duration timeout) {
        okKey().tap(timeout);
    }

    public void cancel(Duration timeout) {
        cancelKey().tap(timeout);
    }

    public void clear(Duration timeout) {
        clearKey().tap(timeout);
    }

    public void expectOpen(Duration timeout) {
        digit("1").expectVisible(timeout);
    }

    private Locator digitLocator() {
        return switch (namespace) {
            case VFI -> Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N;
            case VIRTUAL_KEYBOARD -> Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N;
            case CALCULATOR -> Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N;
        };
    }

    private Locator okLocator() {
        return switch (namespace) {
            case VFI -> Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK;
            case VIRTUAL_KEYBOARD -> Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK;
            case CALCULATOR -> Locators.CALCULATOR_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK;
        };
    }

    private Locator cancelLocator() {
        return switch (namespace) {
            case VFI -> Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL;
            case VIRTUAL_KEYBOARD -> Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL;
            case CALCULATOR -> throw new UnsupportedOperationException(
                    "the calculator keypad has no CANCEL key in the catalogue");
        };
    }
}
