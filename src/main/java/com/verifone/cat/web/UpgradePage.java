package com.verifone.cat.web;

/** Install Upgrade. Not built yet: a placeholder for {@code cat/web/pages/upgrade.py}. */
final class UpgradePage {

    private UpgradePage() {}

    static String body() {
        return Placeholder.html(
                "The Downloads pickers (shared with Config, from cat/web/pages/downloads_ui.py), a summary, "
                        + "Start, Stop and the stages of an install upgrade.",
                "cat/web/pages/upgrade.py");
    }
}
