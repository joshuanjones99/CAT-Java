package com.verifone.cat.pats.runner.handlers.configuration;

/** The field read the configuration handlers share: PATS's empty-means-none read. */
final class ActionFields {

    private ActionFields() {
    }

    /** {@code value}, or {@code null} when it is empty: PATS's {@code x or None}. */
    static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
