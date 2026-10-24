package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.SetupItem;

/**
 * Setup, ported from the Python CAT's {@code cat/web/pages/setup.py}, read-only.
 *
 * <p>Every item with its result: what passed, what needs a person, and the fix to run. The Python
 * page's sign-in forms are not here: they wrote credential files, and the Java Setup page only
 * says what to enter and where. Check again re-runs every check and swaps the panel.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class SetupPage {

    private SetupPage() {}

    /** The page body: the intro and the panel. */
    static String body(SetupService.Snapshot snapshot) {
        return "<p class=\"muted\">What CAT-Java needs on this PC. Nothing here changes the machine: each "
                + "item that fails says what to run or enter. Check again re-runs every check.</p>"
                + panel(snapshot);
    }

    /** The panel alone: swapped whole by Check again. */
    static String panel(SetupService.Snapshot snapshot) {
        StringBuilder out = new StringBuilder("<section id=\"setup-panel\" class=\"card wide\">");
        out.append("<div class=\"sim-actions\"><button type=\"button\" hx-post=\"/setup/check\""
                + " hx-target=\"#setup-panel\" hx-swap=\"outerHTML\">Check again</button></div>");
        if (!snapshot.checked()) {
            out.append("<p class=\"muted\">Not checked yet. Press Check again to run the checks.</p>");
            return out.append("</section>").toString();
        }
        out.append(summary(snapshot));
        out.append("<table class=\"results-table\"><thead><tr>"
                + "<th>Check</th><th>Status</th><th>Detail</th><th>How to fix</th>"
                + "</tr></thead><tbody>");
        for (SetupItem item : snapshot.items()) {
            out.append(row(item, snapshot.results().get(item.name())));
        }
        out.append("</tbody></table></section>");
        return out.toString();
    }

    /** The one line above the table: how many pass, and how many a person has to act on. */
    static String summary(SetupService.Snapshot snapshot) {
        int total = snapshot.items().size();
        int passing = 0;
        int requiredFailing = 0;
        for (SetupItem item : snapshot.items()) {
            Result result = snapshot.results().get(item.name());
            if (result != null && result.outcome() == Outcome.PASS) {
                passing++;
            } else if (item.required()) {
                requiredFailing++;
            }
        }
        int attention = total - passing;
        String text = passing + " of " + total + " checks pass";
        if (attention == 0) {
            return "<p class=\"ok\">" + esc(text) + ".</p>";
        }
        return "<p class=\"warn\">" + esc(text + "; " + attention + " need attention"
                + (requiredFailing == 0 ? "." : " (" + requiredFailing + " required).")) + "</p>";
    }

    /** One item's row. A result missing from the snapshot was never run, so it reads as not checked. */
    static String row(SetupItem item, Result result) {
        StringBuilder out = new StringBuilder("<tr><td><strong>").append(esc(item.title())).append("</strong><br>")
                .append("<code class=\"mono\">").append(esc(item.name())).append("</code></td>");
        if (result == null) {
            return out.append("<td><span class=\"badge\">not checked</span></td><td></td><td></td></tr>").toString();
        }
        out.append("<td>").append(badge(item, result)).append("</td>");
        out.append("<td class=\"mono\">").append(esc(result.detail())).append("</td>");
        if (result.outcome() == Outcome.PASS) {
            out.append("<td class=\"muted\">none needed</td>");
        } else {
            out.append("<td>").append(esc(item.fixHint()));
            if (item.credential()) {
                out.append("<br><span class=\"muted\">A sign-in: CAT does not write it for you. Enter it yourself.</span>");
            }
            out.append("</td>");
        }
        return out.append("</tr>").toString();
    }

    private static String badge(SetupItem item, Result result) {
        if (result.outcome() == Outcome.PASS) {
            return "<span class=\"badge ok\">ok</span>";
        }
        String text = item.manual() ? "needs attention" : result.outcome().value();
        if (!item.required()) {
            text += " (optional)";
        }
        String css = item.required() ? "error" : "warn";
        return "<span class=\"badge " + css + "\">" + esc(text) + "</span>";
    }
}
