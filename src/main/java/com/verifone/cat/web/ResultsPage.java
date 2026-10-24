package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.verifone.cat.results.ResultsLibrary;
import com.verifone.cat.results.RunIndex;
import com.verifone.cat.results.RunStats;
import com.verifone.cat.results.RunSummary;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Results: every past run on disk, by segment, with a filter, and Statistics over them.
 *
 * <p>The Python CAT's {@code cat/web/pages/results.py}. The page is a shell that loads its
 * body from {@code /results/view} on arrival, so opening the page reads nothing from disk;
 * the view reads the index (cache-first, stat-keyed) and redraws. A segment is always one of
 * rig, fake POS or no rig, and only that segment is counted.
 *
 * <p>Every value from a run's events file is escaped with {@link Html#esc}, and a run's
 * folder is named only through {@link ResultsLibrary#runFolder}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests of this repository's reading of runs.
 */
final class ResultsPage {

    private ResultsPage() {}

    /** Registers the Results page, its view fragment, and the run and log pages. */
    static void routes(Javalin app, ResultsLibrary library) {
        app.get("/results", ctx -> ctx.html(Layout.page(Section.RESULTS, "Results", body())));
        app.get("/results/view", ctx -> view(ctx, library));
        ResultsRunPage.routes(app, library);
    }

    /** The page body: a shell that loads the view as soon as it arrives. */
    static String body() {
        return "<div id=\"results\" hx-get=\"/results/view?segment=rig&amp;tab=runs\" hx-trigger=\"load\""
                + " hx-swap=\"outerHTML\"><p class=\"muted\">Reading the results index…</p></div>";
    }

    private static void view(Context ctx, ResultsLibrary library) {
        String segment = ResultsView.segment(ctx.queryParam("segment"));
        String tab = "stats".equals(ctx.queryParam("tab")) ? "stats" : "runs";
        String filter = ctx.queryParam("q") == null ? "" : ctx.queryParam("q").strip();
        try {
            RunIndex.IndexResult result = library.index();
            ctx.html(fragment(library, result, segment, tab, filter));
        } catch (RuntimeException e) {
            ctx.html("<div id=\"results\">" + controls(segment, tab, filter)
                    + "<p class=\"error\">The results index could not be read: " + esc(e.getMessage()) + "</p></div>");
        }
    }

    /** The whole section: controls, then either the runs or the statistics. */
    static String fragment(ResultsLibrary library, RunIndex.IndexResult result,
            String segment, String tab, String filter) {
        StringBuilder out = new StringBuilder("<div id=\"results\">");
        out.append(controls(segment, tab, filter));
        int count = result.rows().size();
        out.append("<p class=\"muted\">").append(count).append(count == 1 ? " run" : " runs")
                .append(" under ").append(esc(library.root().toString()));
        if (!result.failed().isEmpty()) {
            out.append(" · ").append(result.failed().size()).append(" could not be read");
        }
        out.append("</p>");
        if ("stats".equals(tab)) {
            out.append(statistics(RunStats.compute(result.rows(), segment, library.placeholder(), RunStats.WINDOW)));
        } else {
            out.append(runs(result.rows(), segment, filter));
        }
        return out.append("</div>").toString();
    }

    /** Segment buttons, the Runs and Statistics tabs, and the filter; each keeps the others' state. */
    static String controls(String segment, String tab, String filter) {
        StringBuilder out = new StringBuilder("<div class=\"results-controls\">");
        out.append("<div class=\"seg\" role=\"group\" aria-label=\"Segment\">");
        for (String name : ResultsView.SEGMENTS) {
            boolean current = name.equals(segment);
            out.append("<button type=\"button\" class=\"seg-btn").append(current ? " active" : "")
                    .append("\" aria-pressed=\"").append(current)
                    .append("\" hx-get=\"/results/view?segment=").append(ResultsView.query(name))
                    .append("&amp;tab=").append(esc(tab)).append("&amp;q=").append(ResultsView.query(filter))
                    .append("\" hx-target=\"#results\" hx-swap=\"outerHTML\">")
                    .append(esc(ResultsView.segmentLabel(name))).append("</button>");
        }
        out.append("</div>");
        out.append("<nav class=\"tabs\" aria-label=\"Results view\">");
        out.append(tabLink("runs", "Runs", segment, filter, tab));
        out.append(tabLink("stats", "Statistics", segment, filter, tab));
        out.append("</nav>");
        out.append("<input type=\"search\" name=\"q\" value=\"").append(esc(filter))
                .append("\" placeholder=\"Filter by plan, rig, result or run\" aria-label=\"Filter runs\""
                        + " hx-get=\"/results/view?segment=").append(ResultsView.query(segment))
                .append("&amp;tab=runs\" hx-trigger=\"input changed delay:300ms, search\""
                        + " hx-target=\"#results\" hx-swap=\"outerHTML\">");
        return out.append("</div>").toString();
    }

    private static String tabLink(String name, String label, String segment, String filter, String current) {
        boolean active = name.equals(current);
        String target = "/results/view?segment=" + ResultsView.query(segment) + "&amp;tab=" + name
                + "&amp;q=" + ResultsView.query(filter);
        return "<a href=\"" + target + "\""
                + (active ? " class=\"active\" aria-current=\"page\"" : "")
                + " hx-get=\"" + target + "\" hx-target=\"#results\" hx-swap=\"outerHTML\">"
                + esc(label) + "</a>";
    }

    /** The runs of one segment whose plan, rig, result, date or run id match the filter. */
    static String runs(List<RunSummary> rows, String segment, String filter) {
        List<RunSummary> shown = new ArrayList<>();
        String needle = filter.toLowerCase(Locale.ROOT);
        for (RunSummary row : rows) {
            if (segment.equals(row.segment()) && (needle.isEmpty() || haystack(row).contains(needle))) {
                shown.add(row);
            }
        }
        if (shown.isEmpty()) {
            boolean any = rows.stream().anyMatch(r -> segment.equals(r.segment()));
            return "<p class=\"muted\">" + (any ? "No run matches the filter." : "No runs in this segment.") + "</p>";
        }
        StringBuilder out = new StringBuilder("<table class=\"results-table\"><thead><tr>")
                .append("<th>Started</th><th>Plan(s)</th><th>Rig</th><th>Result</th>")
                .append("<th class=\"num\">Pass</th><th class=\"num\">Fail</th><th class=\"num\">Not run</th>")
                .append("<th class=\"num\">Elapsed</th><th>Run</th></tr></thead><tbody>");
        for (RunSummary row : shown) {
            out.append("<tr><td>").append(esc(ResultsView.local(row.startedAt()))).append("</td>")
                    .append("<td>").append(esc(ResultsView.plans(row))).append("</td>")
                    .append("<td>").append(esc(ResultsView.rig(row))).append("</td>")
                    .append("<td>").append(ResultsView.badge(row.status())).append("</td>")
                    .append("<td class=\"num\">").append(row.passed() + row.warned()).append("</td>")
                    .append("<td class=\"num\">").append(row.failed()).append("</td>")
                    .append("<td class=\"num\">").append(row.notRun()).append("</td>")
                    .append("<td class=\"num\">")
                    .append(row.elapsed() > 0 ? esc(ResultsView.elapsed(row.elapsed())) : "")
                    .append("</td>")
                    .append("<td><a class=\"mono\" href=\"/results/run/").append(ResultsView.query(row.runId()))
                    .append("\">").append(esc(row.runId())).append("</a></td></tr>");
        }
        return out.append("</tbody></table>").toString();
    }

    private static String haystack(RunSummary row) {
        List<String> fields = new ArrayList<>();
        fields.add(row.runId());
        fields.add(row.rig());
        fields.add(row.status());
        fields.add(row.startedAt());
        fields.addAll(row.testPlans());
        fields.add(ResultsView.plans(row));
        return String.join(" ", fields).toLowerCase(Locale.ROOT);
    }

    /** Statistics for one segment: what is counted and what is excluded, then the trend and the tables. */
    static String statistics(RunStats.SegmentStats stats) {
        StringBuilder out = new StringBuilder("<section class=\"card stats\">");
        out.append("<h2>").append(esc(ResultsView.segmentLabel(stats.segment()))).append("</h2>");
        out.append("<p>").append(stats.runs()).append(" runs · finished ").append(stats.finished());
        if (!stats.byStatus().isEmpty()) {
            List<String> words = new ArrayList<>();
            stats.byStatus().forEach((status, n) -> words.add(n + " " + status));
            out.append(" (").append(esc(String.join(", ", words))).append(")");
        }
        out.append("</p>");
        out.append("<p class=\"muted\">").append(stats.cancelled()).append(" cancelled and ")
                .append(stats.incomplete()).append(" incomplete: excluded from every rate.</p>");
        if (stats.noVerdicts() > 0) {
            out.append("<p class=\"muted\">").append(stats.noVerdicts())
                    .append(" finished run(s) reached no test verdict, excluded from rates.</p>");
        }
        if (!stats.unmigratedExcluded().isEmpty()) {
            out.append("<p class=\"muted\">").append(stats.unmigratedExcluded().size())
                    .append(" unmigrated placeholder test(s) excluded from rates.</p>");
        }
        if ("fake_pos".equals(stats.segment())) {
            out.append("<p class=\"warn\"><strong>FAKE POS: evidence about this repository, not about a rig.</strong></p>");
        }
        if ("no rig".equals(stats.segment())) {
            out.append("<p class=\"muted\">Developer pytest sessions, no rig: passing tests are not indexed.</p>");
        }
        if (stats.passRates().isEmpty()) {
            out.append("<p class=\"muted\">No finished run in this segment reached a test verdict.</p>");
        } else {
            List<Double> rates = stats.passRates();
            out.append("<p>Pass rate, last ").append(rates.size())
                    .append(" run(s) with a verdict, oldest to newest: latest ")
                    .append(percent(rates.get(rates.size() - 1))).append(" · min ")
                    .append(percent(rates.stream().min(Double::compare).orElse(0.0))).append(" · max ")
                    .append(percent(rates.stream().max(Double::compare).orElse(0.0))).append("</p>");
            out.append(sparkline(rates));
        }
        out.append(flakes(stats.flakes()));
        out.append(slowest(stats.slowest()));
        out.append(steps(stats.stepMeans()));
        out.append(reasons(stats.reasons()));
        return out.append("</section>").toString();
    }

    private static String percent(double rate) {
        return String.format(Locale.ROOT, "%.0f%%", rate * 100);
    }

    /** The pass rate as an inline line chart, 0 to 100 per cent, oldest on the left. */
    static String sparkline(List<Double> rates) {
        int width = 600;
        int height = 160;
        int pad = 28;
        StringBuilder points = new StringBuilder();
        int n = rates.size();
        for (int i = 0; i < n; i++) {
            double x = n == 1 ? width / 2.0 : pad + (width - 2.0 * pad) * i / (n - 1);
            double y = height - pad - (height - 2.0 * pad) * rates.get(i);
            points.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return "<figure class=\"spark\"><svg viewBox=\"0 0 " + width + " " + height
                + "\" role=\"img\" aria-label=\"Pass rate per run, oldest to newest\">"
                + "<line x1=\"" + pad + "\" y1=\"" + (height - pad) + "\" x2=\"" + (width - pad) + "\" y2=\""
                + (height - pad) + "\" class=\"axis\"/>"
                + "<text x=\"2\" y=\"" + (pad + 4) + "\" class=\"tick\">100%</text>"
                + "<text x=\"2\" y=\"" + (height - pad + 4) + "\" class=\"tick\">0%</text>"
                + "<polyline points=\"" + points.toString().strip() + "\" class=\"line\"/>"
                + "</svg></figure>";
    }

    private static String flakes(List<RunStats.Flake> flakes) {
        if (flakes.isEmpty()) {
            return "<h3>Flaky</h3><p class=\"muted\">No test both passed and failed in the window.</p>";
        }
        StringBuilder out = new StringBuilder("<h3>Flaky</h3><p class=\"muted\">Both passed and failed inside the window.")
                .append(" “At the same commit” only where both runs recorded one.</p>")
                .append("<table class=\"results-table\"><thead><tr><th>Test</th><th class=\"num\">Pass</th>")
                .append("<th class=\"num\">Fail</th><th>Commit</th></tr></thead><tbody>");
        for (RunStats.Flake flake : flakes) {
            String commit = flake.sameCommit() == null ? "commit unknown"
                    : flake.sameCommit() ? "at the same commit" : "at different commits";
            out.append("<tr><td class=\"mono\">").append(esc(flake.nodeid())).append("</td>")
                    .append("<td class=\"num\">").append(flake.passes()).append("</td>")
                    .append("<td class=\"num\">").append(flake.fails()).append("</td>")
                    .append("<td>").append(commit).append("</td></tr>");
        }
        return out.append("</tbody></table>").toString();
    }

    private static String slowest(List<RunStats.Timing> timings) {
        if (timings.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h3>Slowest tests</h3><table class=\"results-table\"><thead><tr>")
                .append("<th>Test</th><th class=\"num\">Median</th><th class=\"num\">Runs</th></tr></thead><tbody>");
        for (RunStats.Timing timing : timings) {
            out.append("<tr><td class=\"mono\">").append(esc(timing.nodeid())).append("</td>")
                    .append("<td class=\"num\">").append(String.format(Locale.ROOT, "%.1fs", timing.median()))
                    .append("</td><td class=\"num\">").append(timing.count()).append("</td></tr>");
        }
        return out.append("</tbody></table>").toString();
    }

    private static String steps(List<RunStats.StepMean> means) {
        if (means.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h3>Steps</h3><dl>");
        for (RunStats.StepMean step : means) {
            out.append("<dt>").append(esc(step.name())).append("</dt><dd>mean ")
                    .append(esc(ResultsView.elapsed(step.mean()))).append(" over ").append(step.count())
                    .append("</dd>");
        }
        return out.append("</dl>").toString();
    }

    private static String reasons(List<RunStats.Reason> reasons) {
        if (reasons.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h3>Failure reasons</h3><dl>");
        for (RunStats.Reason reason : reasons) {
            out.append("<dt class=\"mono\">").append(esc(reason.excType())).append("</dt><dd>")
                    .append(reason.count()).append("</dd>");
        }
        return out.append("</dl>").toString();
    }
}
