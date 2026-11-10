package com.verifone.cat.web;


import com.verifone.cat.runconfig.ConfigForm;

import io.javalin.Javalin;

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
        Requests.Actions actions = Requests.actions(app, "Config changes come",
                () -> RunConfigPage.panel(form.view()));
        actions.on("/config/plan", ctx -> form.togglePlan(Requests.form(ctx, "key")));
        actions.on("/config/scope", ctx -> form.setScope(ctx.formParam("mode"), ctx.formParam("tags"),
                ctx.formParam("prerequisites") != null));
        actions.on("/config/case", ctx -> form.tickCase(Requests.form(ctx, "key")));
    }
}
