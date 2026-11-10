package com.verifone.cat.web;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.verifone.cat.tools.RegisterTarget;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * The POS page's routes: the page, its polled panel, the Push shim to register button, and the screen's socket.
 *
 * <p>Each request asks {@link PosRegister} for the registers (the rig's, else the lab's 101 and 102) and checks them
 * all at once ({@link PosReach}). The first live one (with no push still running) is the screen, and the panel answers
 * {@code HX-Refresh} so htmx reloads into it. Otherwise both are {@link PosPage#panel}. A push or a screen is only ever
 * for a register on that list: the POST names the address its row showed, and the socket the address its page showed.
 * The POST requires {@code HX-Request}, as every POST on these pages does.
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
            if (now.targets().isEmpty()) {
                ctx.html(PosPage.noRegister(now.why()));
                return;
            }
            String host = Requests.query(ctx, "host");
            RegisterTarget target = now.byHost(host);
            if (target == null) {
                tools.setNotice(host + " is not one of this page's registers; nothing was pushed.");
            } else {
                tools.startPush(target);
            }
            ctx.html(PosPage.panel(probeAll(now.targets(), reach), tools));
        });
        PosScreenRoutes.register(app, host -> {
            PosRegister.Resolved now = registers.current();
            if (host == null || host.isEmpty()) {
                return now.targets().isEmpty() ? fixedScreen : null;
            }
            RegisterTarget target = now.byHost(host);
            return target == null ? null : target.cdpUrl();
        });
    }

    /** Every register checked at once, in list order: two silent registers cost one timeout, not two. */
    static List<PosPage.Probe> probeAll(List<RegisterTarget> targets, PosReach reach) {
        List<CompletableFuture<PosPage.Probe>> checks = targets.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> new PosPage.Probe(t, reach.probe(t.host(), t.shimPort()))))
                .toList();
        return checks.stream().map(CompletableFuture::join).toList();
    }

    /** The first register whose shim answers, when no push is still running; else null. */
    private static RegisterTarget live(List<PosPage.Probe> probes, ToolsService tools) {
        if (tools.running() == ToolsService.Tool.PUSH_SHIM) {
            return null;
        }
        return probes.stream().filter(p -> p.state() == PosReach.State.LIVE).map(PosPage.Probe::target).findFirst()
                .orElse(null);
    }

    private static String page(PosRegister.Resolved now, String fixedScreen, PosReach reach, ToolsService tools) {
        if (now.targets().isEmpty()) {
            return fixedScreen == null ? PosPage.noRegister(now.why()) : PosPage.screen(fixedScreen);
        }
        List<PosPage.Probe> probes = probeAll(now.targets(), reach);
        RegisterTarget live = live(probes, tools);
        return live != null ? PosPage.screen(live) : PosPage.panel(probes, tools);
    }

    private static void panel(Context ctx, PosRegister.Resolved now, PosReach reach, ToolsService tools) {
        if (now.targets().isEmpty()) {
            ctx.html(PosPage.noRegister(now.why()));
            return;
        }
        List<PosPage.Probe> probes = probeAll(now.targets(), reach);
        if (live(probes, tools) != null) {
            // A shim answers: reload the whole page, which opens its screen.
            ctx.header("HX-Refresh", "true");
            ctx.html("<div id=\"" + PosPage.PANEL_ID + "\" class=\"pos-screen pos-screen-none\">"
                    + "<p role=\"status\">The pos-shim answers: opening the screen…</p></div>");
            return;
        }
        ctx.html(PosPage.panel(probes, tools));
    }
}
