package com.verifone.cat.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Setup routes: the page, and the one POST that runs the workstation checks again.
 *
 * <p>The POST requires the {@code HX-Request} header, as {@code POST /keys} does, so another page in
 * the operator's browser cannot start a check with a cross-site form post. The checks only read;
 * nothing a button here does changes the machine (the page shows the commands instead).
 */
final class SetupRoutes {

    private SetupRoutes() {}

    static void register(Javalin app, SetupService setup) {
        app.get("/setup", ctx -> ctx.html(Layout.page(Section.SETUP, "Setup",
                SetupPage.body(setup.snapshot()))));
        app.post("/setup/check", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            setup.check();
            ctx.html(SetupPage.panel(setup.snapshot()));
        });
    }

    /** True if the request came from htmx on this page. Otherwise it is refused and nothing runs. */
    private static boolean fromThisPage(Context ctx) {
        if ("true".equals(ctx.header("HX-Request"))) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result("Setup checks come from the CAT-Java page only.");
        return false;
    }
}
