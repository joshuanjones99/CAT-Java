package com.verifone.cat.pos.driver;

import java.util.regex.Pattern;

/**
 * POS DOM text, cleaned for comparison. Port of the Python {@code normalise_text}: collapses the
 * non-breaking spaces the Topaz GWT layout pads columns with, and trims. Capture only; the
 * semantic normalisers live in {@code validate}.
 */
public final class PosText {

    /** Non-breaking and figure/narrow spaces, plus ordinary spaces and tabs. */
    private static final Pattern SPACES = Pattern.compile("[ \t\u00a0\u2007\u202f]+");

    private PosText() {}

    public static String normalise(String value) {
        return SPACES.matcher(value.replace("\r\n", "\n")).replaceAll(" ").strip();
    }
}
