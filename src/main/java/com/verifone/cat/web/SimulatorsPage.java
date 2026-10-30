package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.verifone.cat.sims.SimEntry;

/**
 * Launch Simulators, ported from the Python CAT's {@code cat/web/pages/simulators.py}.
 *
 * <p>The page is one polled panel: each row's tick, status and detail, then Start (or the red
 * Stop, once every ticked row is up), Select all and Select none. Every action returns the panel,
 * so the server holds the ticks and a poll never loses them. The Log is a second panel, for the
 * one simulator the operator picked.
 *
 * <p>The red Stop asks first, and its question names the scope: it closes simulators "whoever
 * started them" (HANDOFF.md §1.116).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class SimulatorsPage {

    /** The question the red Stop asks. Names the scope, as the Python's docs do. */
    static final String STOP_QUESTION =
            "Stop every simulator, whoever started them? This closes simulators CAT did not start too.";

    private SimulatorsPage() {}

    /** The page body: the intro, the panel, and the Log placeholder. */
    static String body(SimulatorsService sims) {
        return "<p class=\"muted\">Start the simulators a run needs and leave them running: every later run "
                + "adopts them instead of starting its own. Stop closes every simulator, whoever started it.</p>"
                + panel(sims)
                + "<section id=\"sim-log\" class=\"card wide\"><p class=\"muted\">"
                + "Choose Log on a simulator to follow its output, or "
                + "<button type=\"button\" class=\"ghost\" hx-get=\"/fragments/simulators/log?name="
                + esc(encode(SimulatorsService.LAUNCH_LOG)) + "\" hx-target=\"#sim-log\" hx-swap=\"outerHTML\">"
                + "the launch's own lines</button>.</p></section>";
    }

    /** The polled panel: rows, Start or Stop, and the last message. Swapped whole. */
    static String panel(SimulatorsService sims) {
        SimulatorsService.Busy busy = sims.busy();
        boolean idle = busy == SimulatorsService.Busy.IDLE;
        String disabled = idle ? "" : " disabled";
        StringBuilder out = new StringBuilder("<section id=\"sim-panel\" class=\"card wide\" "
                + "hx-get=\"/fragments/simulators\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\">");
        String notice = sims.probeNotice();
        if (!notice.isEmpty()) {
            out.append("<p class=\"warn\">").append(esc(notice)).append("</p>");
        }
        out.append("<table class=\"sim-table\"><thead><tr>"
                + "<th>Launch</th><th>Simulator</th><th>Status</th><th>Detail</th><th>Log</th>"
                + "</tr></thead><tbody>");
        for (SimEntry entry : sims.rows()) {
            SimulatorsService.RowView view = sims.view(entry);
            String enc = encode(entry.name());
            out.append("<tr>");
            out.append("<td><input type=\"checkbox\" aria-label=\"Launch ").append(esc(entry.name())).append('"')
                    .append(sims.isTicked(entry.name()) ? " checked" : "")
                    .append(disabled)
                    .append(" hx-post=\"/simulators/toggle?name=").append(esc(enc)).append('"')
                    .append(" hx-target=\"#sim-panel\" hx-swap=\"outerHTML\"></td>");
            out.append("<td>").append(esc(entry.name()))
                    .append(sims.isFepOnly(entry.name()) ? " <span class=\"muted\">(FEP simulator)</span>" : "")
                    .append("</td>");
            out.append("<td><span class=\"").append(statusClass(view)).append("\">")
                    .append(esc(view.state())).append("</span>")
                    .append(view.owner().isEmpty() ? "" : " <span class=\"muted\">(" + esc(view.owner()) + ")</span>")
                    .append("</td>");
            out.append("<td class=\"muted\">").append(esc(view.detail())).append("</td>");
            out.append("<td><button type=\"button\" class=\"ghost\" hx-get=\"/fragments/simulators/log?name=")
                    .append(esc(enc)).append("\" hx-target=\"#sim-log\" hx-swap=\"outerHTML\">Log</button></td>");
            out.append("</tr>");
        }
        for (String name : sims.connectors()) {
            SimulatorsService.RowView view = sims.connectorView(name);
            out.append("<tr><td><input type=\"checkbox\" aria-label=\"Launch ").append(esc(name)).append('"')
                    .append(sims.isTicked(name) ? " checked" : "").append(disabled)
                    .append(" hx-post=\"/simulators/toggle?name=").append(esc(encode(name))).append('"')
                    .append(" hx-target=\"#sim-panel\" hx-swap=\"outerHTML\"></td>");
            out.append("<td>").append(esc(name)).append(" <span class=\"muted\">(connector)</span></td>");
            out.append("<td><span class=\"").append(statusClass(view)).append("\">").append(esc(view.state()))
                    .append("</span></td><td class=\"muted\">").append(esc(view.detail())).append("</td><td></td></tr>");
        }
        out.append("</tbody></table>");

        out.append("<div class=\"sim-actions\">");
        String other = sims.otherBusy();
        if (busy == SimulatorsService.Busy.STARTING) {
            out.append("<span class=\"muted\">Launching…</span>");
            out.append("<button type=\"button\" class=\"danger\" hx-post=\"/simulators/cancel\""
                    + " hx-target=\"#sim-panel\" hx-swap=\"outerHTML\">Cancel launch</button>");
        } else if (busy == SimulatorsService.Busy.STOPPING) {
            out.append("<span class=\"muted\">Stopping…</span>");
        } else if (!other.isEmpty()) {
            out.append("<span class=\"muted\">").append(esc(other.substring(0, 1).toUpperCase() + other.substring(1)))
                    .append(" is in progress: Start and Stop wait for it.</span>");
        }
        if (sims.allTickedUp()) {
            out.append("<button type=\"button\" class=\"danger\" hx-post=\"/simulators/stop\""
                    + " hx-confirm=\"").append(esc(STOP_QUESTION)).append('"')
                    .append(" hx-target=\"#sim-panel\" hx-swap=\"outerHTML\"")
                    .append(disabled).append(">Stop</button>");
        } else {
            out.append("<button type=\"button\" hx-post=\"/simulators/start\""
                    + " hx-target=\"#sim-panel\" hx-swap=\"outerHTML\"")
                    .append(disabled).append(">Start</button>");
        }
        out.append("<button type=\"button\" class=\"ghost\" hx-post=\"/simulators/select?mode=all\""
                + " hx-target=\"#sim-panel\" hx-swap=\"outerHTML\"").append(disabled).append(">Select all</button>");
        out.append("<button type=\"button\" class=\"ghost\" hx-post=\"/simulators/select?mode=none\""
                + " hx-target=\"#sim-panel\" hx-swap=\"outerHTML\"").append(disabled).append(">Select none</button>");
        out.append("</div>");

        String message = sims.message();
        if (!message.isEmpty()) {
            out.append("<p class=\"muted\" role=\"status\">").append(esc(message)).append("</p>");
        }
        return out.append("</section>").toString();
    }

    /** The Log panel for one simulator: its bounded tail, polled while it is shown. */
    static String log(SimulatorsService sims, SimEntry entry) {
        return log(sims, entry.name());
    }

    /** The Log panel for one simulator, or for the launch itself ({@link SimulatorsService#LAUNCH_LOG}). */
    static String log(SimulatorsService sims, String name) {
        String enc = encode(name);
        List<String> lines = sims.logLines(name);
        SimEntryName entry = new SimEntryName(name);
        StringBuilder out = new StringBuilder("<section id=\"sim-log\" class=\"card wide\" "
                + "hx-get=\"/fragments/simulators/log?name=").append(esc(enc))
                .append("\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\">");
        out.append("<h2>").append(esc(entry.name())).append(" log</h2>");
        if (lines.isEmpty()) {
            out.append("<p class=\"muted\">No output yet.</p>");
        } else {
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(esc(line)).append('\n');
            }
            out.append("<pre class=\"sim-log-pre\">").append(text).append("</pre>");
        }
        return out.append("</section>").toString();
    }

    /** Just the name, for the log's heading. */
    private record SimEntryName(String name) {}

    private static String statusClass(SimulatorsService.RowView view) {
        if (view.up()) {
            return "ok";
        }
        return view.attention() ? "warn" : "muted";
    }

    private static String encode(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8);
    }
}
