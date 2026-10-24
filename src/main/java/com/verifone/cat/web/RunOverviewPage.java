package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.List;
import java.util.Locale;

import com.verifone.cat.runconfig.ConfigView;
import com.verifone.cat.runservice.RunProgress;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunStart;

/**
 * The Run tab's Overview panel: before a run, the read-back of the Config tab with Start run and
 * Preflight; during and after it, the counters, progress, the current step, Pause/Continue, Stop
 * and New run (docs/web-ui.md, "Run → Overview").
 *
 * <p>The panel is one {@code <section id="run-panel">}. Every action answers with the whole panel,
 * so the page is one server reading. While a run is going the panel polls itself with htmx; once
 * it has ended the poll stops, so an idle page makes no requests.
 *
 * <p>Not built yet, and drawn as disabled placeholders: Schedule run, Continuous run, the cases,
 * Simulators, Connections and Validations.
 */
final class RunOverviewPage {

    /** The id htmx swaps; every Run action targets it. */
    static final String PANEL_ID = "run-panel";

    /** How often a running panel refreshes itself. */
    static final String POLL = "every 2s";

    private static final String TARGET = "hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\"";

    private RunOverviewPage() {}

    /**
     * The panel.
     *
     * @param view the service's read of the current run (not started, busy or ended)
     * @param config the Config tab's read-back, drawn before a run starts
     * @param plan the Config tab's choices as Start run would launch them
     */
    static String panel(RunService.View view, ConfigView config, RunStart.Plan plan) {
        StringBuilder out = new StringBuilder();
        if (view.started()) {
            started(out, view);
        } else {
            preRun(out, config, plan, view);
        }
        return "<section id=\"" + PANEL_ID + "\" class=\"run-panel\"" + (view.busy() ? polling() : "") + ">"
                + out + "</section>";
    }

    private static String polling() {
        return " hx-get=\"/fragments/run\" hx-trigger=\"" + POLL + "\" hx-swap=\"outerHTML\"";
    }

    // ------------------------------------------------------------------ before a run

    private static void preRun(StringBuilder out, ConfigView config, RunStart.Plan plan, RunService.View view) {
        notices(out, view);
        out.append("<div class=\"card run-card\"><h2>Pre-Run</h2>");
        out.append("<ul class=\"run-summary\">");
        for (String line : config.summary()) {
            out.append("<li>").append(esc(line)).append("</li>");
        }
        for (String line : config.caseLines()) {
            out.append("<li class=\"muted\">").append(esc(line)).append("</li>");
        }
        out.append("</ul>");
        if (!plan.startable()) {
            out.append("<p class=\"warn\">").append(esc(plan.refusal())).append("</p>");
        }
        out.append("<div class=\"run-actions\">");
        out.append("<button type=\"button\" class=\"run-primary\" hx-post=\"/run/start\" ").append(TARGET)
                .append(plan.startable() ? "" : " disabled")
                .append(">Start run</button>");
        out.append("<button type=\"button\" hx-post=\"/run/preflight\" ").append(TARGET)
                .append(" title=\"Checks the rig and changes nothing\">Preflight (check only)</button>");
        out.append("</div>");
        if (!view.preflight().isEmpty()) {
            out.append("<h3>Last preflight</h3><pre class=\"run-preflight\">")
                    .append(esc(view.preflight())).append("</pre>");
        }
        out.append("</div>");
        placeholders(out, "Schedule run and Continuous run",
                "the schedule and Continuous run are not ported to CAT-Java yet (Python: cat/web/service.py)");
    }

    // ------------------------------------------------------------------ during and after

    private static void started(StringBuilder out, RunService.View view) {
        RunProgress.Snapshot snap = view.snapshot();
        notices(out, view);
        out.append("<div class=\"run-actions\">");
        if (view.busy()) {
            out.append("<button type=\"button\" hx-post=\"/run/pause\" ").append(TARGET).append('>')
                    .append(view.paused() ? "Continue" : "Pause").append("</button>");
            out.append("<button type=\"button\" class=\"run-danger\" hx-post=\"/run/stop\" ").append(TARGET)
                    .append(">Stop</button>");
        }
        if (view.ended()) {
            out.append("<button type=\"button\" hx-post=\"/run/new\" ").append(TARGET)
                    .append(">New run</button>");
        }
        out.append("</div>");
        if (view.paused() && view.busy()) {
            out.append("<p class=\"warn\">Paused: the run waits before its next step.</p>");
        }

        counters(out, view, snap);
        double percent = snap == null ? 0 : snap.percent();
        out.append("<progress class=\"run-progress\" max=\"100\" value=\"")
                .append(String.format(Locale.ROOT, "%.0f", percent)).append("\" aria-label=\"Progress\"></progress>");

        if (snap != null) {
            currentStep(out, snap);
            stepFailures(out, snap);
            steps(out, snap);
            if (snap.finished()) {
                complete(out, snap);
            }
            failures(out, snap);
        }
        placeholders(out, "Cases, Simulators, Connections and Validations",
                "the cases in plan order, the simulator and connection panels and Validations are not built "
                        + "in CAT-Java yet (Python: cat/web/pages/run.py, cat/web/components.py)");
    }

    private static void notices(StringBuilder out, RunService.View view) {
        for (String text : List.of(view.notice(), view.error(), view.readProblem())) {
            if (!text.isEmpty()) {
                out.append("<p class=\"run-notice\" role=\"status\">").append(esc(text)).append("</p>");
            }
        }
    }

