package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

/**
 * Run, with its tabs Overview, Config and Log (docs/web-ui.md, "Pages"): the tab strip, the board's styles and
 * script ({@link RunStyle}) and the element the run's dialogs open in, around each tab's body.
 */
final class RunPage {

    /** A tab of Run, with its own route. */
    enum Tab {
        OVERVIEW("Overview", "/run"),
        CONFIG("Config", "/run/config"),
        LOG("Log", "/run/log");

        final String label;
        final String path;

        Tab(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    private RunPage() {}

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
