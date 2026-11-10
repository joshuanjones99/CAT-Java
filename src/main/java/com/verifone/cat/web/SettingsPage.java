package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.List;

import com.verifone.cat.desktop.Shortcuts;
import com.verifone.cat.prefs.WebSettings.Appearance;
import com.verifone.cat.prefs.WebSettings.OpenIn;
import com.verifone.cat.web.SettingsPlaces.Place;

/**
 * Settings, as cat/web/pages/settings.py lays it out: Appearance (match Windows, light, dark),
 * Opening CAT (CAT-Java's own: its window or the browser, and the shortcut), where CAT keeps things, and Starting CAT
 * with Shut down CAT.
 *
 * <p>The Appearance and Open CAT in choices save at once (htmx posts the form on change) and answer with a
 * one-line result.
 */
final class SettingsPage {

    private SettingsPage() {}

    /** The whole page body. */
    static String body(Appearance current, OpenIn openIn, List<Place> places) {
        return appearanceCard(current) + openingCard(openIn) + placesCard(places) + startingCard();
    }

    /** Starting CAT, and Shut down CAT with its confirmation (the Python's last card). */
    static String startingCard() {
        return "<section class=\"card settings-card\"><h2>Starting CAT</h2>"
                + "<p>The CAT-Java shortcut on the Desktop and in the Start menu runs run.cmd web with no console "
                + "window, and opens CAT in its own window or in the browser, as Opening CAT says. If CAT is already "
                + "running, it only opens it again. Close every CAT window and page and CAT closes itself 30 seconds "
                + "later, but never during a run, a schedule, a download or a recording: it waits for them to end "
                + "(--no-browser and --stay keep it serving). Shut down CAT below stops it now, run and all.</p>"
                + Layout.shutdownButton() + "</section>";
    }

    /**
     * Opening CAT: where CAT opens (its own window, or the browser), saved at once like Appearance; Open in the
     * browser now; and Add the CAT-Java shortcut.
     */
    static String openingCard(OpenIn current) {
        StringBuilder radios = new StringBuilder();
        for (OpenIn choice : OpenIn.values()) {
            radios.append("<label class=\"choice\"><input type=\"radio\" name=\"open_in\" value=\"")
                    .append(esc(choice.value())).append('"')
                    .append(choice == current ? " checked" : "")
                    .append("> <span>").append(esc(choice.label()))
                    .append(choice == OpenIn.WINDOW ? " (recommended)" : "")
                    .append("</span></label>\n");
        }
        return "<section class=\"card settings-card\" id=\"opening-cat\">"
                + "<h2>Opening CAT</h2>"
                + "<form id=\"open-in-form\" class=\"appearance\" hx-post=\"/settings/open-in\""
                + " hx-trigger=\"change\" hx-target=\"#open-in-result\" hx-swap=\"innerHTML\">"
                + "<fieldset><legend>Open CAT in</legend>\n" + radios + "</fieldset>"
                + "<p id=\"open-in-result\" class=\"appearance-result\" role=\"status\"></p>"
                + "</form>"
                + "<p class=\"placeholder-note\">Its own window is CAT in a Microsoft Edge (or Chrome) app window, "
                + "with no tabs or address bar. Applies the next time CAT starts or its shortcut is clicked.</p>"
                + "<div class=\"sim-actions\">"
                + "<button type=\"button\" hx-post=\"/settings/open-browser\" hx-target=\"#opening-result\""
                + " hx-swap=\"innerHTML\">Open in the browser now</button>"
                + "<button type=\"button\" hx-post=\"/settings/shortcut\" hx-target=\"#opening-result\""
                + " hx-swap=\"innerHTML\">Add CAT-Java to the Desktop and Start menu</button>"
                + "</div>"
                + "<p id=\"opening-result\" class=\"appearance-result pre\" role=\"status\"></p>"
                + "</section>";
    }

    /** The one-line answer to a saved (or refused) Open CAT in choice. */
    static String openInResult(OpenIn chosen, boolean saved) {
        if (!saved) {
            return "<span class=\"warn\">Could not write the settings file; CAT keeps opening as before.</span>";
        }
        return "<span class=\"ok\">Saved. CAT opens in " + esc(chosen == OpenIn.WINDOW ? "its own window"
                : "the browser") + " from its next start.</span>";
    }

    /** What Open in the browser now did. */
    static String openBrowserResult(String what) {
        boolean ok = what.startsWith("opened");
        return "<span class=\"" + (ok ? "ok" : "warn") + "\">" + esc(ok ? "Opened CAT in the browser." : what)
                + "</span>";
    }

    /** What adding the shortcut did, one line per place. */
    static String shortcutResult(Shortcuts.Outcome outcome) {
        return "<span class=\"" + (outcome.ok() ? "ok" : "warn") + "\">" + esc(outcome.summary()) + "</span>";
    }

    /** The one-line answer to a saved (or refused) Appearance choice. */
    static String appearanceResult(Appearance chosen, boolean saved) {
        if (!saved) {
            return "<span class=\"warn\">Could not write the settings file; "
                    + esc(chosen.label()) + " applies in this browser only.</span>";
        }
        return "<span class=\"ok\">Saved. Every page now shows " + esc(chosen.label()) + ".</span>";
    }

    private static String appearanceCard(Appearance current) {
        StringBuilder radios = new StringBuilder();
        for (Appearance choice : Appearance.values()) {
            radios.append("<label class=\"choice\"><input type=\"radio\" name=\"appearance\" value=\"")
                    .append(esc(choice.value())).append('"')
                    .append(choice == current ? " checked" : "")
                    .append("> <span>").append(esc(choice.label())).append("</span></label>\n");
        }
        return "<section class=\"card settings-card\">"
                + "<h2>Appearance</h2>"
                + "<form id=\"appearance-form\" class=\"appearance\" hx-post=\"/settings/appearance\""
                + " hx-trigger=\"change\" hx-target=\"#appearance-result\" hx-swap=\"innerHTML\">"
                + "<fieldset><legend>Theme</legend>\n" + radios + "</fieldset>"
                + "<p id=\"appearance-result\" class=\"appearance-result\" role=\"status\"></p>"
                + "</form>"
                + "<p class=\"placeholder-note\">Remembered for this operator in the web settings file, "
                + "and applied to every page.</p>"
                + "</section>";
    }

    private static String placesCard(List<Place> places) {
        StringBuilder rows = new StringBuilder();
        for (Place place : places) {
            rows.append("<dt>").append(esc(place.what())).append("</dt><dd>")
                    .append(esc(place.where()));
            if (!place.env().isEmpty()) {
                rows.append("<br><code>$").append(esc(place.env())).append("</code>");
            }
            rows.append("</dd>\n");
        }
        return "<section class=\"card settings-card\">"
                + "<h2>Where CAT keeps things</h2>"
                + "<p class=\"muted\">Set by CAT; an environment variable moves each one. Shown so you know "
                + "where to look.</p>"
                + "<dl class=\"cat-places\">\n" + rows + "</dl>"
                + "</section>";
    }
}
