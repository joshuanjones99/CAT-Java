package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.io.IOException;
import java.util.List;

import com.verifone.cat.runservice.RunService;

/**
 * The Run tab's Log: the current run's {@code run.log}, the last few hundred lines (docs/web-ui.md,
 * "Run → Log"). The source and level filters and Copy are not built yet; the page is the tail.
 *
 * <p>The panel is one {@code <div id="run-log">}. It polls itself while the run is going, and stops
 * once it has ended, so a finished run's log is one read.
 */
final class RunLogPage {

    static final String PANEL_ID = "run-log";

    private RunLogPage() {}

    /** The tab's body: the tab strip is added by {@link RunPage#body(RunPage.Tab, String)}. */
    static String body(RunService run) {
        return RunPage.body(RunPage.Tab.LOG, panel(run));
    }

    /** The log panel: the newest lines, oldest first, escaped. */
    static String panel(RunService run) {
        RunService.View view = run.view();
        String polling = view.busy()
                ? " hx-get=\"/fragments/run/log\" hx-trigger=\"" + RunOverviewPage.POLL + "\" hx-swap=\"outerHTML\""
                : "";
        String head = view.started()
                ? "<p class=\"muted\">The newest lines of this run's run.log, the last "
                        + "few hundred. Reloads while the run is going.</p>"
                : "";
        List<String> lines;
        String problem = "";
        try {
            lines = run.logLines();
        } catch (IOException e) {
            lines = List.of();
            problem = "The log could not be read: " + e.getMessage();
        }
        StringBuilder body = new StringBuilder();
        if (!problem.isEmpty()) {
            body.append("<p class=\"warn\">").append(esc(problem)).append("</p>");
        }
        if (lines.isEmpty()) {
            body.append("<p class=\"muted\">").append(view.started()
                    ? "The run has not written its log yet."
                    : "No run yet: Start run on the Overview.").append("</p>");
        } else {
            body.append("<pre class=\"run-log\">");
            for (String line : lines) {
                body.append(esc(line)).append('\n');
            }
            body.append("</pre>");
        }
        return "<div id=\"" + PANEL_ID + "\" class=\"run-log-panel\"" + polling + ">" + head + body + "</div>";
    }
}
