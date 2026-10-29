package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.verifone.cat.devices.printer.TopazPrinter;
import com.verifone.cat.runservice.RunState;
import com.verifone.cat.runservice.RunView;

/**
 * One run's board: its cases in plan order with a filter, the current step's card (which opens the step dialog),
 * and the Simulators and Connections rails. The Python CAT's {@code cat.web.components.RunBoard}, drawn from a
 * {@link RunState} so the Run page draws the live run with it and Results draws a finished one.
 *
 * <p>Each part is its own element. On a live run it polls itself every two seconds and the server answers
 * {@code 204} (no swap) when it would draw the same thing ({@link #polled}), so a case dialog or the filter is not
 * disturbed by a poll that changes nothing. The filter is outside the polled table and is re-applied after each swap
 * ({@link RunStyle}).
 *
 * <p>A simulator row opens that simulator's lines on the Log tab; the Receipt Printer's opens its roll. A row's
 * detail is its title, as the Python's tooltip.
 */
final class RunBoardHtml {

    /** The id the dialogs are swapped into. */
    static final String DIALOG = "run-dialog";

    /** How often a live piece polls. */
    static final String POLL = "every 2s";

    private RunBoardHtml() {}

    // ------------------------------------------------------------------ shared pieces

    /** A status as a coloured word; the word is always shown, never only the colour. */
    static String badge(String status) {
        String tone = switch (RunView.tone(status)) {
            case "ok" -> "ok";
            case "bad" -> "error";
            case "warn" -> "warn";
            case "run" -> "run";
            default -> "muted";
        };
        return "<span class=\"badge " + tone + "\">" + esc(status.replace('_', ' ')) + "</span>";
    }

    /** {@code hx-get} into the dialog, as attributes. */
    static String dialogGet(String url) {
        return "hx-get=\"" + esc(url) + "\" hx-target=\"#" + DIALOG + "\" hx-swap=\"innerHTML\"";
    }

