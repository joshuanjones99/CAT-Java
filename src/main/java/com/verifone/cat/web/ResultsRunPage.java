package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.NdjsonReader;
import com.verifone.cat.report.RunFacts;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.results.ResultsLibrary;
import com.verifone.cat.results.RunReplay;
import com.verifone.cat.results.RunStore;
import com.verifone.cat.results.RunSummary;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * One past run, opened from Results: its verdict, steps, tests, cases not run, recovery and
 * preflight, and its log.
 *
 * <p>The Python CAT's Results detail (the Run board and the Log panel, read from the run's
 * events). The Validations tab is not ported yet. The log shows only the most recent
 * {@link #LOG_LINES} lines: a full Cadence run's log is ~255 MB, and the page must not hold
 * all of it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests of this repository's reading of runs.
 */
final class ResultsRunPage {

    /** The most log lines the log page shows, newest last. */
    static final int LOG_LINES = 500;

    private ResultsRunPage() {}

    static void routes(Javalin app, ResultsLibrary library) {
        app.get("/results/run/{runId}", ctx -> run(ctx, library));
        app.get("/results/run/{runId}/log", ctx -> log(ctx, library));
    }

    private static void run(Context ctx, ResultsLibrary library) {
        String runId = ctx.pathParam("runId");
        try {
            Optional<RunReplay> replay = library.load(runId, false);
            if (replay.isEmpty()) {
                notFound(ctx, runId, library);
                return;
            }
            RunSummary summary = RunStore.summarize(replay.get().eventsFile().getParent(), replay.get());
            ctx.html(Layout.page(Section.RESULTS, "Run", body(summary, replay.get())));
        } catch (IOException | RuntimeException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).html(Layout.page(Section.RESULTS, "Run",
                    "<p class=\"error\">This run could not be read: " + esc(e.getMessage()) + "</p>"));
        }
    }

    private static void log(Context ctx, ResultsLibrary library) {
        String runId = ctx.pathParam("runId");
        Optional<Path> folder = library.runFolder(runId);
        if (folder.isEmpty()) {
            notFound(ctx, runId, library);
            return;
        }
        try {
            Deque<JsonNode> tail = new ArrayDeque<>();
            int[] total = {0};
            NdjsonReader.forEach(folder.get().resolve(ReportPaths.EVENTS_FILE), event -> {
                if ("log.line".equals(text(event, "type"))) {
                    total[0]++;
                    tail.addLast(event);
                    if (tail.size() > LOG_LINES) {
                        tail.removeFirst();
                    }
                }
            });
            ctx.html(Layout.page(Section.RESULTS, "Run", logBody(runId, total[0], tail)));
        } catch (IOException | RuntimeException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).html(Layout.page(Section.RESULTS, "Run",
                    "<p class=\"error\">The log could not be read: " + esc(e.getMessage()) + "</p>"));
        }
    }

    private static void notFound(Context ctx, String runId, ResultsLibrary library) {
        ctx.status(HttpStatus.NOT_FOUND).html(Layout.page(Section.RESULTS, "Run",
                "<p class=\"warn\">There is no run " + esc(runId) + " under " + esc(library.root().toString())
                        + ".</p><p><a href=\"/results\">Back to Results</a></p>"));
    }

    /** The detail page's body. */
    static String body(RunSummary summary, RunReplay replay) {
        StringBuilder out = new StringBuilder();
        out.append("<p><a href=\"/results\">← Results</a></p>");
        out.append("<p class=\"mono\">run ").append(esc(summary.runId())).append("</p>");
        out.append("<p>").append(ResultsView.badge(summary.status())).append(" ")
                .append(esc(ResultsView.plans(summary))).append(" · ")
                .append(esc(ResultsView.rig(summary))).append("</p>");
        if (summary.fakePos()) {
            out.append("<p class=\"warn\"><strong>FAKE POS: evidence about this repository, not about a rig.</strong></p>");
        }
        List<String> counts = new ArrayList<>();
        counts.add(replay.passed() + " passed");
        counts.add(replay.failed() + " failed");
        if (!replay.cases().isEmpty()) {
            counts.add(replay.notRunCases().size() + " not run");
        }
        double elapsed = replay.runElapsed();
        if (elapsed > 0) {
            counts.add(ResultsView.elapsed(elapsed));
        }
        if (!summary.gitHead().isEmpty()) {
            counts.add("commit " + summary.gitHead().substring(0, Math.min(10, summary.gitHead().length())));
        }
        out.append("<p class=\"muted\">").append(esc(String.join(" · ", counts))).append("</p>");
        out.append("<p><a href=\"/results/run/").append(ResultsView.query(summary.runId()))
                .append("/log\">Open the log</a> (the most recent ").append(LOG_LINES).append(" lines)</p>");

        out.append("<h2>Steps</h2><table class=\"results-table\"><thead><tr><th>Step</th><th>Result</th>")
                .append("<th class=\"num\">Elapsed</th></tr></thead><tbody>");
        for (RunReplay.StepState step : replay.steps()) {
            out.append("<tr><td>").append(esc(step.name())).append("</td><td>").append(esc(step.status()))
                    .append("</td><td class=\"num\">")
                    .append(step.elapsed() > 0 ? esc(ResultsView.elapsed(step.elapsed())) : "")
                    .append("</td></tr>");
        }
        out.append("</tbody></table>");

        if (!replay.tests().isEmpty()) {
            out.append("<h2>Tests</h2><table class=\"results-table\"><thead><tr><th>Test</th><th>Result</th>")
                    .append("<th class=\"num\">Duration</th><th>Reason</th></tr></thead><tbody>");
            for (RunReplay.TestState test : replay.tests()) {
                out.append("<tr><td class=\"mono\">").append(esc(test.nodeid())).append("</td><td>")
                        .append(ResultsView.badge(test.status())).append("</td><td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1fs", test.duration())).append("</td><td class=\"mono\">")
                        .append(esc(test.excType())).append("</td></tr>");
            }
            out.append("</tbody></table>");
        }

        if (!replay.notRunCases().isEmpty()) {
            out.append("<h2>Not run</h2><p class=\"muted\">Plan cases with no verdict: unmigrated, or never reached.</p><ul>");
            for (RunReplay.PlanCase planned : replay.notRunCases()) {
                out.append("<li class=\"mono\">").append(esc(planned.key())).append(" ")
                        .append(esc(planned.summary())).append("</li>");
            }
            out.append("</ul>");
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

    /** The log page's body: the last lines, each as its time, level, logger and message. */
    static String logBody(String runId, int total, Deque<JsonNode> tail) {
        StringBuilder out = new StringBuilder();
        out.append("<p><a href=\"/results/run/").append(ResultsView.query(runId)).append("\">← Run</a></p>");
        out.append("<p class=\"muted\">").append(total).append(" log line(s) in this run");
        if (total > tail.size()) {
            out.append(", showing the last ").append(tail.size());
        }
        out.append(".</p><pre class=\"log\">");
        for (JsonNode line : tail) {
            String at = text(line, "at");
            out.append(esc(at == null ? "" : at.length() > 19 ? at.substring(11, 19) : at)).append(' ')
                    .append(esc(text(line, "level") == null ? "" : text(line, "level"))).append(' ')
                    .append(esc(text(line, "logger") == null ? "" : text(line, "logger"))).append(' ')
                    .append(esc(text(line, "message") == null ? "" : text(line, "message"))).append('\n');
        }
        return out.append("</pre>").toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
