package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

/**
 * Run, with its tabs Overview, Config and Log (docs/web-ui.md, "Pages"): the tab strip, the board's styles and
 * script ({@link RunStyle}) and the element the run's dialogs open in, around each tab's body.
 */
final class RunPage {

    /** A tab of Run, with its own route. */
    enum Tab {
        OVERVIEW("Overview", "/run",
                "Before a run: the Config read-back, Start run, Preflight, Schedule and Continuous run, "
                        + "and the pending list. During and after a run: the counters, progress, Pause, "
                        + "Stop, New run, the current step, the cases, simulators, connections and Validations.",
                "cat/web/pages/run.py"),
        CONFIG("Config", "/run/config",
                "Plans, Run scope, the test plan branch, what the ticked plans need, Downloads, "
                        + "Commander Connect and Execution. Saved to the same file the TUI uses.",
                "cat/web/pages/config.py"),
        LOG("Log", "/run/log",
                "The run's log, by source and level, with search and Copy. It follows the newest line "
                        + "until you scroll up.",
                "cat/web/pages/run.py");

        final String label;
        final String path;
        final String summary;
        final String module;

        Tab(String label, String path, String summary, String module) {
            this.label = label;
            this.path = path;
            this.summary = summary;
            this.module = module;
        }
    }

    private RunPage() {}

    static String body(Tab active) {
        return body(active, Placeholder.html(active.summary, active.module));
    }

    /** The Run page with {@code content} in place of the tab's body; the tab strip is the same. */
    static String body(Tab active, String content) {
        StringBuilder out = new StringBuilder("<nav class=\"tabs\" aria-label=\"Run\">");
        for (Tab t : Tab.values()) {
            out.append("<a href=\"").append(esc(t.path)).append('"')
                    .append(t == active ? " aria-current=\"page\"" : "")
                    .append('>').append(esc(t.label)).append("</a>");
        }
        out.append("</nav>");
        // The board's and the Log's styles and script, and the element the run's dialogs open in.
        out.append(RunStyle.assets()).append("<div id=\"").append(RunBoardHtml.DIALOG).append("\"></div>");
        return out.append(content).toString();
    }
}
