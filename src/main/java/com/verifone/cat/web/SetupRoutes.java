package com.verifone.cat.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Setup routes: the page, its fragment (polled while a check runs), Check again, a sign-in card's Save, and an
 * item's Repair.
 *
 * <p>Every POST requires the {@code HX-Request} header, as {@code POST /keys} does, so another page in the operator's
 * browser cannot start a check, save a login or repair the machine with a cross-site form post. A card's fields are
 * posted as {@code f.<key>}; only the item's own declared keys are passed on, and nothing posted is echoed.
 */
final class SetupRoutes {

    private SetupRoutes() {}

    static void register(Javalin app, SetupService setup) {
        app.get("/setup", ctx -> ctx.html(Layout.page(Section.SETUP, "Setup", SetupPage.body(setup.snapshot()))));
        app.get("/fragments/setup", ctx -> ctx.html(SetupPage.panel(setup.snapshot())));
        app.post("/setup/check", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            setup.check(true);
            ctx.html(SetupPage.panel(setup.snapshot()));
        });
        app.post("/setup/save", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            String name = value(ctx, "name");
            Map<String, String> values = new LinkedHashMap<>();
            ctx.formParamMap().forEach((key, list) -> {
                if (key.startsWith("f.") && !list.isEmpty()) {
                    values.put(key.substring(2), list.get(0));
                }
            });
            SetupService.Said said = setup.saveFields(name, values);
            ctx.html(SetupPage.panel(setup.snapshot(), Map.of(name, said)));
        });
        app.post("/setup/repair", ctx -> {
            if (!fromThisPage(ctx)) {
                return;
            }
            String name = value(ctx, "name");
            SetupService.Said said = setup.repair(name);
            setup.shell().notify(said.text(), said.ok() ? "positive" : "error", 5);
            ctx.html(SetupPage.panel(setup.snapshot(), Map.of(name, said)));
        });
    }

    private static String value(Context ctx, String name) {
        List<String> values = ctx.formParams(name);
        return values.isEmpty() ? "" : values.get(0);
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
