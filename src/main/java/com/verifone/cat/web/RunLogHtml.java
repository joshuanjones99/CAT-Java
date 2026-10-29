package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.verifone.cat.runservice.LogSources;
import com.verifone.cat.runservice.RunState;

import io.javalin.http.Context;

/**
 * A drawn run's Log: Source (All, a step, a simulator), the levels, a search, Copy, and following. The Python CAT's
 * {@code cat.web.components.LogPanel} over {@code cat.panels.log}.
 *
 * <p>The newest {@link LogSources#SHOWN} lines that the filters keep are drawn, each {@code HH:MM:SS L source:
 * message} in its level's colour; Copy takes every one of them, from the server. On a live run the body polls while
 * it is following; when the reader scrolls up it stops, so the lines on screen stay put, and a counter says how many
 * arrived since ("N new"); scrolled back to the bottom, or Back to the newest, it follows again ({@link RunStyle}).
 * A step's log is reached as {@code ?step=<name>} and a simulator's as {@code ?source=sim:<name>}.
 *
 * <p>The lines come from the run's {@code log.line} events, bounded per source as the Python's buffers are.
 */
final class RunLogHtml {

    private RunLogHtml() {}

    /** The filters, as a request names them. */
    record Filters(String source, Set<String> levels, String search) {

        /** All levels on, all sources, no search. */
        static Filters all() {
            return new Filters(LogSources.SOURCE_ALL, new LinkedHashSet<>(LogSources.LEVELS), "");
        }

        /**
         * From a request: {@code source} (or {@code step}, a step's name), {@code level} (any number; every level
         * when the form did not say, i.e. no {@code lv} marker) and {@code q}.
         */
        static Filters from(Context ctx) {
            String source = ctx.queryParam("source");
            String step = ctx.queryParam("step");
            if ((source == null || source.isEmpty()) && step != null && !step.isEmpty()) {
                source = LogSources.SOURCE_STEP + step;
            }
            Set<String> levels = new LinkedHashSet<>();
            if (ctx.queryParam("lv") == null) {
                levels.addAll(LogSources.LEVELS);
            } else {
                for (String level : ctx.queryParams("level")) {
                    if (LogSources.LEVELS.contains(level)) {
                        levels.add(level);
                    }
                }
            }
            String q = ctx.queryParam("q");
            return new Filters(source == null ? "" : source, levels, q == null ? "" : q.strip());
        }

        /** The filters as a query string (no leading {@code ?}). */
        String query() {
            StringBuilder out = new StringBuilder("lv=1&source=").append(RunLinks.enc(source));
            for (String level : levels) {
                out.append("&level=").append(RunLinks.enc(level));
            }
            if (!search.isEmpty()) {
                out.append("&q=").append(RunLinks.enc(search));
            }
            return out.toString();
        }
    }

    /** The whole panel: the filter bar, the note, the body and the counter. */
    static String panel(RunState state, Filters filters, RunLinks links) {
        StringBuilder out = new StringBuilder("<div id=\"run-log\" class=\"run-log-panel\">");
        out.append("<form id=\"log-filter\" class=\"run-log-bar\" hx-get=\"").append(esc(links.logBody()))
                .append("\" hx-target=\"#log-body\" hx-swap=\"outerHTML\" ")
                .append("hx-trigger=\"change, input delay:300ms from:#log-search, submit\" onsubmit=\"return false\">")
                .append("<input type=\"hidden\" name=\"lv\" value=\"1\">");
        out.append(sourceSelect(state, filters.source()));
        out.append("<fieldset><legend class=\"muted\">Levels</legend>");
        for (String level : LogSources.LEVELS) {
            out.append("<label><input type=\"checkbox\" name=\"level\" value=\"").append(level).append('"')
                    .append(filters.levels().contains(level) ? " checked" : "").append("> ").append(level)
                    .append("</label>");
        }
        out.append("</fieldset>");
        out.append("<input type=\"search\" id=\"log-search\" name=\"q\" placeholder=\"Search logs…\" value=\"")
                .append(esc(filters.search())).append("\" aria-label=\"Search the log\">");
        out.append("<button type=\"button\" onclick=\"catLog.copy('").append(esc(links.logText()))
                .append("')\">Copy</button> <span id=\"log-copied\" class=\"muted\" role=\"status\"></span>");
        out.append("</form>");
        out.append("<div class=\"run-actions\"><span id=\"log-count\" class=\"muted\" role=\"status\"");
        if (links.live()) {
            out.append(" hx-get=\"").append(esc(links.logCount())).append("\" hx-include=\"#log-filter\"")
                    .append(" hx-trigger=\"every 2s [!catLog.following], refresh\" hx-swap=\"innerHTML\"");
        }
        out.append("></span> <button type=\"button\" id=\"log-newest\" hidden onclick=\"catLog.resume()\">")
                .append("Back to the newest</button></div>");
        out.append(body(state, filters, links));
        return out.append("</div>").toString();
    }

