package com.verifone.cat.web;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * The POS page's routes: the page, its polled panel, and the Push shim to register button.
 *
 * <p>With a register to show ({@code target}), each GET checks it first ({@link PosReach}). Live (and no push still
 * running), the page is the screen, and the panel answers {@code HX-Refresh} so htmx reloads into it. Otherwise both
 * are {@link PosPage#panel}. With no register, the page says why. The POST requires {@code HX-Request}, as every POST
 * on these pages does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PosRoutes {

    static final String WHAT = "POS actions come";

    private PosRoutes() {}

    /**
     * @param cdpUrl the register's DevTools endpoint, or null
     * @param target the register to check, or null (none: the page shows {@code cdpUrl}'s screen, or {@code why})
     * @param why    why there is no register, shown when there is neither a target nor a screen
     */
    static void register(Javalin app, String cdpUrl, PosPage.Target target, String why, PosReach reach,
            ToolsService tools) {
        app.get(Section.POS.path(), ctx -> ctx.html(Layout.screen(Section.POS, "POS",
                page(cdpUrl, target, why, reach, tools))));
        app.get(PosPage.PANEL_PATH, ctx -> panel(ctx, target, why, reach, tools));
        app.post(PosPage.PUSH_PATH, ctx -> {
            if (!Requests.fromPage(ctx, WHAT)) {
                return;
            }
            if (target == null) {
                ctx.html(PosPage.noRegister(why));
                return;
            }
            String register = Requests.query(ctx, "register");
            if (!target.registerId().equals(register)) {
                tools.setNotice("This page shows " + target.registerId() + ", not " + register + "; nothing was pushed.");
            } else {
                tools.start(ToolsService.Tool.PUSH_SHIM, register);
            }
            ctx.html(PosPage.panel(target, reach.probe(target.host(), target.shimPort()), tools));
        });
    }

    private static String page(String cdpUrl, PosPage.Target target, String why, PosReach reach,
            ToolsService tools) {
        if (target == null) {
            return cdpUrl == null ? PosPage.noRegister(why) : PosPage.body(why, cdpUrl);
        }
        PosReach.State state = reach.probe(target.host(), target.shimPort());
        if (live(state, tools)) {
            return PosPage.body(why, cdpUrl);
        }
        return PosPage.panel(target, state, tools);
    }

    private static void panel(Context ctx, PosPage.Target target, String why, PosReach reach,
            ToolsService tools) {
        if (target == null) {
            ctx.html(PosPage.noRegister(why));
            return;
        }
        PosReach.State state = reach.probe(target.host(), target.shimPort());
        if (live(state, tools)) {
            // The shim answers: reload the whole page, which opens the screen.
            ctx.header("HX-Refresh", "true");
            ctx.html("<div id=\"" + PosPage.PANEL_ID + "\" class=\"pos-screen pos-screen-none\">"
                    + "<p role=\"status\">The pos-shim answers: opening the screen…</p></div>");
            return;
        }
        ctx.html(PosPage.panel(target, state, tools));
    }

    /** The screen opens once the shim answers and no push of it is still running. */
    private static boolean live(PosReach.State state, ToolsService tools) {
        return state == PosReach.State.LIVE && tools.running() != ToolsService.Tool.PUSH_SHIM;
    }
}
