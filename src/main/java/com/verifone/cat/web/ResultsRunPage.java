package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.verifone.cat.report.RunFacts;
import com.verifone.cat.results.ResultsLibrary;
import com.verifone.cat.results.RunReplay;
import com.verifone.cat.results.RunStore;
import com.verifone.cat.results.RunSummary;
import com.verifone.cat.runservice.RunProgress;
import com.verifone.cat.runservice.RunState;
import com.verifone.cat.runservice.RunView;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * One past run, opened from Results: the Python CAT's {@code cat/web/pages/results.py detail_page}. Its title (the
 * plans by name), its status, the run line ({@code run <id> · rig · fake POS · N cases}), the counts with the
 * commit, and three tabs: Overview (the Run page's own board, read from the run's events, with the step and case
 * dialogs and the Receipt Printer's roll; then the Failures, Not Run and Run Complete cards, and the tests, recovery
 * and preflight tables), Validations (the Run page's Validations view) and Log (the Run page's Log, filtered by
 * source, level and search, with Copy). A simulator's row and a step's "Show this step's log" open the Log tab on
 * that source.
 *
 * <p>The run is replayed through the same fold as a live run ({@code RunStateReducer}); its log lines only when the
 * Log tab or the roll asks for them. The library keeps the newest few runs loaded.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests of this repository's reading of runs.
 */
final class ResultsRunPage {

    /** The tabs, in order. */
    static final List<String> TABS = List.of("overview", "validations", "log");

    private ResultsRunPage() {}

    static void routes(Javalin app, ResultsLibrary library) {
        app.get("/results/run/{runId}", ctx -> page(ctx, library, tabOf(ctx.queryParam("tab"))));
        app.get("/results/run/{runId}/log", ctx -> page(ctx, library, "log"));
        app.get("/results/run/{runId}/step", ctx -> fragment(ctx, library, false,
                (state, links) -> RunDialogsHtml.step(state, RunRoutes.intParam(ctx, "i"), links)));
        app.get("/results/run/{runId}/case", ctx -> fragment(ctx, library, false,
                (state, links) -> RunDialogsHtml.caseDetail(state, RunRoutes.param(ctx, "key"), links)));
        app.get("/results/run/{runId}/receipts", ctx -> fragment(ctx, library, true, RunDialogsHtml::receipts));
        app.get("/results/run/{runId}/validations", ctx -> fragment(ctx, library, false,
                (state, links) -> RunDialogsHtml.validations(state, RunRoutes.param(ctx, "tab"), links)));
        app.get("/results/run/{runId}/validation", ctx -> fragment(ctx, library, false,
                (state, links) -> RunRoutes.validationDetail(state, RunRoutes.intParam(ctx, "i"))));
        app.get("/results/run/{runId}/log/body", ctx -> fragment(ctx, library, true,
                (state, links) -> RunLogHtml.body(state, RunLogHtml.Filters.from(ctx), links)));
        app.get("/results/run/{runId}/log/count", ctx -> ctx.html(""));
        app.get("/results/run/{runId}/log.txt", ctx -> {
            Optional<RunReplay> replay = load(ctx, library, true);
            if (replay.isPresent()) {
                ctx.contentType("text/plain; charset=utf-8")
                        .result(RunLogHtml.text(replay.get().state(), RunLogHtml.Filters.from(ctx)));
            }
        });
    }

    static String tabOf(String requested) {
        return requested != null && TABS.contains(requested) ? requested : "overview";
    }

