package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;
import static com.verifone.cat.web.RunBoardHtml.badge;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.runservice.RunProgress;
import com.verifone.cat.runservice.RunSchedule;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunStart;
import com.verifone.cat.runservice.RunState;
import com.verifone.cat.runservice.RunView;

/**
 * The Run tab's Overview panel (docs/web-ui.md, "Run → Overview"; the Python CAT's {@code cat/web/pages/run.py}).
 *
 * <p>Before a run: the Pre-Run read-back of the Config tab's choices ({@code form.describe} and "What this run will
 * start"), Start run, Preflight (check only, a background job with Stop) and the schedule (Schedule run, Clear
 * schedule, Continuous run). During and after it: the counters (Run Time ticking each second), the progress bar,
 * Pause/Continue, Stop, Validations and New run, a failed step's card, then the board (the cases in plan order with
 * a filter and each case's detail, the current step's card that opens the step dialog, the Simulators and Connections
 * rails); after it, Failures with case labels, Not Run and Run Complete (Skipped and Total included).
 *
 * <p>The panel is one {@code <section id="run-panel">}. Before a run it polls itself only while a schedule, a
 * continuous run or a preflight is waiting. Once started, its parts poll themselves while the run goes: the head
 * every two seconds, the clock every second, the board and the post-run cards only when they would change
 * ({@link RunBoardHtml#polled}). Every action answers with the whole panel.
 */
final class RunOverviewPage {

    /** The id htmx swaps; every Run action targets it. */
    static final String PANEL_ID = "run-panel";

    /** How often a running panel refreshes itself. */
    static final String POLL = RunBoardHtml.POLL;

    /** The times the schedule takes, as the Python CAT's {@code SCHEDULE_HINT}. */
    static final String SCHEDULE_HINT = "Times: 23:00 · 23:00:30 · 2026-09-06 23:00 · +30m (relative). "
            + "Several at once, comma- or space-separated.";

    private static final String TARGET = "hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\"";

    private RunOverviewPage() {}

    /**
     * The panel.
     *
     * @param view     the service's read of the current run (not started, busy or ended)
     * @param plan     the Config tab's choices as Start run would launch them, with their read-back
     * @param schedule the schedule and the continuous run
     */
    static String panel(RunService.View view, RunStart.Plan plan, RunSchedule.View schedule) {
        if (!view.started()) {
            StringBuilder out = new StringBuilder();
            preRun(out, plan, view, schedule);
            boolean poll = schedule.active() || view.preflighting();
            return "<section id=\"" + PANEL_ID + "\" class=\"run-panel\""
                    + (poll ? " hx-get=\"/fragments/run\" hx-trigger=\"" + POLL + "\" hx-swap=\"outerHTML\"" : "")
                    + ">" + out + "</section>";
        }
        RunLinks links = RunLinks.live(view.busy());
        RunState state = view.state();
        return "<section id=\"" + PANEL_ID + "\" class=\"run-panel\" style=\"max-width: 90rem;\">"
                + head(view, schedule)
                + RunBoardHtml.board(state, links, "/fragments/run/cases", "/fragments/run/side")
                + post(view)
                + "</section>";
    }

    // ------------------------------------------------------------------ before a run