    private static void counters(StringBuilder out, RunService.View view, RunProgress.Snapshot snap) {
        out.append("<div class=\"run-counters\">");
        metric(out, "Status", view.status());
        metric(out, "Run ID", snap == null || snap.runId().isEmpty() ? "-" : snap.runId(), "mono");
        metric(out, "Run Time", clockText(view.seconds()));
        metric(out, "Test Plan(s)", snap == null || snap.testPlans().isEmpty()
                ? "-" : String.join(", ", snap.testPlans()));
        metric(out, "Passed", snap == null ? 0 : snap.passed());
        metric(out, "Failed", snap == null ? 0 : snap.failed());
        metric(out, "Passed with Warnings", snap == null ? 0 : snap.warned());
        metric(out, "Not Run", snap == null ? 0 : snap.notRun());
        metric(out, "Percent Completed", snap == null ? "0%" : String.format(Locale.ROOT, "%.0f%%", snap.percent()));
        out.append("</div>");
    }

    private static void metric(StringBuilder out, String label, Object value) {
        metric(out, label, value, "");
    }

    private static void metric(StringBuilder out, String label, Object value, String extraClass) {
        out.append("<div class=\"run-metric\"><div class=\"run-metric-label\">").append(esc(label))
                .append("</div><div class=\"run-metric-value")
                .append(extraClass.isEmpty() ? "" : " " + extraClass).append("\">")
                .append(esc(value)).append("</div></div>");
    }

    private static void currentStep(StringBuilder out, RunProgress.Snapshot snap) {
        out.append("<h3>Current step</h3>");
        if (snap.currentStep().isEmpty()) {
            out.append("<p class=\"muted\">").append(snap.finished() ? "The run has finished." : "None yet.")
                    .append("</p>");
            return;
        }
        out.append("<p>").append(esc(snap.currentStep())).append("</p>");
    }

    private static void steps(StringBuilder out, RunProgress.Snapshot snap) {
        out.append("<ol class=\"run-steps\">");
        for (RunProgress.StepLine step : snap.steps()) {
            out.append("<li><span class=\"run-step-name\">").append(esc(step.name())).append("</span> ")
                    .append("<span class=\"badge muted\">").append(esc(step.state())).append("</span>");
            if (!step.detail().isEmpty()) {
                out.append(" <span class=\"muted\">").append(esc(step.detail())).append("</span>");
            }
            out.append("</li>");
        }
        out.append("</ol>");
    }

    private static void complete(StringBuilder out, RunProgress.Snapshot snap) {
        out.append("<div class=\"card run-card\"><h2>Run Complete</h2><dl class=\"run-summary\">");
        row(out, "Passed", snap.passed());
        row(out, "Failed", snap.failed());
        row(out, "Passed with Warnings", snap.warned());
        row(out, "Not Run", snap.notRun());
        out.append("</dl><p class=\"muted\">New run resets this page; the run stays in Results.</p></div>");
    }

    private static void row(StringBuilder out, String label, int value) {
        out.append("<div><dt>").append(esc(label)).append("</dt><dd>").append(value).append("</dd></div>");
    }

    /**
     * A step that failed, above the step list: its name, then its detail one line per element. The
     * Start Simulators failure is one operator line per simulator (SimStatus.awaitingButtons).
     */
    private static void stepFailures(StringBuilder out, RunProgress.Snapshot snap) {
        for (RunProgress.StepLine step : snap.steps()) {
            if (!"failed".equals(step.state()) || step.detail().isEmpty()) {
                continue;
            }
            out.append("<div class=\"card run-card\"><h2>").append(esc(step.name())).append(" failed</h2>");
            for (String line : step.detail().split("\n")) {
                out.append("<p class=\"warn\">").append(esc(line)).append("</p>");
            }
            out.append("</div>");
        }
    }

    private static void failures(StringBuilder out, RunProgress.Snapshot snap) {
        out.append("<div class=\"card run-card\"><h2>Failures</h2>");
        if (snap.failures().isEmpty()) {
            out.append("<p class=\"muted\">None.</p></div>");
            return;
        }
        out.append("<ul class=\"run-summary\">");
        for (RunProgress.FailureLine failure : snap.failures()) {
            out.append("<li><span class=\"badge error\">").append(esc(failure.outcome())).append("</span> ")
                    .append(esc(failure.nodeid()));
            if (!failure.detail().isEmpty()) {
                out.append(" <span class=\"muted\">").append(esc(failure.detail())).append("</span>");
            }
            out.append("</li>");
        }
        out.append("</ul>");
        if (snap.failureCount() > snap.failures().size()) {
            out.append("<p class=\"muted\">and ").append(snap.failureCount() - snap.failures().size()).append(" more</p>");
        }
        out.append("</div>");
    }

    // ------------------------------------------------------------------ placeholders

    private static void placeholders(StringBuilder out, String label, String why) {
        out.append("<div class=\"placeholder run-placeholder\" aria-disabled=\"true\">")
                .append("<h3>Not built yet: ").append(esc(label)).append("</h3>")
                .append("<p class=\"placeholder-note\">").append(esc(why)).append("</p></div>");
    }

    /** The run timer as {@code 0:04:09}: it keeps its seconds past an hour, as the Python CAT's {@code clock_text}. */
    static String clockText(double seconds) {
        long whole = (long) seconds;
        long hours = whole / 3600;
        long minutes = (whole % 3600) / 60;
        long secs = whole % 60;
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs);
    }
}
