package com.verifone.cat.web;

import java.util.Set;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

/**
 * The Downloads routes: the fragment the Config tab includes and the Install Upgrade page renders,
 * and the POSTs that change the choice, refresh the listings, or start a Commander download.
 *
 * <p>Every POST requires the {@code HX-Request} header, as the other pages' POSTs do, so a page in
 * the operator's browser cannot change the choice or start a download with a cross-site form post.
 * A field name that is not a Downloads choice is refused with a fixed message and is never echoed.
 */
final class DownloadsRoutes {

    /** The choice fields the pickers post. */
    static final Set<String> FIELDS = Set.of("commanderBranch", "commanderBuild", "simBranch", "simBuild",
            "mode", "dist", "foundationBranch", "foundationBuild");

    /** Who a refused POST is told these buttons belong to; Install Upgrade's are refused in the same words. */
    static final String WHAT = "Downloads buttons come";

    private DownloadsRoutes() {}

    static void register(Javalin app, DownloadsService downloads) {
        app.get(DownloadsPanel.PATH, ctx -> {
            downloads.ensureListings();
            ctx.html(DownloadsPanel.fragment(downloads));
        });
        app.post("/downloads/set", ctx -> {
            if (!Requests.fromPage(ctx, WHAT)) {
                return;
            }
            String field = ctx.queryParam("field");
            if (field == null || !FIELDS.contains(field)) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Not a Downloads choice.");
                return;
            }
            downloads.notice(downloads.set(field, ctx.formParam("value")));
            downloads.ensureListings();
            ctx.html(DownloadsPanel.fragment(downloads));
        });
        Requests.Actions actions = Requests.actions(app, WHAT, () -> DownloadsPanel.fragment(downloads));
        actions.on("/downloads/refresh", ctx -> downloads.refresh());
        actions.on("/downloads/fetch", ctx -> downloads.notice(downloads.fetch()));
    }
}