    private static void preRun(StringBuilder out, RunStart.Plan plan, RunService.View view,
            RunSchedule.View schedule) {
        notices(out, view);
        out.append("<div class=\"card run-card\"><h2>Pre-Run</h2>");
        out.append("<ul class=\"run-summary cat-mono\">");
        for (RunStart.Line line : plan.readBack()) {
            out.append("<li").append(line.warning() ? " class=\"warn\"" : "").append('>').append(esc(line.text()))
                    .append("</li>");
        }
        out.append("</ul>");
        boolean canTry = plan.startable() || plan.needsFetch();
        out.append("<div class=\"run-actions\">");
        out.append("<button type=\"button\" class=\"run-primary\" hx-post=\"/run/start\" ").append(TARGET)
                .append(canTry ? "" : " disabled").append(">Start run</button>");
        if (view.preflighting()) {
            out.append("<span class=\"muted\">Preflight is running…</span>")
                    .append("<button type=\"button\" class=\"run-danger\" hx-post=\"/run/stop\" ").append(TARGET)
                    .append(">Stop</button>");
        } else {
            out.append("<button type=\"button\" hx-post=\"/run/preflight\" ").append(TARGET)
                    .append(" title=\"Checks the rig and changes nothing\">Preflight (check only)</button>");
        }
        out.append("</div>");
        out.append("<p class=\"muted\">Preflight with repair drives the POS, so it waits for the rig owner's "
                + "go-ahead; use the check only.</p>");
        if (!view.preflight().isEmpty()) {
            out.append("<h3>Last preflight</h3><pre class=\"run-preflight\">")
                    .append(esc(view.preflight())).append("</pre>");
        }
        out.append("</div>");
        scheduleCard(out, schedule, true);
    }

    /**
     * The schedule block. Before a run it has the entry form, Clear schedule and Continuous run; during one it only
     * shows what is waiting, with Finish continuous and Cancel per run.
     */
    private static void scheduleCard(StringBuilder out, RunSchedule.View schedule, boolean preRun) {
        if (!preRun && !schedule.active()) {
            return;
        }
        out.append("<div class=\"card run-card run-schedule\"><h2>Schedule</h2>");
        if (preRun) {
            out.append("<form hx-post=\"/run/schedule\" ").append(TARGET).append(">")
                    .append("<label>Start at <input type=\"text\" name=\"at\" autocomplete=\"off\" ")
                    .append("placeholder=\"23:00, +2h\"></label> ")
                    .append("<button type=\"submit\">Schedule run</button></form>");
            out.append("<div class=\"run-actions\">")
                    .append("<button type=\"button\" hx-post=\"/run/schedule/clear\" ").append(TARGET)
                    .append(">Clear schedule</button>")
                    .append("<button type=\"button\" hx-post=\"/run/continuous\" ").append(TARGET).append('>')
                    .append(schedule.continuous() ? "Finish continuous" : "Continuous run").append("</button>")
                    .append("</div>");
            out.append("<p class=\"muted\">").append(esc(SCHEDULE_HINT)).append("</p>");
        } else if (schedule.continuous()) {
            out.append("<div class=\"run-actions\"><button type=\"button\" hx-post=\"/run/continuous\" ")
                    .append(TARGET).append(">Finish continuous</button></div>");
        }
        if (!schedule.status().isEmpty()) {
            out.append("<p class=\"run-notice\" role=\"status\">").append(esc(schedule.status())).append("</p>");
        }
        if (!schedule.pending().isEmpty()) {
            out.append("<ul class=\"run-summary\">");
            for (RunSchedule.PendingLine line : schedule.pending()) {
                out.append("<li><span class=\"cat-mono\">#").append(line.id()).append(' ')
                        .append(esc(line.at())).append("</span> <span class=\"muted\">(")
                        .append(clockText(line.secondsLeft())).append(")</span> ")
                        .append(esc(line.plans())).append(' ')
                        .append("<button type=\"button\" hx-post=\"/run/schedule/cancel\" hx-vals='{\"id\":\"")
                        .append(line.id()).append("\"}' ").append(TARGET).append(">Cancel</button></li>");
            }
            out.append("</ul>");
        }
        out.append("</div>");
    }

    // ------------------------------------------------------------------ during and after

