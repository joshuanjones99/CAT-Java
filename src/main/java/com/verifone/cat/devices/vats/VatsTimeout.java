package com.verifone.cat.devices.vats;

import java.util.Locale;

/**
 * A bounded wait on the physical pinpad ran out. Stands in for the Python CAT's {@code TimeoutExpired}, with its
 * message: {@code "<what> exceeded its <n>s budget"}, so a failed case says what it waited for and what the terminal
 * showed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsTimeout extends RuntimeException {

    private final double budgetSeconds;

    public VatsTimeout(String what, double budgetSeconds) {
        super(what + " exceeded its " + seconds(budgetSeconds) + "s budget");
        this.budgetSeconds = budgetSeconds;
    }

    /** The budget the wait was given, in seconds. */
    public double budgetSeconds() {
        return budgetSeconds;
    }

    /** Python's {@code :.3g}: three significant digits, no trailing zeros. */
    static String seconds(double value) {
        String text = String.format(Locale.ROOT, "%.3g", value);
        if (text.contains("e") || text.contains("E")) {
            return text;
        }
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }
}
