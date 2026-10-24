package com.verifone.cat.web;

import java.util.Objects;

import com.verifone.cat.runconfig.ConfigForm;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Config tab's routes: the page, its plan-filter fragment, and the changes that post from it.
 *
 * <p>Every change answers with the whole {@code #run-config} panel, so the page is one server
 * reading of the choices. The changes are POSTs and need htmx's {@code HX-Request} header, as
 * {@code POST /keys} does: a cross-site form post cannot set it, so another page in the operator's
 * browser cannot change the saved choices.
 */
final class RunConfigRoutes {

    private RunConfigRoutes() {}

    static void register(Javalin app, ConfigForm form) {
        app.get("/run/config", ctx -> ctx.html(Layout.page(Section.RUN, "Run",
                RunPage.body(RunPage.Tab.CONFIG, RunConfigPage.panel(form.view())))));
        app.get("/fragments/config", ctx -> ctx.html(RunConfigPage.panel(form.view())));
        // The filter swaps only the plan list, so the filter box keeps its focus while typing.
        app.get("/fragments/config/plans", ctx -> {
            form.setFilter(ctx.queryParam("filter"));
            ctx.html(RunConfigPage.planList(form.view()));
        });
        app.post("/config/plan", ctx -> {
            if (fromPage(ctx)) {
                form.togglePlan(Objects.requireNonNullElse(ctx.formParam("key"), ""));
                panel(ctx, form);
            }
        });
        app.post("/config/scope", ctx -> {
            if (fromPage(ctx)) {
                form.setScope(ctx.formParam("mode"), ctx.formParam("tags"),
                        ctx.formParam("prerequisites") != null);
                panel(ctx, form);
            }
        });
        app.post("/config/case", ctx -> {
            if (fromPage(ctx)) {
                form.tickCase(Objects.requireNonNullElse(ctx.formParam("key"), ""));
                panel(ctx, form);
            }
        });
    }

    private static void panel(Context ctx, ConfigForm form) {
        ctx.html(RunConfigPage.panel(form.view()));
    }

    private static boolean fromPage(Context ctx) {
        if ("true".equals(ctx.header("HX-Request"))) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result("Config changes come from the CAT-Java page only.");
        return false;
    }
}
