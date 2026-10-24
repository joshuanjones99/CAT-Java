package com.verifone.cat.web;

/** Settings. Not built yet: a placeholder for {@code cat/web/pages/settings.py}. */
final class SettingsPage {

    private SettingsPage() {}

    static String body() {
        return Placeholder.html(
                "Appearance (match Windows, light or dark), kept in this operator's web settings file, "
                        + "and where CAT keeps its files.",
                "cat/web/pages/settings.py");
    }
}
