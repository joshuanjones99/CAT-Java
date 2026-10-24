package com.verifone.cat.report.events;

/** The bounds the Python CAT's event models state, and the one bounding helper they share. */
public final class Limits {

    /**
     * Hard cap on {@code TestFinished.detail}, and on the detail of a {@code test.step}. A pytest
     * longrepr can run to megabytes, and this field crosses a socket once per test. 2000 characters
     * is an assertion line and a few frames of context.
     */
    public static final int DETAIL_MAX_CHARS = 2000;

    /**
     * Cap on {@code TestValidation.diff}, {@code expected} and {@code actual}. The Validations view
     * shows a diff whole, so it is far longer than {@link #DETAIL_MAX_CHARS}.
     */
    public static final int VALIDATION_DIFF_MAX_CHARS = 20_000;

    private Limits() {}

    /**
     * Bounds a failure message to {@code limit} characters, marking that it was cut, as the Python
     * CAT's {@code cat.report.plugin._truncate} does. A silent cut would let a reader take a cut
     * message for the whole story, so the three-dot marker stays.
     */
    public static String truncate(String text, int limit) {
        String stripped = text.strip();
        if (stripped.length() <= limit) {
            return stripped;
        }
        return stripped.substring(0, limit - 3).stripTrailing() + "...";
    }

    /** {@link #truncate(String, int)} at {@link #DETAIL_MAX_CHARS}. */
    public static String truncateDetail(String text) {
        return truncate(text, DETAIL_MAX_CHARS);
    }
}
