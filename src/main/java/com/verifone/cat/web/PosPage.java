package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

/**
 * The POS page: the register's screen, live, and clickable, as {@code edge://inspect}'s screencast shows a page.
 * Not in the Python CAT's web-ui.md; kept as a page of the shell.
 *
 * <p>The body is a canvas. {@code /static/pos-screen.js} opens {@link PosScreenRoutes#PATH} as soon as the page
 * loads, draws each frame the register sends, and sends the operator's clicks, drags, wheel and keys back. It
 * reconnects by itself when the register or CAT drops the connection.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): see {@link com.verifone.cat.pos.inspect.PosScreencast}.
 */
final class PosPage {

    private PosPage() {}

    /**
     * The page body. {@code subtitle} names the rig and register; {@code cdpUrl} is the register's DevTools endpoint,
     * or null when CAT was started without a register (the page then says so and opens nothing).
     */
    static String body(String subtitle, String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            return "<p class=\"muted\">" + esc(subtitle) + ".</p>"
                    + "<p class=\"warn\">No register to show: start CAT with <code>--rig &lt;name&gt;</code> "
                    + "(and <code>--register &lt;id&gt;</code> on a rig with more than one).</p>";
        }
        return "<p class=\"muted\">" + esc(subtitle) + ". The register's browser through DevTools at <code>"
                + esc(cdpUrl) + "</code>. Clicks and keys on the screen go to the live POS.</p>"
                + "<section class=\"card pos-screen\">"
                + "<div class=\"pos-screen-bar\"><span id=\"pos-screen-status\" role=\"status\">Connecting to the "
                + "register…</span><span id=\"pos-screen-page\" class=\"muted\"></span></div>"
                + "<div class=\"pos-screen-frame\"><canvas id=\"pos-screen\" tabindex=\"0\" data-socket=\""
                + PosScreenRoutes.PATH + "\" aria-label=\"The register's screen; clicks and keys go to the live POS\">"
                + "</canvas></div>"
                + "</section>"
                + "<script src=\"/static/pos-screen.js\" defer></script>";
    }
}
