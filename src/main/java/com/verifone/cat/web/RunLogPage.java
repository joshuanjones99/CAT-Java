package com.verifone.cat.web;

import com.verifone.cat.runservice.RunService;
import com.verifone.cat.runservice.RunState;

/**
 * The Run tab's Log (docs/web-ui.md, "Run → Log"): the current run's log by Source (All, a step, a simulator),
 * level and search, with Copy, following and its "N new" counter ({@link RunLogHtml}). {@code ?step=<name>} opens on
 * a step's lines, {@code ?source=sim:<name>} on a simulator's.
 *
 * <p>The lines are the run's {@code log.line} events, as the Python's Log tab reads them; {@code run.log} (the
 * run's summary file) stays in the run's folder.
 */
final class RunLogPage {

    static final String PANEL_ID = "run-log";

    private RunLogPage() {}

    /** The tab's body: the tab strip is added by {@link RunPage#body(RunPage.Tab, String)}. */
    static String body(RunService run, RunLogHtml.Filters filters) {
        return RunPage.body(RunPage.Tab.LOG, panel(run, filters));
    }

    /** The log panel. */
    static String panel(RunService run, RunLogHtml.Filters filters) {
        RunService.View view = run.view();
        RunState state = view.state();
        String head = view.started() ? ""
                : "<p class=\"muted\">No run yet: Start run on the Overview. The Log fills as a run writes it.</p>";
        return head + RunLogHtml.panel(state, filters, RunLinks.live(view.busy()));
    }
}
