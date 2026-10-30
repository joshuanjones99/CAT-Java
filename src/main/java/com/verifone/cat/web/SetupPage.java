package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.verifone.cat.preflight.Outcome;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.setup.ManualField;
import com.verifone.cat.setup.SetupItem;

/**
 * Setup, ported from the Python CAT's {@code cat/web/pages/setup.py}: what CAT checks and repairs on start, and the
 * sign-ins.
 *
 * <p>Every item is a card with its last result: what passed, what was repaired, what needs a person. Sign-in cards
 * (the Atlassian login, the Verifone login for Artifactory and Bitbucket) take their fields here, show what is saved
 * (masked where secret, with Show), are saved as the Python saves them (a blank field keeps what is saved) and signed
 * in with at once; their badge says {@code rejected} or {@code signed in}. An automatable item that still fails has a
 * Repair button. Check again reruns the whole audit, repairs included. Sign-in cards come first, then failing items.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class SetupPage {

    static final String PANEL_ID = "setup-panel";

    private static final String TARGET = "hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\"";

    private SetupPage() {}

    /** The page body: the intro and the panel. */
    static String body(SetupService.Snapshot snapshot) {
        return "<p class=\"muted\">What CAT needs on this PC. Items CAT can fix are fixed when it checks; sign-ins are "
                + "entered here. Runs refuse to start until both sign-ins are saved.</p>"
                + panel(snapshot, Map.of());
    }

    static String panel(SetupService.Snapshot snapshot) {
        return panel(snapshot, Map.of());
    }

    /**
     * The panel alone: swapped whole by Check again, a save and a repair.
     *
     * @param notes what the last save or repair said, by item name
     */
    static String panel(SetupService.Snapshot snapshot, Map<String, SetupService.Said> notes) {
        StringBuilder out = new StringBuilder("<section id=\"" + PANEL_ID + "\" class=\"card wide\"");
        if (snapshot.checking()) {
            out.append(" hx-get=\"/fragments/setup\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\"");
        }
        out.append('>');
        out.append("<div class=\"sim-actions\"><button type=\"button\" hx-post=\"/setup/check\" ").append(TARGET)
                .append(snapshot.checking() ? " disabled" : "").append(">Check again</button></div>");
        if (!snapshot.status().isEmpty()) {
            out.append("<p class=\"warn\">").append(esc(snapshot.status())).append("</p>");
        }
        if (!snapshot.tools().isEmpty()) {
            out.append("<p class=\"muted\">").append(esc(snapshot.tools())).append("</p>");
        }
        if (!snapshot.checked()) {
            out.append("<p class=\"muted\">").append(snapshot.checking() ? "Checking setup…"
                    : "Not checked yet. Press Check again to run the checks.").append("</p>");
            return out.append("</section>").toString();
        }
        out.append(summary(snapshot));
        List<SetupItem> items = new ArrayList<>(snapshot.items());
        items.sort(Comparator.comparing((SetupItem item) -> !item.credential())
                .thenComparing(item -> ok(snapshot.results().get(item.name()))));
        out.append("<div class=\"setup-cards\">");
        for (SetupItem item : items) {
            out.append(card(item, snapshot, notes.get(item.name())));
        }
        return out.append("</div></section>").toString();
    }

    private static boolean ok(Result result) {
        return result != null && result.ok();
    }

    /** The one line above the cards: how many pass, and how many a person has to act on. */
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

    /** One item's card. */
    static String card(SetupItem item, SetupService.Snapshot snapshot, SetupService.Said note) {
        Result result = snapshot.results().get(item.name());
        StringBuilder out = new StringBuilder("<article class=\"card setup-card\">");
        out.append("<div class=\"setup-head\"><strong>").append(esc(item.title())).append("</strong> ")
                .append("<code class=\"mono muted\">").append(esc(item.name())).append("</code> ")
                .append(badge(item, result, snapshot)).append("</div>");
        if (result == null) {
            return out.append("</article>").toString();
        }
        out.append("<p class=\"mono pre\">").append(esc(result.detail())).append("</p>");
        if (!result.ok() && !item.hasFields() && !item.fixHint().isEmpty()) {
            out.append("<p class=\"muted\">").append(esc(item.fixHint())).append("</p>");
        }
        if (item.hasFields()) {
            out.append(fields(item, result, snapshot));
        } else if (!result.ok() && !item.manual()) {
            out.append("<button type=\"button\" class=\"ghost\" hx-post=\"/setup/repair\" hx-vals=\"")
                    .append(esc("{\"name\":\"" + item.name() + "\"}")).append("\" ").append(TARGET)
                    .append(">Repair</button>");
        }
        if (note != null) {
            out.append("<p class=\"").append(note.ok() ? "ok" : "warn").append("\" role=\"status\">")
                    .append(esc(note.text())).append("</p>");
        }
        return out.append("</article>").toString();
    }

    private static String fields(SetupItem item, Result result, SetupService.Snapshot snapshot) {
        Map<String, String> saved = snapshot.env() == null ? Map.of() : item.saved(snapshot.env());
        StringBuilder out = new StringBuilder("<form class=\"setup-form\" hx-post=\"/setup/save\" ").append(TARGET)
                .append(" hx-vals=\"").append(esc("{\"name\":\"" + item.name() + "\"}")).append("\">");
        for (ManualField field : item.fields()) {
            String id = "setup-" + item.name() + "-" + field.key().replace('.', '-');
            out.append("<label class=\"setup-field\" for=\"").append(esc(id)).append("\">").append(esc(field.label()))
                    .append("</label><span class=\"setup-input\"><input id=\"").append(esc(id)).append("\" name=\"f.")
                    .append(esc(field.key())).append("\" type=\"").append(field.secret() ? "password" : "text")
                    .append("\" autocomplete=\"off\" value=\"").append(esc(saved.getOrDefault(field.key(), "")))
                    .append('"');
            if (result.ok()) {
                out.append(" placeholder=\"leave blank to keep what is saved\"");
            }
            out.append('>');
            if (field.secret()) {
                out.append("<button type=\"button\" class=\"ghost\" data-action=\"reveal\" data-target=\"")
                        .append(esc(id)).append("\" aria-controls=\"").append(esc(id)).append("\">Show</button>");
            }
            out.append("</span>");
        }
        out.append("<button type=\"submit\">Save").append(item.credential() ? " and sign in" : "").append("</button>");
        return out.append("</form>").toString();
    }

    private static String badge(SetupItem item, Result result, SetupService.Snapshot snapshot) {
        if (result == null) {
            return "<span class=\"badge\">not checked</span>";
        }
        if (snapshot.rejected().contains(item.name())) {
            return "<span class=\"badge error\">rejected</span>";
        }
        if (snapshot.signedIn().contains(item.name())) {
            return "<span class=\"badge ok\">signed in</span>";
        }
        if (result.ok()) {
            return "<span class=\"badge ok\">" + (result.outcome() == Outcome.PASS ? "ok" : esc(result.outcome().value()))
                    + "</span>";
        }
        String text = item.manual() ? "needs attention" : result.outcome().value();
        if (!item.required()) {
            text += " (optional)";
        }
        return "<span class=\"badge " + (item.required() ? "error" : "warn") + "\">" + esc(text) + "</span>";
    }
}
