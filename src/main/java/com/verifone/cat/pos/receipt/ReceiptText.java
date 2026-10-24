package com.verifone.cat.pos.receipt;

import java.util.regex.Pattern;

/**
 * Capture-level text cleaning, shared by the DOM and serial paths. Ported from the Python
 * {@code petrotest.pos.driver.normalise_text} and the receipt module's parse regexes.
 *
 * <p>This is capture normalisation only: non-breaking and figure spaces (the Topaz GWT layout pads
 * columns with them) collapse to a space, and the ends are trimmed. The semantic normalisers
 * (dates, STANs, cashier names) belong to {@code com.verifone.cat.validate} and are not applied
 * here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class ReceiptText {

    /** Python's {@code _SPACES}: space, tab, NBSP, figure space, narrow NBSP. */
    private static final Pattern SPACES = Pattern.compile("[ \t   ]+");

    private ReceiptText() {}

    /** Collapses padding spaces, folds CRLF, and trims. */
    static String normalise(String value) {
        return SPACES.matcher(value.replace("\r\n", "\n")).replaceAll(" ").strip();
    }
}