    /** The source selector: All, every step, then the run's simulators. */
    static String sourceSelect(RunState state, String selected) {
        List<String> sims = new ArrayList<>();
        for (RunState.SimulatorState sim : state.simulators()) {
            sims.add(sim.name());
        }
        boolean known = false;
        StringBuilder options = new StringBuilder();
        for (LogSources.Option option : LogSources.options(sims)) {
            boolean on = option.value().equals(selected);
            known |= on;
            options.append("<option value=\"").append(esc(option.value())).append('"').append(on ? " selected" : "")
                    .append('>').append(esc(option.label())).append("</option>");
        }
        if (!known && !selected.isEmpty()) {
            // A simulator the run does not know yet (a deep link): offered, so the view is what was asked for.
            String label = selected.startsWith(LogSources.SOURCE_SIM) ? selected.substring(LogSources.SOURCE_SIM.length())
                    : selected;
            options.append("<option value=\"").append(esc(selected)).append("\" selected>").append(esc(label))
                    .append("</option>");
        }
        return "<label>Source <select id=\"log-source\" name=\"source\">" + options + "</select></label>";
    }

    /** The lines the filters keep, the newest {@link LogSources#SHOWN}; polls while following on a live run. */
    static String body(RunState state, Filters filters, RunLinks links) {
        List<RunState.LogLine> lines = LogSources.filter(state, filters.source(), filters.levels(), filters.search());
        List<RunState.LogLine> shown = lines.subList(Math.max(0, lines.size() - LogSources.SHOWN), lines.size());
        StringBuilder inner = new StringBuilder("<p class=\"muted\">").append(lines.size()).append(" line(s)");
        if (shown.size() < lines.size()) {
            inner.append("; the newest ").append(shown.size()).append(" shown, Copy takes all");
        }
        inner.append("</p><pre id=\"log-pre\" class=\"run-log\">");
        if (lines.isEmpty()) {
            inner.append("<span class=\"lv-DEBUG\">").append(state.logLines().isEmpty()
                    ? "No log lines yet." : "No line matches these filters.").append("</span>");
        }
        for (RunState.LogLine line : shown) {
            inner.append("<span class=\"lv-").append(esc(line.level())).append("\">")
                    .append(esc(LogSources.format(line))).append("</span>\n");
        }
        inner.append("</pre>");
        String html = inner.toString();
        StringBuilder out = new StringBuilder("<div id=\"log-body\" data-count=\"").append(lines.size()).append('"');
        String url = links.logBody() + "?" + filters.query();
        if (links.live()) {
            out.append(" hx-get=\"").append(esc(url + "&h=" + RunBoardHtml.hash(html)))
                    .append("\" hx-trigger=\"every 2s [catLog.following], refresh\" hx-swap=\"outerHTML\"");
        } else {
            out.append(" hx-get=\"").append(esc(url)).append("\" hx-trigger=\"refresh\" hx-swap=\"outerHTML\"");
        }
        return out.append('>').append(html).append("</div>").toString();
    }

    /** The hash a body poll carries, for its {@code 204} when nothing changed. */
    static String bodyHash(RunState state, Filters filters, RunLinks links) {
        String full = body(state, filters, links);
        int start = full.indexOf('>') + 1;
        return RunBoardHtml.hash(full.substring(start, full.length() - "</div>".length()));
    }

    /** "Not following: scrolled up. N new line(s) since; ...", for the lines past {@code since}. */
    static String count(RunState state, Filters filters, int since) {
        int now = LogSources.filter(state, filters.source(), filters.levels(), filters.search()).size();
        int arrived = Math.max(0, now - since);
        return "Not following: scrolled up. " + arrived
                + " new line(s) since; scroll to the bottom to follow again.";
    }

    /** Every line the filters keep, formatted, one per line (Copy). */
    static String text(RunState state, Filters filters) {
        List<String> out = new ArrayList<>();
        for (RunState.LogLine line : LogSources.filter(state, filters.source(), filters.levels(), filters.search())) {
            out.add(LogSources.format(line));
        }
        return String.join("\n", out);
    }
}