    /** The head: notices, actions, counters, the progress bar, a failed step's card and the waiting schedule. */
    static String head(RunService.View view, RunSchedule.View schedule) {
        StringBuilder out = new StringBuilder();
        RunState state = view.state();
        out.append("<div id=\"run-head\" class=\"run-card\"");
        if (view.busy()) {
            out.append(" hx-get=\"/fragments/run/head\" hx-trigger=\"").append(POLL).append("\" hx-swap=\"outerHTML\"");
        }
        out.append('>');
        notices(out, view);
        out.append("<div class=\"run-actions\">");
        if (view.busy()) {
            out.append("<button type=\"button\" hx-post=\"/run/pause\" ").append(TARGET).append('>')
                    .append(view.paused() ? "Continue" : "Pause").append("</button>");
            out.append("<button type=\"button\" class=\"run-danger\" hx-post=\"/run/stop\" ").append(TARGET)
                    .append(">Stop</button>");
        }
        if (view.ended()) {
            out.append("<button type=\"button\" hx-post=\"/run/new\" ").append(TARGET).append(">New run</button>");
        }
        out.append("<button type=\"button\" ").append(RunBoardHtml.dialogGet("/fragments/run/validations"))
                .append(">Validations</button>");
        out.append("</div>");
        if (view.paused() && view.busy()) {
            out.append("<p class=\"warn\">Paused: the run waits before its next step or test case.</p>");
        }
        counters(out, view, state);
        out.append("<progress class=\"run-progress\" max=\"100\" value=\"")
                .append(String.format(Locale.ROOT, "%.0f", state.percentComplete()))
                .append("\" aria-label=\"Progress\"></progress>");
        stepFailures(out, state);
        scheduleCard(out, schedule, false);
        return out.append("</div>").toString();
    }

    private static void notices(StringBuilder out, RunService.View view) {
        for (String text : List.of(view.notice(), view.error(), view.readProblem())) {
            if (!text.isEmpty()) {
                out.append("<p class=\"run-notice\" role=\"status\">").append(esc(text)).append("</p>");
            }
        }
    }

    /** The Status counter: Starting before the run's first event, Paused, else the Python's {@code status_word}. */
    static String statusWord(RunService.View view, RunState state) {
        if (view.busy() && "not_started".equals(state.status())) {
            return "Starting";
        }
        if (view.paused() && "running".equals(state.status())) {
            return "Paused";
        }
        if (!view.busy() && !view.error().isEmpty() && state.outcome() == null) {
            return "Failed";
        }
        return RunView.statusWord(state);
    }

    private static void counters(StringBuilder out, RunService.View view, RunState state) {
        Map<String, Integer> counts = RunView.verdictCounts(state);
        out.append("<div class=\"run-counters\">");
        metric(out, "Status", esc(statusWord(view, state)), "");
        metric(out, "Run ID", esc(state.runId().isEmpty() ? "-" : state.runId()), "mono");
        metric(out, "Run Time", clock(view), "");
        String plans = RunView.plansLabel(state.testPlans(), state.suite(), state.planNames());
        metric(out, "Test Plan(s)", esc(plans.isEmpty() ? "-" : plans), "");
        metric(out, "Passed", String.valueOf(counts.get("passed")), "");
        metric(out, "Failed", String.valueOf(counts.get("failed")), "");
        metric(out, "Passed with Warnings", String.valueOf(counts.get("warned")), "");
        metric(out, "Not Run", String.valueOf(state.countNotRun()), "");
        metric(out, "Percent Completed", String.format(Locale.ROOT, "%.0f%%", state.percentComplete()), "");
        out.append("</div>");
    }

    /** The Run Time value; while the run goes it ticks each second from {@code /fragments/run/clock}. */
    static String clock(RunService.View view) {
        String text = esc(clockText(view.seconds()));
        if (view.busy() && "running".equals(view.state().status())) {
            return "<span id=\"run-clock\" hx-get=\"/fragments/run/clock\" hx-trigger=\"every 1s\""
                    + " hx-swap=\"outerHTML\">" + text + "</span>";
        }
        return "<span id=\"run-clock\">" + text + "</span>";
    }

