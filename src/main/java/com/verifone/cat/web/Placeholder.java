package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

/**
 * The body of a page that is not ported yet: what it will do, and the Python module it
 * will be ported from. It states that nothing is built, so it is never mistaken for a page.
 */
final class Placeholder {

    private Placeholder() {}

    /**
     * @param summary what the page does in the Python CAT, in words from docs/web-ui.md
     * @param module  the Python module, relative to the CAT checkout, e.g. {@code cat/web/pages/results.py}
     */
    static String html(String summary, String module) {
        return "<section class=\"card placeholder\">"
                + "<h2>Not built yet</h2>"
                + "<p>" + esc(summary) + "</p>"
                + "<p class=\"placeholder-note\">Ported from " + esc(module) + " — not built yet</p>"
                + "</section>";
    }
}
