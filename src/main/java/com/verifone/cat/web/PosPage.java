package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.List;
import java.util.Map;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.posshim.ShimReplies.ExpressButton;
import com.verifone.cat.posshim.ShimReplies.Health;
import com.verifone.cat.posshim.ShimReplies.KeyPress;
import com.verifone.cat.posshim.ShimReplies.KeyPressResult;
import com.verifone.cat.posshim.ShimReplies.Overlay;
import com.verifone.cat.posshim.ShimReplies.Screen;
import com.verifone.cat.posshim.ShimReplies.SupportedKeys;

/**
 * The POS proof of concept: the shim's health, the POS screen, and key buttons, rendered on
 * the server. Not in the Python CAT's web-ui.md; kept as a page of the shell.
 *
 * <p>The body is a shell; htmx fills each card from a {@code /fragments/...} route and
 * refreshes it on a timer. A fragment that fails renders as an error card rather than an
 * HTTP error, because htmx does not swap a 4xx/5xx reply into the page.
 *
 * <p>UNVERIFIED AGAINST HARDWARE from this client's key path; the reads were checked live
 * on 2026-10-24 (HANDOFF.md).
 */
final class PosPage {

    private PosPage() {}

    /** The page body. {@code subtitle} names the rig and register; {@code shimUri} the shim. */
    static String body(String subtitle, String shimUri) {
        return "<p class=\"muted\">" + esc(subtitle) + ". Proof of concept: reads the POS through pos-shim at <code>"
                + esc(shimUri) + "</code>.</p>"
                + "<div class=\"cards\">"
                + "<section class=\"card\" hx-get=\"/fragments/health\" hx-trigger=\"load, every 5s\">Loading health…</section>"
                + "<section class=\"card wide\" hx-get=\"/fragments/screen\" hx-trigger=\"load, every 2s\">Loading screen…</section>"
                + "<section class=\"card wide\">"
                + "<h2>Press a key</h2>"
                + "<p class=\"warn\">These buttons drive the live POS.</p>"
                + "<div hx-get=\"/fragments/keys\" hx-trigger=\"load\">Loading keys…</div>"
                + "<div id=\"key-result\"></div>"
                + "</section>"
                + "</div>";
    }

    static String health(Health h) {
        StringBuilder out = new StringBuilder("<h2>Shim health</h2><dl>");
        row(out, "OK", h.ok() ? "yes" : "no");
        row(out, "Build", h.build() == null ? "(none: built before 2026-10-16)" : shortBuild(h.build()));
        row(out, "Up", h.uptimeS() + " s");
        row(out, "Clients", join(h.clients()));
        row(out, "Drivable", join(h.drivableClients()));
        row(out, "State present", h.statePresent() ? "yes" : "no");
        out.append("</dl>");
        if (h.drivableButSilent() != null && !h.drivableButSilent().isEmpty()) {
            out.append("<p class=\"warn\">Drivable but silent: ").append(esc(join(h.drivableButSilent())))
                    .append(". The POS accepts keys and pushes nothing; restart the POS, not the shim.</p>");
        }
        return out.toString();
    }

    static String screen(Screen s) {
        StringBuilder out = new StringBuilder("<h2>POS screen <span class=\"muted\">")
                .append(esc(s.client())).append("</span></h2>");
        if (!s.statePresent()) {
            return out.append("<p class=\"warn\">The POS has pushed no state, so nothing can be said about its screen.</p>")
                    .toString();
        }
        out.append("<dl>");
        row(out, "Idle", yesNo(s.idle()) + " (" + s.idleBasis() + ")");
        row(out, "Transaction open", yesNo(s.transactionActive()));
        row(out, "Last change", s.lastChangeAgeS() == null ? "unknown" : s.lastChangeAgeS() + " s ago");
        out.append("</dl>");
        if (s.lastChangeAgeS() != null && s.lastChangeAgeS() > 60) {
            out.append("<p class=\"muted\">The POS pushes state and cannot be polled. On a screen that should be moving, "
                    + "an old last change means this picture is stale.</p>");
        }

        Overlay o = s.overlay();
        if (o != null) {
            out.append("<h3>Overlay</h3><div class=\"overlay\">");
            if (o.title() != null && !o.title().isEmpty()) {
                out.append("<strong>").append(esc(o.title())).append("</strong><br>");
            }
            for (String line : new String[] {o.line1(), o.line2(), o.line3()}) {
                if (line != null) {
                    out.append(esc(line)).append("<br>");
                }
            }
            out.append("</div>");
        }
        if (s.overlayReview() != null && !s.overlayReview().isEmpty()) {
            out.append("<ul>");
            s.overlayReview().forEach(line -> out.append("<li>").append(esc(line)).append("</li>"));
            out.append("</ul>");
        }

        List<ExpressButton> buttons = s.expressPanel();
        if (buttons != null) {
            out.append("<h3>Soft keys</h3><div class=\"softkeys\">");
            for (ExpressButton b : buttons) {
                if (b.label() != null && !b.label().isEmpty()) {
                    out.append("<span class=\"softkey").append(b.disabled() ? " disabled" : "").append("\">")
                            .append(esc(b.label())).append("</span>");
                }
            }
            out.append("</div>");
        }

        Map<String, Double> totals = s.totals();
        if (totals != null) {
            out.append("<h3>Totals</h3><dl>");
            row(out, "Subtotal", money(totals.get("sub_total")));
            row(out, "Tax", money(totals.get("tax")));
            row(out, "Total due", money(totals.get("total_due")));
            row(out, "Balance due", money(totals.get("bal_due")));
            out.append("</dl>");
        }
        return out.toString();
    }

    static String keys(SupportedKeys keys) {
        StringBuilder out = new StringBuilder("<div class=\"keys\">");
        for (String key : keys.namedKeys()) {
            // The name goes into a JSON attribute, where escaping alone is not enough.
            // Every name the shim builds is a Java enum constant, so anything else is skipped.
            if (!key.matches("[A-Za-z0-9_]+")) {
                continue;
            }
            out.append("<button hx-post=\"/keys\" hx-vals='{\"key\": \"").append(esc(key)).append("\"}'")
                    .append(" hx-target=\"#key-result\" hx-confirm=\"Press ").append(esc(key))
                    .append(" on the live POS?\">").append(esc(key)).append("</button>");
        }
        out.append("</div>");
        if (keys.notes() != null) {
            keys.notes().forEach(note -> out.append("<p class=\"muted\">").append(esc(note)).append("</p>"));
        }
        return out.toString();
    }

    static String keyResult(KeyPressResult result) {
        StringBuilder out = new StringBuilder("<p class=\"")
                .append(result.allProcessed() ? "ok" : "warn").append("\">");
        for (KeyPress press : result.pressed()) {
            out.append(esc(press.key())).append(press.processed() ? " processed" : " sent, not processed (ignored by the POS)")
                    .append(" in ").append(press.elapsedMs()).append(" ms. ");
        }
        return out.append("</p>").toString();
    }

    static String error(DeviceError e) {
        return "<p class=\"error\"><strong>" + esc(e.getClass().getSimpleName()) + "</strong>: "
                + esc(e.getMessage()) + "</p>";
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append("<dt>").append(esc(label)).append("</dt><dd>").append(esc(value)).append("</dd>");
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static String yesNo(Boolean value) {
        return value == null ? "unknown" : value ? "yes" : "no";
    }

    private static String money(Double value) {
        return value == null ? "" : String.format("%.2f", value);
    }

    private static String shortBuild(String build) {
        return build.length() > 12 ? build.substring(0, 12) : build;
    }
}
