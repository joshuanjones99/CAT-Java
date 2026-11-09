package com.verifone.cat.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.verifone.cat.desktop.DesktopHost;
import com.verifone.cat.desktop.Shortcuts;
import com.verifone.cat.prefs.WebSettings;
import com.verifone.cat.prefs.WebSettings.Appearance;
import com.verifone.cat.prefs.WebSettings.OpenIn;
import com.verifone.cat.web.SettingsPlaces.Place;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Settings page and its changes: the Appearance choice, where CAT opens, Open in the browser now, and the
 * CAT-Java shortcut.
 *
 * <p>The Appearance choice is written to the web settings file, then copied into a cookie that
 * {@code /static/cat.js} reads on every page to set {@code data-theme}. Every POST needs htmx's
 * {@code HX-Request} header, as {@code POST /keys} does, so another page in the operator's browser
 * cannot change a setting, open a window or write a shortcut. The cookie is refreshed on each visit to the page, so
 * a browser that has forgotten it still matches the file.
 *
 * <p>The Open CAT in choice is read when CAT next starts or its shortcut is clicked ({@code WebServe}); the page
 * itself does not move.
 */
final class SettingsRoutes {

    /** A year, in seconds: the choice is a preference, not a session. */
    static final int COOKIE_AGE = 365 * 24 * 60 * 60;

    private SettingsRoutes() {}

    /**
     * @param settingsFile the web settings file ({@link WebSettings#defaultPath()} in production)
     * @param env          the environment the places are read from
     * @param userHome     the home directory the default places are shown under
     * @param desktop      opens the browser and adds the shortcut
     */
    static void register(Javalin app, Path settingsFile, Map<String, String> env, String userHome,
            DesktopHost desktop) {
        app.get("/settings", ctx -> {
            Appearance current = WebSettings.appearance(settingsFile);
            remember(ctx, current);
            ctx.html(Layout.page(Section.SETTINGS, "Settings",
                    SettingsPage.body(current, WebSettings.openIn(settingsFile), places(env, userHome))));
        });
        app.post("/settings/appearance", ctx -> {
            if (!fromPage(ctx)) {
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
        app.post("/settings/open-in", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            OpenIn chosen = OpenIn.parse(ctx.formParam("open_in"));
            if (chosen == null) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Choose Its own window or The browser.");
                return;
            }
            boolean saved = WebSettings.update(settingsFile, WebSettings.OPEN_IN_KEY, chosen.value());
            ctx.html(SettingsPage.openInResult(chosen, saved));
        });
        app.post("/settings/open-browser", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            String url = "http://" + WebApp.BIND_HOST + ":" + ctx.port() + Section.SETTINGS.path();
            ctx.html(SettingsPage.openBrowserResult(desktop.openInBrowser(url)));
        });
        app.post("/settings/shortcut", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            Shortcuts.Outcome outcome = desktop.addShortcuts();
            ctx.html(SettingsPage.shortcutResult(outcome));
        });
    }

    /** htmx sends HX-Request on every call; a cross-site form post cannot set it. Refuses (403) when it is absent. */
    private static boolean fromPage(Context ctx) {
        return Requests.fromPage(ctx, "Settings changes come");
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