    private static void metric(StringBuilder out, String label, String html, String extraClass) {
        out.append("<div class=\"run-metric\"><div class=\"run-metric-label\">").append(esc(label))
                .append("</div><div class=\"run-metric-value")
                .append(extraClass.isEmpty() ? "" : " " + extraClass).append("\">")
                .append(html).append("</div></div>");
    }

    /**
     * A step that failed: its name, then its detail one line per element. The Start Simulators failure is one
     * operator line per simulator.
     */
    private static void stepFailures(StringBuilder out, RunState state) {
        for (RunState.StepState step : state.steps()) {
            if (!"failed".equals(step.status()) || step.detail().isEmpty()) {
                continue;
            }
            out.append("<div class=\"card run-card\"><h2>").append(esc(step.name())).append(" failed</h2>");
            for (String line : step.detail().split("\n")) {
                out.append("<p class=\"warn\">").append(esc(line)).append("</p>");
            }
            out.append("</div>");
        }
    }

    /** The post-run cards, polled while the run goes so they appear when it ends. */
    static String post(RunService.View view) {
        return RunBoardHtml.polled("div", "run-post", "", "/fragments/run/post", postInner(view), view.busy());
    }

    static String postInner(RunService.View view) {
        if (!view.ended()) {
            return "";
        }
        return postCards(view.state(), new RunProgress.Snapshot(view.state()),
                "New run resets this page; the run stays in Results.");
    }

    /** Failures, Not Run and Run Complete for a finished run (shared with Results). */
    static String postCards(RunState state, RunProgress.Snapshot snap, String note) {
        StringBuilder out = new StringBuilder("<div class=\"run-post\">");
        out.append("<div class=\"card run-card\"><h2>Failures</h2>");
        List<RunProgress.FailureLine> failures = snap.failures();
        if (failures.isEmpty()) {
            out.append("<p class=\"muted\">None.</p>");
        } else {
            out.append("<div class=\"run-rows\">");
            for (RunProgress.FailureLine failure : failures) {
                out.append("<div class=\"run-row\">").append(badge(failure.outcome())).append("<div class=\"grow\"><div>")
                        .append(esc(failure.label())).append("</div>");
                if (!failure.detail().isEmpty()) {
                    out.append("<div class=\"sub\">").append(esc(failure.detail())).append("</div>");
                }
                out.append("</div></div>");
            }
            out.append("</div>");
            if (snap.failureCount() > failures.size()) {
                out.append("<p class=\"muted\">and ").append(snap.failureCount() - failures.size()).append(" more</p>");
            }
        }
        out.append("</div>");
        out.append("<div class=\"card run-card\"><h2>Not Run</h2>");
        List<String> keys = state.notRunCaseKeys();
        if (keys.isEmpty()) {
            out.append("<p class=\"muted\">None.</p>");
        } else {
            out.append("<ul class=\"run-summary\">");
            for (String key : keys) {
                out.append("<li>").append(esc(RunView.caseLine(key, state.cases().get(key).summary()))).append("</li>");
            }
            out.append("</ul>");
        }
        out.append("</div>");
        Map<String, Integer> counts = RunView.verdictCounts(state);
        out.append("<div class=\"card run-card\"><h2>Run Complete</h2><pre class=\"run-complete\">");
        out.append(line("Passed:", counts.get("passed"))).append(line("Failed:", counts.get("failed")))
                .append(line("Passed with Warnings:", counts.get("warned")))
                .append(line("Skipped:", counts.get("skipped"))).append(line("Not Run:", state.countNotRun()))
                .append(line("Total:", counts.get("total")));
        out.append("</pre><p class=\"muted\">").append(esc(note)).append("</p></div>");
        return out.append("</div>").toString();
    }

    private static String line(String label, int value) {
        return String.format(Locale.ROOT, "%-22s%d%n", label, value).replace("\r", "");
    }

    /** The run timer as {@code 0:04:09}: it keeps its seconds past an hour, as the Python CAT's {@code clock_text}. */
    static String clockText(double seconds) {
        return RunView.clockText(seconds);
    }
}
