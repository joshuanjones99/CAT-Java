package com.verifone.cat.web;

import com.verifone.cat.launch.LaunchDefaults;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunStart;
import com.verifone.cat.runservice.RunState;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Run tab's routes: the Overview, its parts' fragments (head, clock, cases, side rail, post-run cards), the
 * dialogs (a step, a case, the Receipt Printer's roll, the Validations), the actions that post from it, and the Log
 * with its body, counter and text.
 *
 * <p>Every action is a POST that needs htmx's {@code HX-Request} header, as {@code POST /keys} and the Config tab's
 * changes do, so a cross-site form post cannot start, pause or stop a run. Each action answers with the whole
 * Overview panel. A polled part that would draw the same answers {@code 204}, so htmx swaps nothing.
 */
final class RunRoutes {

    private RunRoutes() {}

    static void register(Javalin app, ConfigForm form, RunService run) {
        app.get("/run", ctx -> ctx.html(Layout.page(Section.RUN, "Run",
                RunPage.body(RunPage.Tab.OVERVIEW, overview(form, run)))));
        app.get("/fragments/run", ctx -> ctx.html(overview(form, run)));
        app.get("/fragments/run/head", ctx -> ctx.html(RunOverviewPage.head(run.view(), run.scheduleView())));
        app.get("/fragments/run/clock", ctx -> ctx.html(RunOverviewPage.clock(run.view())));
        app.get("/fragments/run/cases", ctx -> {
            RunService.View view = run.view();
            RunLinks links = RunLinks.live(view.busy());
            String inner = RunBoardHtml.casesInner(view.state(), links);
            answer(ctx, RunBoardHtml.cases(view.state(), links, "/fragments/run/cases"), inner, view.busy());
        });
        app.get("/fragments/run/side", ctx -> {
            RunService.View view = run.view();
            RunLinks links = RunLinks.live(view.busy());
            String inner = RunBoardHtml.sideInner(view.state(), links);
            answer(ctx, RunBoardHtml.side(view.state(), links, "/fragments/run/side"), inner, view.busy());
        });
        app.get("/fragments/run/post", ctx -> {
            RunService.View view = run.view();
            answer(ctx, RunOverviewPage.post(view), RunOverviewPage.postInner(view), view.busy());
        });
        app.get("/fragments/run/step", ctx -> dialog(ctx, run,
                (state, links) -> RunDialogsHtml.step(state, intParam(ctx, "i"), links)));
        app.get("/fragments/run/case", ctx -> dialog(ctx, run,
                (state, links) -> RunDialogsHtml.caseDetail(state, param(ctx, "key"), links)));
        app.get("/fragments/run/receipts", ctx -> dialog(ctx, run, RunDialogsHtml::receipts));
        app.get("/fragments/run/validations", ctx -> dialog(ctx, run,
                (state, links) -> RunDialogsHtml.validationsDialog(state, param(ctx, "tab"), links)));
        app.get("/fragments/run/validations/view", ctx -> {
            RunService.View view = run.view();
            ctx.html(RunDialogsHtml.validations(view.state(), param(ctx, "tab"), RunLinks.live(view.busy())));
        });
        app.get("/fragments/run/validation", ctx -> ctx.html(validationDetail(run.state(), intParam(ctx, "i"))));

        app.post("/run/start", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            run.startFromForm(() -> RunStart.from(form, run.startContext()), form.plans());
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
        app.post("/run/schedule", ctx -> {
            if (fromPage(ctx)) {
                run.schedule(formValue(ctx, "at"), RunStart.from(form, run.startContext()));
                ctx.html(overview(form, run));
            }
        });
        app.post("/run/schedule/clear", ctx -> {
            if (fromPage(ctx)) {
                run.clearSchedule();
                ctx.html(overview(form, run));
            }
        });
        app.post("/run/schedule/cancel", ctx -> {
            if (fromPage(ctx)) {
                Integer id = parseId(formValue(ctx, "id"));
                if (id != null) {
                    run.cancelScheduled(id);
                }
                ctx.html(overview(form, run));
            }
        });
        app.post("/run/continuous", ctx -> {
            if (fromPage(ctx)) {
                run.toggleContinuous(RunStart.from(form, run.startContext()));
                ctx.html(overview(form, run));
            }
        });

        app.get("/run/log", ctx -> ctx.html(Layout.page(Section.RUN, "Run",
                RunLogPage.body(run, RunLogHtml.Filters.from(ctx)))));
        app.get("/fragments/run/log", ctx -> ctx.html(RunLogPage.panel(run, RunLogHtml.Filters.from(ctx))));
        app.get("/fragments/run/log/body", ctx -> {
            RunService.View view = run.view();
            RunLinks links = RunLinks.live(view.busy());
            RunLogHtml.Filters filters = RunLogHtml.Filters.from(ctx);
            String html = RunLogHtml.body(view.state(), filters, links);
            if (view.busy() && RunLogHtml.bodyHash(view.state(), filters, links).equals(ctx.queryParam("h"))) {
                ctx.status(HttpStatus.NO_CONTENT);
                return;
            }
            ctx.html(html);
        });
        app.get("/fragments/run/log/count", ctx -> ctx.html(Html.esc(RunLogHtml.count(run.state(),
                RunLogHtml.Filters.from(ctx), intParam(ctx, "since")))));
        app.get("/run/log.txt", ctx -> ctx.contentType("text/plain; charset=utf-8")
                .result(RunLogHtml.text(run.state(), RunLogHtml.Filters.from(ctx))));
    }

    /**
     * Start run's Downloads come from the page's Downloads choice rather than the saved file. The orchestrator's
     * hook: {@code WebApp} calls this once it has made its {@link DownloadsService}.
     */
    static void useDownloads(RunService run, DownloadsService downloads) {
        run.startContext(run.startContext().withDownloads(() -> {
            DownloadsService.Choice c = downloads.choice();
            return new LaunchDefaults.Downloads(c.mode(), c.dist(), c.commanderBranch(), c.commanderBuild(),
                    c.localBuild(), c.simBranch(), c.simBuild());
        }));
    }

    /** The Overview panel as the Config tab reads it now, and the current run. */
    static String overview(ConfigForm form, RunService run) {
        RunService.View view = run.view();
        RunStart.Plan plan = view.started() ? new RunStart.Plan(null, "") : RunStart.from(form, run.startContext());
        return RunOverviewPage.panel(view, plan, run.scheduleView());
    }

    /** A dialog over the live run, answered 204 when a poll would draw the same. */
    interface DialogDraw {
        String draw(RunState state, RunLinks links);
    }

    private static void dialog(Context ctx, RunService run, DialogDraw draw) {
        RunService.View view = run.view();
        RunLinks links = RunLinks.live(view.busy());
        String html = draw.draw(view.state(), links);
        String hash = ctx.queryParam("h");
        if (view.busy() && hash != null && html.contains("h=" + hash + "\"")) {
            ctx.status(HttpStatus.NO_CONTENT);
            return;
        }
        ctx.html(html);
    }

    /** One validation's comparison, by its place in the run's validations. */
    static String validationDetail(RunState state, int index) {
        if (index < 0 || index >= state.validations().size()) {
            return "<p class=\"muted\">That validation is no longer in this run (its case ran again).</p>";
        }
        return RunDialogsHtml.detail(state.validations().get(index));
    }

    /** A polled part: {@code 204} when the request's hash is the hash of what it would draw now, on a live run. */
    static void answer(Context ctx, String html, String inner, boolean live) {
        if (live && RunBoardHtml.hash(inner).equals(ctx.queryParam("h"))) {
            ctx.status(HttpStatus.NO_CONTENT);
            return;
        }
        ctx.html(html);
    }

    static String param(Context ctx, String name) {
        String value = ctx.queryParam(name);
        return value == null ? "" : value;
    }

    static int intParam(Context ctx, String name) {
        try {
            return Integer.parseInt(param(ctx, name).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formValue(Context ctx, String name) {
        String value = ctx.formParam(name);
        return value == null ? "" : value;
    }

    /** A pending run's id from the form, or {@code null} when it is not a number. */
    private static Integer parseId(String text) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean fromPage(Context ctx) {
        if ("true".equals(ctx.header("HX-Request"))) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result("Run actions come from the CAT-Java page only.");
        return false;
    }
}
