package com.verifone.cat.web;

/**
 * Tools: empty on purpose, kept for operator tools to come (docs/web-ui.md). It is not a
 * placeholder for unbuilt work, so it says it is empty rather than claiming to be missing.
 */
final class ToolsPage {

    private ToolsPage() {}

    static String body() {
        return "<section class=\"card placeholder\">"
                + "<h2>Nothing here yet</h2>"
                + "<p>This page is kept for operator tools to come. Everything CAT does today is on the other pages.</p>"
                + "<p class=\"placeholder-note\">Ported from cat/web/pages/manual.py (empty by design)</p>"
                + "</section>";
    }
}
