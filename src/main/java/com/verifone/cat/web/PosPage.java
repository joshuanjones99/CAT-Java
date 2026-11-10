package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

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
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): see {@link com.verifone.cat.pos.inspect.PosScreencast}.
 */
final class PosPage {

    private PosPage() {}

    /**
     * The page body. {@code cdpUrl} is the register's DevTools endpoint, or null when CAT was started without a
     * register: the page then says so, with {@code subtitle} (which names why), and opens nothing.
     */
    static String body(String subtitle, String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            return "<div class=\"pos-screen pos-screen-none\"><p class=\"warn\">No register to show: start CAT with "
                    + "<code>--rig &lt;name&gt;</code> (and <code>--register &lt;id&gt;</code> on a rig with more than "
                    + "one).</p><p class=\"muted\">" + esc(subtitle) + "</p></div>";
        }
        return "<div class=\"pos-screen\">"
                + "<canvas id=\"pos-screen\" tabindex=\"0\" data-socket=\"" + PosScreenRoutes.PATH + "\""
                + " title=\"" + esc(cdpUrl) + "\""
                + " aria-label=\"The register's screen; clicks and keys go to the live POS\"></canvas>"
                + "<p id=\"pos-screen-status\" role=\"status\">Connecting to the register…</p>"
                + "</div>"
                + "<script src=\"/static/pos-screen.js\" defer></script>";
    }
}
