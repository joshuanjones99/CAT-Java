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
 * as soon as the page loads, draws each frame the register sends at the register's aspect, and sends the operator's
 * clicks, drags, wheel and keys back. A status line shows over the picture only while it is not live (connecting,
 * or the connection dropped); it reconnects by itself.
 *
 * <p>Before the picture, the page checks the register ({@link PosReach}, user 2026-11-10): one that does not answer is
 * "Register not connected"; one that answers with its pos-shim port closed gets a <b>Push shim to register</b> button
 * (one register provisioned, {@link Tool#PUSH_SHIM}). That panel ({@link #panel}) checks again every few seconds, and
 * the page reloads into the picture once the shim answers. The register is the rig's, else the Settings page's
 * address ({@link PosRegister}): no rig file is needed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): see {@link com.verifone.cat.pos.inspect.PosScreencast}.
 */
final class PosPage {

    /** The panel's address, which it polls while the register is not live. */
    static final String PANEL_PATH = "/fragments/pos";
    /** The Push shim to register button's address. */
    static final String PUSH_PATH = "/pos/push-shim";
    static final String PANEL_ID = "pos-panel";
    /** The log lines the panel shows under a running or failed push. */
    static final int LOG_TAIL = 15;

    private PosPage() {}

    /**
     * The page body. {@code cdpUrl} is the register's DevTools endpoint, or null when CAT was started without a
     * register: the page then says so, with {@code subtitle} (which names why), and opens nothing.
     */
    static String body(String subtitle, String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            return noRegister(subtitle);
        }
        return "<div class=\"pos-screen\">"
                + "<canvas id=\"pos-screen\" tabindex=\"0\" data-socket=\"" + PosScreenRoutes.PATH + "\""
                + " title=\"" + esc(cdpUrl) + "\""
                + " aria-label=\"The register's screen; clicks and keys go to the live POS\"></canvas>"
                + "<p id=\"pos-screen-status\" role=\"status\">Connecting to the register…</p>"
                + "</div>"
                + "<script src=\"/static/pos-screen.js\" defer></script>";
    }

    /**
     * No register to check at all (no rig register and no address on Settings, or one that is not usable): {@code why}
     * says which. Never "not connected": nothing was probed, and the register may well be up (user, 2026-11-10).
     * Polls {@link #PANEL_PATH}, so an address saved on Settings in another tab takes effect here too.
     */
    static String noRegister(String why) {
        return "<div id=\"" + PANEL_ID + "\" class=\"pos-screen pos-screen-none\" hx-get=\"" + PANEL_PATH
                + "\" hx-trigger=\"every 5s\" hx-swap=\"outerHTML\"><p class=\"warn\" role=\"status\">Can't check "
                + "the register: CAT doesn't know its address.</p><p class=\"muted\">" + esc(why) + "</p>"
                + "<p><a href=\"" + Section.SETTINGS.path() + "#register\">Set the register's address on "
                + "Settings</a></p></div>";
    }

    /**
     * The panel shown while the register is not live: not connected, or up with its shim closed (the push button, its
     * last outcome and, while it runs or after it fails, the log). Polls {@link #PANEL_PATH}.
     */
    static String panel(RegisterTarget target, PosReach.State state, ToolsService tools) {
        StringBuilder out = new StringBuilder("<div id=\"" + PANEL_ID + "\" class=\"pos-screen pos-screen-none\""
                + " hx-get=\"" + PANEL_PATH + "\" hx-trigger=\"every 3s\" hx-swap=\"outerHTML\">");
        String where = esc(target.describe());
        Tool running = tools.running();
        boolean pushing = running == Tool.PUSH_SHIM;
        if (state == PosReach.State.NOT_CONNECTED && !pushing) {
            out.append("<p class=\"warn\" role=\"status\">Register not connected: ").append(where)
                    .append(" does not answer.</p><p class=\"muted\">Checking again every few seconds.</p>");
            return out.append("</div>").toString();
        }
        if (state == PosReach.State.LIVE) {
            out.append("<p role=\"status\">The pos-shim on ").append(where)
                    .append(" answers; the screen opens once the push finishes.</p>");
        } else if (state == PosReach.State.NOT_CONNECTED) {
            out.append("<p class=\"warn\" role=\"status\">Register not connected: ").append(where)
                    .append(" does not answer (it may be restarting during the push).</p>");
        } else {
            out.append("<p class=\"warn\" role=\"status\">").append(where).append(" answers, but its pos-shim (port ")
                    .append(target.shimPort()).append(") is not open.</p>");
        }
        String why = tools.pushRefusal();
        if (!why.isEmpty() && !pushing) {
            out.append("<p class=\"warn\">").append(esc(why)).append("</p>");
        }
        String notice = tools.notice();
        if (!notice.isEmpty() && !notice.equals(why)) {
            out.append("<p class=\"warn\">").append(esc(notice)).append("</p>");
        }
        // The address rides along, so a push never lands on a register other than the one this panel showed.
        String path = PUSH_PATH + "?host=" + URLEncoder.encode(target.host(), StandardCharsets.UTF_8);
        out.append("<p><button type=\"button\" class=\"run-primary\" hx-post=\"").append(esc(path))
                .append("\" hx-confirm=\"").append(esc(tools.confirmText(Tool.PUSH_SHIM, target.describe())))
                .append("\" hx-target=\"#").append(PANEL_ID).append("\" hx-swap=\"outerHTML\"")
                .append(why.isEmpty() ? "" : " disabled").append(">").append(esc(Tool.PUSH_SHIM.title))
                .append("</button></p>");
        Outcome outcome = tools.outcome(Tool.PUSH_SHIM);
        if (!outcome.state().isEmpty()) {
            String badge = switch (outcome.state()) {
                case "running" -> Html.badge("run", "Running");
                case "done" -> Html.badge("ok", "Done");
                default -> Html.badge("error", "Failed");
            };
            out.append("<p role=\"status\">").append(badge).append(" ").append(esc(outcome.message())).append("</p>");
            if (!"done".equals(outcome.state())) {
                List<String> lines = tools.lines();
                List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_TAIL), lines.size());
                if (!tail.isEmpty()) {
                    out.append("<pre class=\"cat-pre\">").append(esc(String.join("\n", tail))).append("</pre>");
                }
            }
        }
        return out.append("</div>").toString();
    }
}
