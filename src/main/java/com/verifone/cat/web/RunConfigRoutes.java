package com.verifone.cat.web;


import com.verifone.cat.runconfig.ConfigForm;

import io.javalin.Javalin;

/**
 * The Config tab's routes: the page, its plan-filter fragment, and the changes that post from it.
 *
 * <p>The page answers at once with a waiting panel that loads {@code /fragments/config}.
 *
 * <p>Every change answers with the whole {@code #run-config} panel, so the page is one server
 * reading of the choices. The changes are POSTs and need htmx's {@code HX-Request} header, as
 * {@code POST /keys} does: a cross-site form post cannot set it, so another page in the operator's
 * browser cannot change the saved choices.
 */
final class RunConfigRoutes {

    private RunConfigRoutes() {}

    /** What the tab says while its panel loads. */
    static final String LOADING = "Reading the test plans and their cases…";

    static void register(Javalin app, ConfigForm form) {
        // The panel reads every plan's case list and the checkout: the page opens at once and the panel loads into it.
        app.get("/run/config", ctx -> ctx.html(Layout.page(Section.RUN, "Run",
                RunPage.body(RunPage.Tab.CONFIG, Html.deferred("div", RunConfigPage.PANEL_ID, "config",
                        "/fragments/config", LOADING)))));
        app.get("/fragments/config", ctx -> ctx.html(RunConfigPage.panel(form.view())));
        // The filter swaps only the plan list, so the filter box keeps its focus while typing; it reads the picker
        // alone, not the whole tab.
        app.get("/fragments/config/plans", ctx -> {
            form.setFilter(ctx.queryParam("filter"));
            ctx.html(RunConfigPage.planList(form.pickerView()));
        });
        Requests.Actions actions = Requests.actions(app, "Config changes come",
                () -> RunConfigPage.panel(form.view()));
        actions.on("/config/plan", ctx -> form.togglePlan(Requests.form(ctx, "key")));
        actions.on("/config/scope", ctx -> form.setScope(ctx.formParam("mode"), ctx.formParam("tags"),
                ctx.formParam("prerequisites") != null));
        actions.on("/config/case", ctx -> form.tickCase(Requests.form(ctx, "key")));
    }
}
