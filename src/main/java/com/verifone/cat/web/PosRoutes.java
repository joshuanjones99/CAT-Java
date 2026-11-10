package com.verifone.cat.web;

import com.verifone.cat.tools.RegisterTarget;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * The POS page's routes: the page, its polled panel, the Push shim to register button, and the screen's socket.
 *
 * <p>Each request asks {@link PosRegister} which register it is (the rig's, else the Settings page's address), then
 * checks it ({@link PosReach}). Live (and no push still running), the page is the screen, and the panel answers
 * {@code HX-Refresh} so htmx reloads into it. Otherwise both are {@link PosPage#panel}. With no register, the page
 * says why and keeps polling, so an address saved on Settings takes effect here. The POST requires
 * {@code HX-Request}, as every POST on these pages does, and the address the panel showed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class PosRoutes {

    static final String WHAT = "POS actions come";

    private PosRoutes() {}

    /**
     * @param fixedScreen a DevTools endpoint shown with no check when there is no register (tests' local Chromium), or
     *                    null
     */
    static void register(Javalin app, PosRegister registers, String fixedScreen, PosReach reach, ToolsService tools) {
        app.get(Section.POS.path(), ctx -> ctx.html(Layout.screen(Section.POS, "POS",
                page(registers.current(), fixedScreen, reach, tools))));
        app.get(PosPage.PANEL_PATH, ctx -> panel(ctx, registers.current(), reach, tools));
        app.post(PosPage.PUSH_PATH, ctx -> {
            if (!Requests.fromPage(ctx, WHAT)) {
                return;
            }
            PosRegister.Resolved now = registers.current();
            RegisterTarget target = now.target();
            if (target == null) {
                ctx.html(PosPage.noRegister(now.why()));
                return;
            }
            String host = Requests.query(ctx, "host");
            if (!target.host().equals(host)) {
                tools.setNotice("This page now shows " + target.describe() + ", not " + host
                        + "; nothing was pushed.");
            } else {
                tools.startPush(target);
            }
            ctx.html(PosPage.panel(target, reach.probe(target.host(), target.shimPort()), tools));
        });
        PosScreenRoutes.register(app, () -> {
            RegisterTarget target = registers.current().target();
            return target != null ? target.cdpUrl() : fixedScreen;
        });
    }

    private static String page(PosRegister.Resolved now, String fixedScreen, PosReach reach, ToolsService tools) {
        RegisterTarget target = now.target();
        if (target == null) {
            return fixedScreen == null ? PosPage.noRegister(now.why()) : PosPage.body(now.why(), fixedScreen);
        }
        PosReach.State state = reach.probe(target.host(), target.shimPort());
        if (live(state, tools)) {
            return PosPage.body(target.describe(), target.cdpUrl());
        }
        return PosPage.panel(target, state, tools);
    }

    private static void panel(Context ctx, PosRegister.Resolved now, PosReach reach, ToolsService tools) {
        RegisterTarget target = now.target();
        if (target == null) {
            ctx.html(PosPage.noRegister(now.why()));
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
