package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.authoring.Case;
import com.verifone.cat.authoring.CaseReview;
import com.verifone.cat.authoring.Session;
import com.verifone.cat.authoring.StepDef;

/**
 * Authoring, ported from {@code cat/web/pages/authoring.py} in its compact browser layout (rig owner, 2026-10-23):
 * the Jira and session toolbars, the cases table, and the Feature / Steps / Spec / Review editor. Record and
 * the fake POS are not here, by the same decision.
 *
 * <p>The whole workbench is one swapped section ({@code #authoring-workbench}); every action POSTs and gets it back,
 * so the page is the server's state. The Feature editor is the one exception: it saves as the operator types and
 * answers with its diagnostics and the cases table only, so the text under the cursor is never replaced.
 *
 * <p>Built: Jira ingest (read-only), the cases, the editor and checker, the step library, approve, and the publish
 * dry run. Not built, and shown disabled with the Python module that would build them: Draft with Claude
 * ({@code cat/authoring/draft.py}), Capture and Replay ({@code cat/authoring/replay.py}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class AuthoringPage {

    /** The selector the whole workbench is swapped by. */
    static final String WORKBENCH = "#authoring-workbench";

    /** The inputs every action sends with it, so the server saves the draft and the ticket fields first. */
    static final String INCLUDE = "#authoring-text, #authoring-case, #authoring-ticket, #authoring-spec, #authoring-name";

    private static final String[] TAB_LABELS = {"Feature", "Steps", "Spec", "Review"};

    private AuthoringPage() {}

    /** The workbench for the page: called with the service's state held still. */
    static String workbench(AuthoringService service) {
        Session session = service.session();
        Case selected = service.selected();
        CaseReview review = session == null || selected == null ? null : session.review(selected);
        String tab = service.tab();

        StringBuilder out = new StringBuilder();
        out.append("<section id=\"authoring-workbench\" class=\"card wide authoring\" aria-label=\"Authoring\">");
        out.append("<p class=\"authoring-status muted\">").append(esc(status(session))).append("</p>");
        String message = service.message();
        if (!message.isEmpty()) {
            out.append("<p class=\"authoring-message ").append(tone(message)).append("\" role=\"status\">")
                    .append(esc(message)).append("</p>");
        }

        out.append("<div class=\"authoring-toolbar\">");
        out.append("<label>Story ticket <input id=\"authoring-ticket\" name=\"ticket\" value=\"")
                .append(esc(service.ticketField())).append("\" placeholder=\"e.g. PETROCORE-12345\"></label>");
        out.append(action("Save", "/authoring/ticket", "ghost"));
        out.append("<label>Spec ticket to ingest <input id=\"authoring-spec\" name=\"spec\" value=\"")
                .append(esc(service.specField())).append("\" placeholder=\"e.g. PETROCORE-678\"></label>");
        out.append(action("Ingest", "/authoring/ingest", ""));
        notBuilt("Draft with Claude", AuthoringService.DRAFT_MODULE, out);
        out.append("</div>");

        out.append("<div class=\"authoring-toolbar\">");
        out.append("<label>Case name <input id=\"authoring-name\" name=\"name\" "
                + "placeholder=\"e.g. cash sale with charity\"></label>");
        out.append(action("New case", "/authoring/case/new", ""));
        out.append(action("Rename", "/authoring/case/rename", ""));
        out.append(action("Move up", "/authoring/case/move?delta=-1", "ghost"));
        out.append(action("Move down", "/authoring/case/move?delta=1", "ghost"));
        out.append("<button type=\"button\" class=\"danger\" hx-post=\"/authoring/case/remove\""
                + " hx-confirm=\"Remove this case from the session? Its draft file stays on disk.\""
                + " hx-include=\"" + INCLUDE + "\" hx-target=\"" + WORKBENCH + "\" hx-swap=\"outerHTML\">Remove</button>");
        out.append(action("New session", "/authoring/session/new", "ghost"));
        out.append("</div>");

        out.append("<div class=\"authoring-toolbar\">");
        notBuilt("Capture", AuthoringService.REPLAY_MODULE, out);
        out.append(action("Approve baselines", "/authoring/approve", "ghost"));
        notBuilt("Replay", AuthoringService.REPLAY_MODULE, out);
        out.append(action("Publish (dry run)", "/authoring/publish", ""));
        out.append("</div>");

        out.append("<div class=\"authoring-workspace\">");
        out.append(cases(session, service.cursor(), false));
        out.append("<div class=\"authoring-editor-pane\">");
        out.append(tabs(tab));
        out.append(featurePanel(service, selected, review, tab));
        out.append(stepsPanel(service, tab));
        out.append("<div role=\"tabpanel\" id=\"authoring-panel-spec\" aria-label=\"Spec\"")
                .append(hidden(tab, AuthoringService.TAB_SPEC)).append(">")
                .append(AuthoringService.TAB_SPEC.equals(tab) ? AuthoringReview.spec(session) : "").append("</div>");
        out.append("<div role=\"tabpanel\" id=\"authoring-panel-review\" aria-label=\"Review\"")
                .append(hidden(tab, AuthoringService.TAB_REVIEW)).append(">")
                .append(AuthoringService.TAB_REVIEW.equals(tab)
                        ? AuthoringReview.review(session, review, gate(session), service.publishLines()) : "")
                .append("</div>");
        out.append("</div></div></section>");
        return out.toString();
    }

    /** What the editor's answer carries: its diagnostics, and the cases table out of band. */
    static String checkFragment(AuthoringService service) {
        Session session = service.session();
        Case selected = service.selected();
        CaseReview review = session == null || selected == null ? null : session.review(selected);
        return AuthoringReview.diagnostics(review) + cases(session, service.cursor(), true);
    }

    /** The Steps tab's list for the current filter. */
    static String steps(AuthoringService service) {
        return stepList(AuthoringSteps.matching(service.stepFilter()));
    }

    // ------------------------------------------------------------ parts

    private static String status(Session session) {
        if (session == null) {
            return "Authoring — no session yet";
        }
        List<String> parts = new ArrayList<>();
        parts.add("Session " + session.slug());
        parts.add("story " + (session.ticket() == null ? "—" : session.ticket()));
        parts.add("spec " + (session.specTicket() == null ? "—" : session.specTicket()));
        parts.add(session.cases().size() + " case(s)");
        return String.join(" · ", parts);
    }

    private static String tone(String message) {
        if (message.startsWith("✖")) {
            return "bad";
        }
        return message.startsWith("✔") ? "ok" : "";
    }

    /** The cases table; {@code oob} marks it for an out-of-band swap from the editor's answer. */
    static String cases(Session session, int cursor, boolean oob) {
        StringBuilder out = new StringBuilder("<div id=\"authoring-cases\" class=\"authoring-cases\"");
        if (oob) {
            out.append(" hx-swap-oob=\"true\"");
        }
        out.append('>');
        if (session == null || session.cases().isEmpty()) {
            out.append("<p class=\"muted\">No cases yet</p></div>");
            return out.toString();
        }
        out.append("<table class=\"authoring-table\"><thead><tr><th>#</th><th>Case</th><th>From</th>"
                + "<th>Check</th><th>Baselines</th></tr></thead><tbody>");
        List<Case> cases = session.cases();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            CaseReview review = session.review(c);
            out.append(i == cursor ? "<tr class=\"selected\">" : "<tr>");
            out.append("<td class=\"num\">").append(session.number(c)).append("</td>");
            out.append("<td><button type=\"button\" class=\"cat-case-link\" hx-post=\"/authoring/select?index=")
                    .append(i).append("\" hx-include=\"").append(INCLUDE).append("\" hx-target=\"")
                    .append(WORKBENCH).append("\" hx-swap=\"outerHTML\">").append(esc(review.title()))
                    .append("</button></td>");
            out.append("<td>").append(esc(AuthoringReview.source(c.source()))).append("</td>");
            out.append("<td>").append(AuthoringReview.checkCell(review)).append("</td>");
            out.append("<td>").append(AuthoringReview.baselinesCell(review)).append("</td>");
            out.append("</tr>");
        }
        out.append("</tbody></table></div>");
        return out.toString();
    }

    private static String tabs(String active) {
        StringBuilder out = new StringBuilder("<div class=\"authoring-tabs\" role=\"tablist\">");
        for (int i = 0; i < AuthoringService.TABS.size(); i++) {
            String name = AuthoringService.TABS.get(i);
            boolean on = name.equals(active);
            out.append("<button type=\"button\" role=\"tab\" class=\"tab").append(on ? " on" : "").append('"')
                    .append(" aria-selected=\"").append(on).append('"')
                    .append(" hx-post=\"/authoring/tab?name=").append(name).append('"')
                    .append(" hx-include=\"").append(INCLUDE).append("\" hx-target=\"").append(WORKBENCH)
                    .append("\" hx-swap=\"outerHTML\">").append(TAB_LABELS[i]).append("</button>");
        }
        return out.append("</div>").toString();
    }

    private static String featurePanel(AuthoringService service, Case selected, CaseReview review, String tab) {
        StringBuilder out = new StringBuilder(
                "<div role=\"tabpanel\" id=\"authoring-panel-feature\" aria-label=\"Feature\"")
                .append(hidden(tab, AuthoringService.TAB_FEATURE)).append('>');
        out.append("<input type=\"hidden\" id=\"authoring-case\" name=\"case\" value=\"")
                .append(esc(selected == null ? "" : selected.id())).append("\">");
        if (selected == null) {
            out.append("<p class=\"muted\">Add a case to write its steps here.</p>");
        }
        String text = selected == null || service.session() == null ? "" : service.session().text(selected);
        out.append("<textarea id=\"authoring-text\" name=\"text\" class=\"authoring-text\" spellcheck=\"false\""
                + " aria-label=\"Feature text\"")
                .append(selected == null ? " disabled" : "")
                .append(" hx-post=\"/authoring/feature\" hx-trigger=\"input changed delay:700ms\""
                        + " hx-include=\"#authoring-case\" hx-target=\"#authoring-check\" hx-swap=\"innerHTML\">")
                .append(esc(text)).append("</textarea>");
        out.append("<div id=\"authoring-check\" class=\"authoring-check\">")
                .append(AuthoringReview.diagnostics(review)).append("</div>");
        return out.append("</div>").toString();
    }

    private static String stepsPanel(AuthoringService service, String tab) {
        StringBuilder out = new StringBuilder(
                "<div role=\"tabpanel\" id=\"authoring-panel-steps\" aria-label=\"Steps\"")
                .append(hidden(tab, AuthoringService.TAB_STEPS)).append('>');
        out.append("<input id=\"authoring-filter\" name=\"filter\" type=\"search\" value=\"")
                .append(esc(service.stepFilter())).append("\" placeholder=\"Filter phrases; click one to insert it\""
                        + " aria-label=\"Filter phrases\" hx-post=\"/authoring/steps\""
                        + " hx-trigger=\"input changed delay:250ms\" hx-target=\"#authoring-steps\""
                        + " hx-swap=\"outerHTML\">");
        out.append(steps(service));
        return out.append("</div>").toString();
    }

    private static String stepList(List<StepDef> steps) {
        StringBuilder out = new StringBuilder("<ul id=\"authoring-steps\" class=\"authoring-steps\">");
        if (steps.isEmpty()) {
            out.append("<li class=\"muted\">No phrase matches that filter.</li>");
        }
        for (StepDef step : steps) {
            String label = step.display() + ("ui".equals(step.level()) ? " [UI level]" : "")
                    + "  — " + step.group();
            out.append("<li><button type=\"button\" class=\"step\" hx-post=\"/authoring/insert?example=")
                    .append(esc(URLEncoder.encode(step.example(), StandardCharsets.UTF_8)))
                    .append("\" hx-include=\"").append(INCLUDE).append("\" hx-target=\"").append(WORKBENCH)
                    .append("\" hx-swap=\"outerHTML\"><span>").append(esc(label)).append("</span>")
                    .append("<span class=\"cat-mono muted\">").append(esc(step.example())).append("</span>")
                    .append("</button></li>");
        }
        return out.append("</ul>").toString();
    }

    /** The Review tab's gate: the publish blockers, then the replay gate's reason, which is always there for now. */
    private static List<String> gate(Session session) {
        if (session == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(session.publishBlockers());
        out.add(AuthoringService.GATE_REASON);
        return out;
    }

    // ------------------------------------------------------------ small markup

    private static String hidden(String tab, String panel) {
        return tab.equals(panel) ? "" : " hidden";
    }

    private static String action(String label, String url, String cls) {
        return "<button type=\"button\"" + (cls.isEmpty() ? "" : " class=\"" + cls + "\"")
                + " hx-post=\"" + esc(url) + "\" hx-include=\"" + INCLUDE + "\" hx-target=\"" + WORKBENCH
                + "\" hx-swap=\"outerHTML\">" + esc(label) + "</button>";
    }

    /** A control this build has not ported: disabled, and its label says what is missing. */
    private static void notBuilt(String label, String module, StringBuilder out) {
        out.append("<button type=\"button\" disabled title=\"Not built yet: ").append(esc(module)).append("\">")
                .append(esc(label)).append("</button>");
        out.append("<span class=\"placeholder-note\">not built yet — ").append(esc(module)).append("</span>");
    }
}
