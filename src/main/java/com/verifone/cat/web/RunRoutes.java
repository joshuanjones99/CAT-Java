package com.verifone.cat.web;


import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunStart;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Run tab's routes: the Overview, its panel fragment, the actions that post from it, and the
 * Log tab.
 *
 * <p>Every action is a POST that needs htmx's {@code HX-Request} header, as {@code POST /keys} and
 * the Config tab's changes do, so a cross-site form post cannot start, pause or stop a run. Each
 * action answers with the whole Overview panel.
 */
final class RunRoutes {

    private RunRoutes() {}

    static void register(Javalin app, ConfigForm form, RunService run) {
        app.get("/run", ctx -> ctx.html(Layout.page(Section.RUN, "Run",
                RunPage.body(RunPage.Tab.OVERVIEW, overview(form, run)))));
        app.get("/fragments/run", ctx -> ctx.html(overview(form, run)));
        app.post("/run/start", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            RunStart.Plan plan = RunStart.from(form, run.rig());
            if (plan.startable()) {
                run.start(plan.config());
            } else {
                run.refuse(plan.refusal());
            }
            ctx.html(overview(form, run));
        });
        app.post("/run/preflight", ctx -> {
            if (fromPage(ctx)) {
                run.preflight();
                ctx.html(overview(form, run));
            }
        });
        app.post("/run/pause", ctx -> {
            if (fromPage(ctx)) {
                run.togglePause();
                ctx.html(overview(form, run));
            }
        });
        app.post("/run/stop", ctx -> {
            if (fromPage(ctx)) {
                run.stop();
                ctx.html(overview(form, run));
            }
        });
        app.post("/run/new", ctx -> {
            if (fromPage(ctx)) {
                run.newRun();
                ctx.html(overview(form, run));
            }
        });
        app.get("/run/log", ctx -> ctx.html(Layout.page(Section.RUN, "Run", RunLogPage.body(run))));
        app.get("/fragments/run/log", ctx -> ctx.html(RunLogPage.panel(run)));
    }

    /** The Overview panel as the Config tab reads it now, and the current run. */
    static String overview(ConfigForm form, RunService run) {
        RunStart.Plan plan = RunStart.from(form, run.rig());
        return RunOverviewPage.panel(run.view(), form.view(), plan);
    }

    private static boolean fromPage(Context ctx) {
        if ("true".equals(ctx.header("HX-Request"))) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result("Run actions come from the CAT-Java page only.");
        return false;
    }
}
