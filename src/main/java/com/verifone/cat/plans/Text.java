package com.verifone.cat.plans;

import java.util.regex.Pattern;

/**
 * Text helpers shared by the plan records.
 *
 * <p>Jackson leaves a field null when a cache or seed row omits it; the Python dataclasses
 * default such a field to {@code ""}, and the rest of the port reads the empty string.
 */
final class Text {

    /** Python's {@code \s} on a str: any Unicode whitespace. Java's plain {@code \s} is ASCII only. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private Text() {}

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Collapses whitespace and no-break spaces, as {@code cat.plans._clean} does.
     *
     * <p>Written {@code "\\s+"}: in Java source a bare {@code "\s"} is a single space, not the
     * regex class, so the old literal collapsed spaces only and left tabs and newlines alone.
     */
    static String clean(String value) {
        return WHITESPACE.matcher(value.replace(' ', ' ')).replaceAll(" ").strip();
    }
}
