package com.verifone.cat.web;

import io.javalin.Javalin;

/**
 * The Tools routes: the page, its polled panel, and one POST per tool.
 *
 * <p>The POSTs require the {@code HX-Request} header, as every POST on these pages does, so another page in the
 * operator's browser cannot reboot a device with a cross-site form post. A refusal is shown on the panel and nothing
 * runs.
 */
final class ToolsRoutes {

    static final String WHAT = "Tool actions come";

    private ToolsRoutes() {}

    static void register(Javalin app, ToolsService tools) {
        app.get(Section.TOOLS.path(), ctx -> ctx.html(Layout.page(Section.TOOLS, "Tools", ToolsPage.body(tools))));
        app.get(ToolsPage.PATH, ctx -> ctx.html(ToolsPage.panel(tools)));
        Requests.Actions actions = Requests.actions(app, WHAT, () -> ToolsPage.panel(tools));
        for (ToolsService.Tool tool : ToolsService.Tool.values()) {
            if (tool == ToolsService.Tool.PUSH_SHIM) {
                continue; // the POS page's own button (PosRoutes), on the register that page shows
            }
            actions.on(tool.path(), ctx -> tools.start(tool, Requests.query(ctx, "register")));
        }
    }
}