    private static Optional<RunReplay> load(Context ctx, ResultsLibrary library, boolean logs) {
        String runId = ctx.pathParam("runId");
        try {
            Optional<RunReplay> replay = library.load(runId, logs);
            if (replay.isEmpty()) {
                notFound(ctx, runId, library);
            }
            return replay;
        } catch (IOException | RuntimeException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).html(Layout.page(Section.RESULTS, "Run",
                    "<p class=\"error\">This run could not be read: " + esc(e.getMessage()) + "</p>"));
            return Optional.empty();
        }
    }

    private interface Draw {
        String draw(RunState state, RunLinks links);
    }

    private static void fragment(Context ctx, ResultsLibrary library, boolean logs, Draw draw) {
        Optional<RunReplay> replay = load(ctx, library, logs);
        replay.ifPresent(r -> ctx.html(draw.draw(r.state(), RunLinks.result(ctx.pathParam("runId")))));
    }

    private static void page(Context ctx, ResultsLibrary library, String tab) {
        Optional<RunReplay> replay = load(ctx, library, "log".equals(tab));
        if (replay.isEmpty()) {
            return;
        }
        try {
            RunSummary summary = RunStore.summarize(replay.get().eventsFile().getParent(), replay.get());
            String body = body(summary, replay.get(), tab, ctx);
            ctx.html(Layout.page(Section.RESULTS, "Run", body));
        } catch (IOException | RuntimeException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).html(Layout.page(Section.RESULTS, "Run",
                    "<p class=\"error\">This run could not be read: " + esc(e.getMessage()) + "</p>"));
        }
    }

    private static void notFound(Context ctx, String runId, ResultsLibrary library) {
        ctx.status(HttpStatus.NOT_FOUND).html(Layout.page(Section.RESULTS, "Run",
                "<p class=\"warn\">There is no run " + esc(runId) + " under " + esc(library.root().toString())
                        + ".</p><p><a href=\"/results\">Back to Results</a></p>"));
    }

    /** The detail page's body: the heading, the counts, the tab strip and the tab. */
    static String body(RunSummary summary, RunReplay replay, String tab, Context ctx) {
        RunState state = replay.state();
        RunLinks links = RunLinks.result(summary.runId());
        StringBuilder out = new StringBuilder(RunStyle.assets());
        out.append("<div id=\"").append(RunBoardHtml.DIALOG).append("\"></div>");
        out.append("<p><a href=\"/results\">← Results</a></p>");
        out.append("<h2>").append(esc(RunView.runTitle(state))).append(' ')
                .append(RunBoardHtml.badge(RunView.runStatus(state))).append("</h2>");
        out.append("<p class=\"mono muted\">").append(esc(RunView.describe(state).isEmpty()
                ? "run " + summary.runId() : RunView.describe(state))).append("</p>");
        if (summary.fakePos()) {
            out.append("<p class=\"warn\"><strong>FAKE POS: evidence about this repository, not about a rig.</strong></p>");
        }
        out.append("<p class=\"muted\">").append(esc(String.join(" · ", counts(state, replay, summary)))).append("</p>");
        String base = "/results/run/" + ResultsView.query(summary.runId());
        out.append("<nav class=\"run-tabs\" aria-label=\"Run view\">");
        for (String[] each : new String[][] {{"overview", "Overview"}, {"validations", "Validations"},
                {"log", "Log"}}) {
            out.append("<a href=\"").append(base).append("?tab=").append(each[0]).append('"')
                    .append(each[0].equals(tab) ? " aria-current=\"page\"" : "").append('>').append(each[1])
                    .append("</a>");
        }
        out.append("</nav>");
        switch (tab) {
            case "validations" -> out.append(RunDialogsHtml.validations(state,
                    RunRoutes.param(ctx, "vtab"), links));
            case "log" -> out.append(RunLogHtml.panel(state, RunLogHtml.Filters.from(ctx), links));
            default -> out.append(overview(state, replay, links));
        }
        return out.toString();
    }

    /** {@code 13 passed · 5 failed · 9 not run · 1h 02m · commit 0123456789}, in case units with a plan. */
    static List<String> counts(RunState state, RunReplay replay, RunSummary summary) {
        Map<String, Integer> verdicts = RunView.verdictCounts(state);
        List<String> parts = new ArrayList<>();
        parts.add(verdicts.get("passed") + " passed");
        parts.add(verdicts.get("failed") + " failed");
        if (!state.cases().isEmpty()) {
            parts.add(state.countNotRun() + " not run");
        }
        double elapsed = replay.runElapsed();
        if (elapsed > 0) {
            parts.add(RunView.elapsedText(elapsed));
        }
        String head = summary.gitHead();
        if (!head.isEmpty()) {
            parts.add("commit " + head.substring(0, Math.min(10, head.length())));
        }
        return parts;
    }

    /** The Overview tab: the board, the post-run cards, then the tests, recovery and preflight. */
    static String overview(RunState state, RunReplay replay, RunLinks links) {
        StringBuilder out = new StringBuilder();
        out.append(RunBoardHtml.board(state, links, "", ""));
        out.append(RunOverviewPage.postCards(state, new RunProgress.Snapshot(state), "From the run's events."));

        if (!state.tests().isEmpty()) {
            out.append("<h2>Tests</h2><table class=\"results-table\"><thead><tr><th>Test</th><th>Result</th>")
                    .append("<th class=\"num\">Duration</th><th>Reason</th></tr></thead><tbody>");
            for (RunState.TestState test : state.tests().values()) {
                out.append("<tr><td class=\"mono\">").append(esc(test.nodeid())).append("</td><td>")
                        .append(RunBoardHtml.badge(test.status())).append("</td><td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1fs", test.duration())).append("</td><td class=\"mono\">")
                        .append(esc(test.excType())).append("</td></tr>");
            }
            out.append("</tbody></table>");
        }

        if (!replay.facts().recoveryRungs().isEmpty()) {
            out.append("<h2>Recovery</h2><dl>");
            replay.facts().recoveryRungs().forEach((nodeid, rungs) -> {
                StringBuilder steps = new StringBuilder();
                rungs.forEach(rung -> steps.append(rung.rung()).append(" ").append(rung.outcome()).append("; "));
                out.append("<dt class=\"mono\">").append(esc(nodeid)).append("</dt><dd>")
                        .append(esc(steps.toString().strip())).append("</dd>");
            });
            out.append("</dl>");
        }

        if (!replay.facts().preflightChecks().isEmpty()) {
            out.append("<h2>Preflight</h2><table class=\"results-table\"><thead><tr><th>Check</th><th>Phase</th>")
                    .append("<th>Result</th><th>Detail</th></tr></thead><tbody>");
            for (RunFacts.PreflightCheck check : replay.facts().preflightChecks()) {
                out.append("<tr><td>").append(esc(check.name())).append("</td><td>").append(esc(check.phase()))
                        .append("</td><td>").append(ResultsView.badge(check.outcome())).append("</td><td>")
                        .append(esc(check.detail())).append("</td></tr>");
            }
            out.append("</tbody></table>");
        }
        return out.toString();
    }
}
