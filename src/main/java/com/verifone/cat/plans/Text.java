package com.verifone.cat.plans;

/**
 * Text helpers shared by the plan records.
 *
 * <p>Jackson leaves a field null when a cache or seed row omits it; the Python dataclasses
 * default such a field to {@code ""}, and the rest of the port reads the empty string.
 */
final class Text {

    private Text() {}

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Collapses whitespace and no-break spaces, as {@code cat.plans._clean} does. */
    static String clean(String value) {
        return value.replace(' ', ' ').replaceAll("\s+", " ").strip();
    }
}
