package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.List;

import com.verifone.cat.prefs.WebSettings.Appearance;
import com.verifone.cat.web.SettingsPlaces.Place;

/**
 * Settings, as cat/web/pages/settings.py lays it out: Appearance (match Windows, light, dark),
 * where CAT keeps things, and the not-yet-built parts of the Python page.
 *
 * <p>The Appearance choice saves at once (htmx posts the form on change) and answers with a
 * one-line result. The rest of the page is read-only.
 */
final class SettingsPage {

    private SettingsPage() {}

    /** The whole page body. */
    static String body(Appearance current, List<Place> places) {
        return appearanceCard(current) + placesCard(places) + Placeholder.html(
                "Starting CAT (the desktop and Start menu shortcut) and Shut down CAT, which stops the "
                        + "server now. Neither is in CAT-Java yet.",
                "cat/web/pages/settings.py");
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
