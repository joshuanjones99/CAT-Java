package com.verifone.cat.web;

import io.javalin.Javalin;

/**
 * The Install Upgrade routes: the page, its polled panel, and the Start POST.
 *
 * <p>The POST requires the {@code HX-Request} header, as every POST on these pages does, so a page in
 * the operator's browser cannot start an install with a cross-site form post. The refusal, if any, is
 * shown on the panel and nothing runs.
 */
final class UpgradeRoutes {

    private UpgradeRoutes() {}

    static void register(Javalin app, UpgradeService upgrades, DownloadsService downloads) {
        app.get("/upgrade", ctx -> {
            downloads.ensureListings();
            ctx.html(Layout.page(Section.UPGRADE, "Install Upgrade", UpgradePage.body(downloads, upgrades)));
        });
        app.get(UpgradePage.PATH, ctx -> ctx.html(UpgradePage.panel(downloads, upgrades)));
        app.post("/upgrade/start", ctx -> {
            if (!DownloadsRoutes.fromHtmx(ctx)) {
                return;
            }
            upgrades.start();
            ctx.html(UpgradePage.panel(downloads, upgrades));
        });
    }
}
