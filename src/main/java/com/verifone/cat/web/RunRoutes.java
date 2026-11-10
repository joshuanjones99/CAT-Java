package com.verifone.cat.web;

import java.util.function.UnaryOperator;

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
            answer(ctx, RunBoardHtml.casesInner(view.state(), links), view.busy(),
                    inner -> RunBoardHtml.cases(inner, links, "/fragments/run/cases"));
        });
        app.get("/fragments/run/side", ctx -> {
            RunService.View view = run.view();
            RunLinks links = RunLinks.live(view.busy());
            answer(ctx, RunBoardHtml.sideInner(view.state(), links), view.busy(),
                    inner -> RunBoardHtml.side(inner, links, "/fragments/run/side"));
        });
        app.get("/fragments/run/post", ctx -> {
            RunService.View view = run.view();
            answer(ctx, RunOverviewPage.postInner(view), view.busy(), inner -> RunOverviewPage.post(inner, view.busy()));
        });
        app.get("/fragments/run/step", ctx -> dialog(ctx, run,
                (state, links) -> RunDialogsHtml.step(state, intParam(ctx, "i"), links)));
        app.get("/fragments/run/case", ctx -> dialog(ctx, run,
                (state, links) -> RunDialogsHtml.caseDetail(state, Requests.query(ctx, "key"), links)));
        app.get("/fragments/run/receipts", ctx -> dialog(ctx, run, RunDialogsHtml::receipts));
        app.get("/fragments/run/validations", ctx -> dialog(ctx, run,
                (state, links) -> RunDialogsHtml.validationsDialog(state, Requests.query(ctx, "tab"), links)));
        app.get("/fragments/run/validations/view", ctx -> {
            RunService.View view = run.view();
            ctx.html(RunDialogsHtml.validations(view.state(), Requests.query(ctx, "tab"), RunLinks.live(view.busy())));
        });
        app.get("/fragments/run/validation", ctx -> ctx.html(validationDetail(run.state(), intParam(ctx, "i"))));

        Requests.Actions actions = Requests.actions(app, "Run actions come", () -> overview(form, run));
        actions.on("/run/start",
                ctx -> run.startFromForm(() -> RunStart.from(form, run.startContext()), form.plans()));
        actions.on("/run/preflight", ctx -> run.preflight());
        actions.on("/run/pause", ctx -> run.togglePause());
        actions.on("/run/stop", ctx -> run.stop());
        actions.on("/run/new", ctx -> run.newRun());
        actions.on("/run/schedule",
                ctx -> run.schedule(Requests.form(ctx, "at"), RunStart.from(form, run.startContext())));
        actions.on("/run/schedule/clear", ctx -> run.clearSchedule());
        actions.on("/run/schedule/cancel", ctx -> {
            Integer id = parseId(Requests.form(ctx, "id"));
            if (id != null) {
                run.cancelScheduled(id);
            }
        });
        actions.on("/run/continuous", ctx -> run.toggleContinuous(RunStart.from(form, run.startContext())));

        app.get("/run/log", ctx -> ctx.html(Layout.page(Section.RUN, "Run",
                RunLogPage.body(run, RunLogHtml.Filters.from(ctx)))));
        app.get("/fragments/run/log", ctx -> ctx.html(RunLogPage.panel(run, RunLogHtml.Filters.from(ctx))));
        app.get("/fragments/run/log/body", ctx -> {
            RunService.View view = run.view();
            RunLinks links = RunLinks.live(view.busy());
            RunLogHtml.Filters filters = RunLogHtml.Filters.from(ctx);
            String html = RunLogHtml.body(view.state(), filters, links);
            if (view.busy() && RunLogHtml.bodyHash(html).equals(ctx.queryParam("h"))) {
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

    /**
     * A polled part: {@code 204} when the request's hash is the hash of what it would draw now, on a live run; else
     * {@code inner} in its element ({@code wrap}). The part is drawn once per poll.
     */
    static void answer(Context ctx, String inner, boolean live, UnaryOperator<String> wrap) {
        if (live && RunBoardHtml.hash(inner).equals(ctx.queryParam("h"))) {
            ctx.status(HttpStatus.NO_CONTENT);
            return;
        }
        ctx.html(wrap.apply(inner));
    }

    static int intParam(Context ctx, String name) {
        try {
            return Integer.parseInt(Requests.query(ctx, name).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A pending run's id from the form, or {@code null} when it is not a number. */
    private static Integer parseId(String text) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
