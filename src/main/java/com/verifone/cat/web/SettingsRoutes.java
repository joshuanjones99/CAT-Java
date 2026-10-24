package com.verifone.cat.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.verifone.cat.prefs.WebSettings;
import com.verifone.cat.prefs.WebSettings.Appearance;
import com.verifone.cat.web.SettingsPlaces.Place;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Settings page and its one change, the Appearance choice.
 *
 * <p>The choice is written to the web settings file, then copied into a cookie that
 * {@code /static/cat.js} reads on every page to set {@code data-theme}. The POST needs htmx's
 * {@code HX-Request} header, as {@code POST /keys} does, so another page in the operator's browser
 * cannot change it. The cookie is refreshed on each visit to the page, so a browser that has
 * forgotten it still matches the file.
 */
final class SettingsRoutes {

    /** A year, in seconds: the choice is a preference, not a session. */
    static final int COOKIE_AGE = 365 * 24 * 60 * 60;

    private SettingsRoutes() {}

    /**
     * @param settingsFile the web settings file ({@link WebSettings#defaultPath()} in production)
     * @param env          the environment the places are read from
     * @param userHome     the home directory the default places are shown under
     */
    static void register(Javalin app, Path settingsFile, Map<String, String> env, String userHome) {
        app.get("/settings", ctx -> {
            Appearance current = WebSettings.appearance(settingsFile);
            remember(ctx, current);
            ctx.html(Layout.page(Section.SETTINGS, "Settings",
                    SettingsPage.body(current, places(env, userHome))));
        });
        app.post("/settings/appearance", ctx -> {
            // htmx sends HX-Request on every call; a cross-site form post cannot set it.
            if (!"true".equals(ctx.header("HX-Request"))) {
                ctx.status(HttpStatus.FORBIDDEN).result("Settings changes come from the CAT-Java page only.");
                return;
            }
            Appearance chosen = Appearance.parse(ctx.formParam("appearance"));
            if (chosen == null) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Choose Match Windows, Light or Dark.");
                return;
            }
            boolean saved = WebSettings.update(settingsFile, WebSettings.APPEARANCE_KEY, chosen.value());
            remember(ctx, chosen);
            ctx.html(SettingsPage.appearanceResult(chosen, saved));
        });
    }

    private static void remember(Context ctx, Appearance choice) {
        ctx.cookie(WebSettings.APPEARANCE_COOKIE, choice.value(), COOKIE_AGE);
    }

    private static List<Place> places(Map<String, String> env, String userHome) {
        try {
            return SettingsPlaces.rows(env, userHome);
        } catch (RuntimeException e) {
            // A broken install must not stop the page, as the Python CAT's Settings page does.
            return List.of(new Place("(could not read)", e.getClass().getSimpleName() + ": " + e.getMessage(), ""));
        }
    }
}