    /** A short hash of a fragment's HTML: a poll that would draw the same is answered 204. */
    static String hash(String html) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(html.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(html.hashCode());
        }
    }

    /**
     * {@code inner} in an element with {@code id}; on a live run it polls {@code url} with its own hash, so the server
     * can answer "no change". {@code url} may already carry a query.
     */
    static String polled(String tag, String id, String classes, String url, String inner, boolean live) {
        StringBuilder out = new StringBuilder("<").append(tag).append(" id=\"").append(id).append('"');
        if (!classes.isEmpty()) {
            out.append(" class=\"").append(classes).append('"');
        }
        if (live) {
            String join = url.contains("?") ? "&" : "?";
            out.append(" hx-get=\"").append(esc(url + join + "h=" + hash(inner))).append("\" hx-trigger=\"")
                    .append(POLL).append("\" hx-swap=\"outerHTML\"");
        }
        return out.append('>').append(inner).append("</").append(tag).append('>').toString();
    }

    // ------------------------------------------------------------------ the board

    /** The whole board: the cases beside the current step, the Simulators and the Connections. */
    static String board(RunState state, RunLinks links, String casesUrl, String sideUrl) {
        return "<div class=\"run-board\"><div class=\"run-board-cases\"><div class=\"run-case-head\">"
                + "<h2>Test cases</h2><span class=\"grow\"></span>"
                + "<input type=\"search\" id=\"run-filter\" placeholder=\"Filter\" aria-label=\"Filter the cases\" "
                + "oninput=\"catRun.filter(this.value)\"></div>"
                + cases(state, links, casesUrl)
                + "<p class=\"muted\" style=\"padding: 0 12px 8px; font-size: 12px;\">Click a case to see its steps.</p>"
                + "</div>" + side(state, links, sideUrl) + "</div>";
    }

    /** The case table, polled on a live run. */
    static String cases(RunState state, RunLinks links, String url) {
        return polled("div", "run-cases", "", url, casesInner(state, links), links.live());
    }

    static String casesInner(RunState state, RunLinks links) {
        List<RunView.CaseRow> rows = RunView.caseRows(state);
        StringBuilder out = new StringBuilder();
        out.append("<p class=\"muted\" style=\"padding: 0 12px; margin: 0; font-size: 13px;\">").append(rows.size())
                .append(" · ").append(state.cases().isEmpty() ? "start order" : "plan order").append("</p>");
        out.append("<table class=\"run-case-table\"><thead><tr><th>Case</th><th>Result</th>")
                .append("<th class=\"num\">Time</th></tr></thead><tbody>");
        for (RunView.CaseRow row : rows) {
            out.append("<tr><td><button type=\"button\" class=\"run-case-link\" ").append(dialogGet(links.caseDetail(
                    row.key()))).append('>').append(esc(RunView.caseLine(row.key(), row.title()))).append("</button>");
            if (!row.detail().isEmpty()) {
                out.append("<div class=\"run-case-detail\">").append(esc(row.detail())).append("</div>");
            }
            out.append("</td><td>").append(badge(row.status())).append("</td><td class=\"num\">")
                    .append(esc(row.elapsed())).append("</td></tr>");
        }
        out.append("</tbody></table>");
        if (rows.isEmpty()) {
            out.append("<p class=\"muted\" style=\"padding: 0 12px;\">No cases yet: the plan resolves when the tests start.</p>");
        }
        return out.toString();
    }

    /** The current step's card and the Simulators and Connections rails, polled on a live run. */
    static String side(RunState state, RunLinks links, String url) {
        return polled("aside", "run-side", "run-side", url, sideInner(state, links), links.live());
    }

    static String sideInner(RunState state, RunLinks links) {
        StringBuilder out = new StringBuilder();
        List<RunView.StepRow> rows = RunView.stepRows(state);
        int active = RunView.activeStep(rows);
        RunView.StepRow current = rows.get(active);
        out.append("<button type=\"button\" class=\"run-current\" ").append(dialogGet(links.step(active)))
                .append("><span class=\"info\">Step ").append(active + 1).append(" of ").append(rows.size())
                .append("</span><span class=\"title\">").append(esc(current.name())).append("</span><span>")
                .append(badge(current.status())).append(" <span class=\"info\">").append(esc(current.elapsed()))
                .append("</span></span><span class=\"info\">View step details</span></button>");

        out.append("<div class=\"run-side-card\"><h3>Simulators</h3>");
        if (state.simulators().isEmpty()) {
            out.append("<p class=\"run-side-empty\">No simulators yet</p>");
        }
        for (RunState.SimulatorState sim : state.simulators()) {
            String title = sim.detail().isEmpty() ? sim.bind() : sim.detail();
            if (TopazPrinter.PRINTER_NAME.equals(sim.name())) {
                out.append("<div class=\"run-side-row click\" role=\"button\" tabindex=\"0\" title=\"").append(esc(title))
                        .append("\" ").append(dialogGet(links.receipts())).append('>');
            } else {
                out.append("<a class=\"run-side-row click\" href=\"").append(esc(links.simLog(sim.name())))
                        .append("\" title=\"").append(esc(title)).append("\">");
            }
            out.append("<span class=\"grow\">").append(esc(sim.name())).append("</span>").append(badge(sim.status()));
            out.append(TopazPrinter.PRINTER_NAME.equals(sim.name()) ? "</div>" : "</a>");
        }
        out.append("</div>");

        out.append("<div class=\"run-side-card\"><h3>Connections</h3>");
        if (state.connections().isEmpty()) {
            out.append("<p class=\"run-side-empty\">No connections probed yet</p>");
        }
        for (RunState.ConnectionState conn : state.connections()) {
            String title = conn.detail().isEmpty() ? conn.probeType() : conn.detail();
            out.append("<div class=\"run-side-row\" title=\"").append(esc(title)).append("\"><span class=\"grow\">")
                    .append(esc(conn.target())).append("</span>").append(badge(conn.state())).append("</div>");
        }
        out.append("</div>");
        return out.toString();
    }
}
