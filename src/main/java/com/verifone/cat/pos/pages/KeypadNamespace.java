package com.verifone.cat.pos.pages;

/**
 * Which of the three live keypad ID prefixes to drive. Port of the Python {@code KeypadNamespace}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public enum KeypadNamespace {
    /** {@code VFIKeypadWidget}: the default, and the namespace the authoring guide documents. */
    VFI,
    /** {@code VirtualKeyboardView}: the keypad icon's pad on the lab register (HANDOFF.md 1.28). */
    VIRTUAL_KEYBOARD,
    /** {@code CalculatorView}: the till-opening-float entry. Has no CANCEL key in the catalogue. */
    CALCULATOR
}
