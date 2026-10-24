package com.verifone.cat.devices.viperpay;

import java.util.List;
import java.util.Locale;

/**
 * How a display's lines become the text a prompt is matched against. Ported from the Python CAT's
 * {@code exact_prompt_text}, {@code prompt_text}, {@code matches_prompt} and {@code matches_exactly}.
 *
 * <p>Three readings, each copied from PATS's {@code PaymentTerminalSimManagerImpl.getPrompt} in the
 * deployed {@code Automn.jar} (read with {@code javap}):
 * <ul>
 *   <li>the exact text ({@code DISPLAY_KEY_EXACT_MATCH=YES}): lines with null or empty dropped, each
 *       trimmed, joined with a bare comma;</li>
 *   <li>the bracketed text ({@code Arrays.toString}): every line, empty ones too, joined with ", ";</li>
 *   <li>CAT's earlier reading: the non-empty lines joined with a space.</li>
 * </ul>
 * Java's {@code String.trim} strips every character at or below U+0020 and nothing else.
 *
 * <p><b>CAT's one deviation (rig owner, 2026-10-20).</b> A line of blanks is dropped by the exact
 * text too, not kept as "". On Solutions 134 with Petro build 129 the EPS pads its idle prompt with a
 * one-space line, and PATS would then read "...SWIPE CARD," and fail every exact match on that blank.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PromptText {

    private PromptText() {
    }

    /** Java's {@code String.trim}: every character at or below U+0020, and nothing else. */
    public static String javaTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && text.charAt(end - 1) <= ' ') {
            end--;
        }
        return text.substring(start, end);
    }

    /** The text {@code WAIT_FOR_PROMPT} compares when the exact match is on (see the class note). */
    public static String exactText(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            if (line == null || javaTrim(line).isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(javaTrim(line));
        }
        return javaTrim(joined.toString());
    }

    /** The text {@code WAIT_FOR_PROMPT} searches when the exact match is off: the bracketed lines. */
    public static String bracketedText(List<String> lines) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            String line = lines.get(i);
            text.append(line == null ? "null" : line);
        }
        return javaTrim(text.append(']').toString());
    }

    /**
     * Whether {@code prompt} is found in the display, ignoring case: in the bracketed text, or in the
     * non-empty lines joined with spaces (CAT's earlier reading, which nothing that passed on it loses).
     */
    public static boolean matchesPrompt(List<String> lines, String prompt) {
        String needle = lower(javaTrim(prompt));
        String spaced = lower(joinNonEmpty(lines));
        return lower(bracketedText(lines)).contains(needle) || spaced.contains(needle);
    }

    /** Whether the whole display, as {@link #exactText} renders it, equals {@code prompt}, ignoring case. */
    public static boolean matchesExactly(List<String> lines, String prompt) {
        return exactText(lines).equalsIgnoreCase(javaTrim(prompt));
    }

    private static String joinNonEmpty(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (line != null && !line.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(line);
            }
        }
        return text.toString();
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
