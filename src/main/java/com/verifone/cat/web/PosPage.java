package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.verifone.cat.tools.RegisterTarget;
import com.verifone.cat.web.ToolsService.Outcome;
import com.verifone.cat.web.ToolsService.Tool;

/**
 * The POS page: the register's screen, live, and clickable, as {@code edge://inspect}'s screencast shows a page.
 * Not in the Python CAT's web-ui.md; kept as a page of the shell, but unlike the others it is one big picture
 * ({@link Layout#screen}): no heading, no description, no card.
 *
 * <p>The body is a canvas filling the content area. {@code /static/pos-screen.js} opens {@link PosScreenRoutes#PATH}
 * (with the register's address) as soon as the page loads, draws each frame the register sends at the register's
 * aspect, and sends the operator's clicks, drags, wheel and keys back. A status line shows over the picture only while
 * it is not live (connecting, or the connection dropped); it reconnects by itself.
 *
 * <p>Before the picture, the page checks its registers ({@link PosRegister}: the rig's, else the lab's 101 and 102;
 * {@link PosReach}, user 2026-11-10). The first whose pos-shim answers is shown. Otherwise the panel ({@link #panel})
 * lists each: "Register not connected" when it does not answer, or a <b>Push shim to register</b> button (one register
 * provisioned, {@link Tool#PUSH_SHIM}) when it answers with its pos-shim port closed. The panel checks again every few
 * seconds, and the page reloads into the picture once a shim answers.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): see {@link com.verifone.cat.pos.inspect.PosScreencast}.
 */
final class PosPage {

    /** The panel's address, which it polls while no register is live. */
    static final String PANEL_PATH = "/fragments/pos";
    /** The Push shim to register button's address. */
    static final String PUSH_PATH = "/pos/push-shim";
    static final String PANEL_ID = "pos-panel";
    /** The log lines the panel shows under a running or failed push. */
    static final int LOG_TAIL = 15;

    /** One register, as the check found it. */
    record Probe(RegisterTarget target, PosReach.State state) {}

    private PosPage() {}

    /** The screen of {@code target}, through the socket for its address. */
    static String screen(RegisterTarget target) {
        return screen(target.cdpUrl(), PosScreenRoutes.PATH + "?host="
                + URLEncoder.encode(target.host(), StandardCharsets.UTF_8));
    }

    /** The screen of a fixed endpoint (tests' local Chromium), through the socket with no address. */
    static String screen(String cdpUrl) {
        return screen(cdpUrl, PosScreenRoutes.PATH);
    }

    private static String screen(String cdpUrl, String socket) {
        return "<div class=\"pos-screen\">"
                + "<canvas id=\"pos-screen\" tabindex=\"0\" data-socket=\"" + esc(socket) + "\""
                + " title=\"" + esc(cdpUrl) + "\""
                + " aria-label=\"The register's screen; clicks and keys go to the live POS\"></canvas>"
                + "<p id=\"pos-screen-status\" role=\"status\">Connecting to the register…</p>"
                + "</div>"
                + "<script src=\"/static/pos-screen.js\" defer></script>";
    }

    /** No register to check at all: {@code why} says why. Never "not connected": nothing was probed. */
    static String noRegister(String why) {
        return "<div id=\"" + PANEL_ID + "\" class=\"pos-screen pos-screen-none\"><p class=\"warn\" role=\"status\">"
                + "Can't check the register: CAT has none to check.</p><p class=\"muted\">" + esc(why) + "</p></div>";
    }

    /**
     * The panel shown while no register is live: each register's state, a push button on each that answers without a
     * shim, the push's last outcome and, while it runs or after it fails, the log. Polls {@link #PANEL_PATH}.
     */
    static String panel(List<Probe> probes, ToolsService tools) {
        StringBuilder out = new StringBuilder("<div id=\"" + PANEL_ID + "\" class=\"pos-screen pos-screen-none\""
                + " hx-get=\"" + PANEL_PATH + "\" hx-trigger=\"every 3s\" hx-swap=\"outerHTML\">");
        boolean pushing = tools.running() == Tool.PUSH_SHIM;
        String why = tools.pushRefusal();
        boolean anyPushable = probes.stream().anyMatch(p -> p.state() == PosReach.State.NO_SHIM);
        out.append("<ul class=\"tool-list\">");
        for (Probe probe : probes) {
            out.append(row(probe, tools, why, pushing));
        }
        out.append("</ul>");
        if (!why.isEmpty() && !pushing && anyPushable) {
            out.append("<p class=\"warn\">").append(esc(why)).append("</p>");
        }
        String notice = tools.notice();
        if (!notice.isEmpty() && !notice.equals(why)) {
            out.append("<p class=\"warn\">").append(esc(notice)).append("</p>");
        }
        if (!anyPushable && !pushing) {
            out.append("<p class=\"muted\">Checking again every few seconds.</p>");
        }
        Outcome outcome = tools.outcome(Tool.PUSH_SHIM);
        if (!outcome.state().isEmpty() && !"done".equals(outcome.state())) {
            List<String> lines = tools.lines();
            List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_TAIL), lines.size());
            if (!tail.isEmpty()) {
                out.append("<pre class=\"cat-pre\">").append(esc(String.join("\n", tail))).append("</pre>");
            }
        }
        return out.append("</div>").toString();
    }

    /** One register's line: what the check found, its button, and the push's outcome when it was this register's. */
    private static String row(Probe probe, ToolsService tools, String why, boolean pushing) {
        RegisterTarget target = probe.target();
        String where = esc(target.describe());
        StringBuilder out = new StringBuilder("<li id=\"pos-register-" + esc(target.id()) + "\">");
        switch (probe.state()) {
            case NOT_CONNECTED:
                out.append("<span class=\"warn\" role=\"status\">Register not connected: ").append(where)
                        .append(" does not answer.</span>");
                break;
            case LIVE:
                out.append("<span role=\"status\">The pos-shim on ").append(where)
                        .append(" answers; the screen opens once the push finishes.</span>");
                break;
            default:
                out.append("<span class=\"warn\" role=\"status\">").append(where)
                        .append(" answers, but its pos-shim (port ").append(target.shimPort())
                        .append(") is not open.</span> ");
                // The address rides along: a push lands only on the register this row showed.
                String path = PUSH_PATH + "?host=" + URLEncoder.encode(target.host(), StandardCharsets.UTF_8);
                out.append("<button type=\"button\" class=\"run-primary\" hx-post=\"").append(esc(path))
                        .append("\" hx-confirm=\"").append(esc(tools.confirmText(Tool.PUSH_SHIM, target.describe())))
                        .append("\" hx-target=\"#").append(PANEL_ID).append("\" hx-swap=\"outerHTML\"")
                        .append(why.isEmpty() && !pushing ? "" : " disabled").append(">")
                        .append(esc(Tool.PUSH_SHIM.title)).append("</button>");
        }
        Outcome outcome = tools.outcome(Tool.PUSH_SHIM);
        if (!outcome.state().isEmpty() && outcome.message().contains("(" + target.id() + ")")) {
            String badge = switch (outcome.state()) {
                case "running" -> Html.badge("run", "Running");
                case "done" -> Html.badge("ok", "Done");
                default -> Html.badge("error", "Failed");
            };
            out.append(" <span role=\"status\">").append(badge).append(" ").append(esc(outcome.message()))
                    .append("</span>");
        }
        return out.append("</li>").toString();
    }
}
