package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import com.verifone.cat.runconfig.ConfigView;
import com.verifone.cat.runconfig.RunScope;

/**
 * The Run → Config tab's markup (docs/web-ui.md, "Run → Config"): the plan picker with its
 * filter, Run scope, the "Needs:" line, and the card that says what Start run will do.
 *
 * <p>Every control posts to {@code /config/...} and the answer replaces {@code #run-config}, so
 * the page is always the server's one reading of the choices. The test plan branch, Downloads,
 * Commander Connect, Execution and Refresh from Confluence load from {@code ConfigSections}' fragments.
 * Every dynamic value goes through {@link Html#esc}.
 */
final class RunConfigPage {

    /** The id the forms swap; the routes answer with the whole panel. */
    static final String PANEL_ID = "run-config";

    private static final String TARGET = "hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\"";

    private RunConfigPage() {}

    /** The whole tab, inside {@code #run-config}. */
    static String panel(ConfigView view) {
        StringBuilder out = new StringBuilder();
        out.append("<div id=\"").append(PANEL_ID).append("\" class=\"config\">");

        out.append("<section class=\"card config-section\"><h2>Run configuration</h2>");
        out.append("<div class=\"config-row\"><div class=\"config-label\">Test plans</div>");
        out.append("<div class=\"config-body\">");
        out.append("<div class=\"config-tools\">")
                .append("<input type=\"search\" id=\"config-filter\" name=\"filter\" placeholder=\"Filter plans\" ")
                .append("aria-label=\"Filter plans\" autocomplete=\"off\" value=\"").append(esc(view.filter()))
                .append("\" hx-get=\"/fragments/config/plans\" hx-trigger=\"input changed delay:300ms\" ")
                .append("hx-target=\"#plan-list\" hx-swap=\"innerHTML\">")
                .append("<span hx-get=\"/fragments/config/refresh\" hx-trigger=\"load\" hx-swap=\"outerHTML\"></span>")
                .append("<span class=\"muted\">").append(esc(view.catalogueAge())).append("</span>")
                .append("</div>");
        if (!view.catalogueNote().isEmpty()) {
            out.append("<p class=\"warn\">").append(esc(view.catalogueNote())).append("</p>");
        }
        out.append("<div id=\"plan-list\" class=\"config-scroll\" role=\"group\" aria-label=\"Test plans\">");
        out.append(planList(view));
        out.append("</div>");
        out.append("<div class=\"muted pre\">");
        for (String line : view.caseLines()) {
            out.append("<div>").append(esc(line)).append("</div>");
        }
        out.append("</div>");
        if (!view.needs().isEmpty()) {
            out.append("<p class=\"muted\">").append(esc(view.needs())).append("</p>");
        }
        out.append("</div></div></section>");

        out.append("<section class=\"card config-section\"><h2>Run scope</h2>");
        out.append(scopeForm(view));
        out.append(caseList(view));
        out.append("</section>");

        // The test plan branch, Downloads, Commander Connect and Execution sections (webshell's ConfigSections).
        out.append("<div hx-get=\"/fragments/config/controls\" hx-trigger=\"load\" hx-swap=\"outerHTML\"></div>");

        out.append("<section class=\"card config-section\"><h2>What Start run will do</h2>");
        if (view.missing().isEmpty()) {
            out.append("<p class=\"ok\">No choice on this page stops a run.</p>");
        }
        out.append("<div class=\"cat-mono\">");
        for (String line : view.summary()) {
            out.append("<div>").append(esc(line)).append("</div>");
        }
        out.append("</div></section>");

        out.append("</div>");
        return out.toString();
    }

    /** The picker's rows, grouped by section; the filter route swaps just this. */
    static String planList(ConfigView view) {
        StringBuilder out = new StringBuilder();
        if (view.sections().isEmpty()) {
            return "<p class=\"muted\">" + (view.planCount() == 0
                    ? "No plans in the catalogue yet."
                    : "No plan matches the filter.") + "</p>";
        }
        for (ConfigView.Section section : view.sections()) {
            out.append("<h3 class=\"config-group\">").append(esc(section.name())).append("</h3>");
            for (ConfigView.PlanRow row : section.rows()) {
                out.append("<label class=\"config-check\"><input type=\"checkbox\"")
                        .append(row.ticked() ? " checked" : "")
                        .append(" hx-post=\"/config/plan\" hx-vals=\"")
                        .append(esc("{\"key\":\"" + row.key() + "\"}"))
                        .append("\" ").append(TARGET).append('>')
                        .append(esc(row.label())).append("</label>");
            }
        }
        return out.toString();
    }

    /** Run scope's radios, Tags text, and the prerequisites switch, in one form. */
    static String scopeForm(ConfigView view) {
        RunScope scope = view.scope();
        StringBuilder out = new StringBuilder();
        out.append("<form id=\"scope-form\" class=\"config-scope\" hx-post=\"/config/scope\" ")
                .append("hx-trigger=\"change, submit\" ").append(TARGET).append('>');
        out.append("<div class=\"seg\" role=\"radiogroup\" aria-label=\"Run scope\">");
        for (RunScope.Mode mode : RunScope.Mode.values()) {
            out.append("<label><input type=\"radio\" name=\"mode\" value=\"").append(esc(mode.value()))
                    .append('"').append(mode == scope.mode() ? " checked" : "").append('>')
                    .append(esc(mode.label())).append("</label>");
        }
        out.append("</div>");
        // Always in the form, hidden when it does not apply: a form post always carries it.
        out.append("<div class=\"config-tags\"").append(scope.mode() == RunScope.Mode.TAGS ? "" : " hidden")
                .append("><label>Tags <input type=\"text\" name=\"tags\" placeholder=\"not @plan_end\" value=\"")
                .append(esc(scope.tags())).append("\"></label>")
                .append("<span class=\"muted\">").append(esc(view.tagCount())).append("</span></div>");
        out.append("<label class=\"config-check\"").append(view.partial() ? "" : " hidden")
                .append("><input type=\"checkbox\" name=\"prerequisites\" value=\"on\"")
                .append(scope.prerequisites() ? " checked" : "")
                .append("> Add the prerequisites a partial scope leaves out</label>");
        if (!view.scopeSummary().isEmpty()) {
            out.append("<p class=\"muted\">Run scope: ").append(esc(view.scopeSummary())).append("</p>");
        }
        for (String warning : view.scopeWarnings()) {
            out.append("<p class=\"warn\">").append(esc(warning)).append("</p>");
        }
        out.append("</form>");
        return out.toString();
    }

    /** The cases a partial scope lists, ticked by hand or by its expression. */
    static String caseList(ConfigView view) {
        if (!view.partial() || view.caseRows().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<div class=\"config-scroll config-cases\" aria-label=\"Cases\">");
        for (ConfigView.CaseRow row : view.caseRows()) {
            String reason = row.neededBy().isEmpty() ? "" : " · needed by " + shortKey(row.neededBy());
            String label = (row.added() ? "+ " : "") + row.key() + "  " + row.title() + reason
                    + (row.tags().isEmpty() ? "" : "  " + row.tags());
            out.append("<label class=\"config-check").append(row.added() ? " warn" : "").append("\">")
                    .append("<input type=\"checkbox\"").append(row.ticked() ? " checked" : "")
                    .append(" hx-post=\"/config/case\" hx-vals=\"")
                    .append(esc("{\"key\":\"" + row.key() + "\"}"))
                    .append("\" ").append(TARGET).append('>')
                    .append(esc(label)).append("</label>");
        }
        return out.append("</div>").toString();
    }

    private static String shortKey(String key) {
        return key.replaceFirst("(?i)^PETROCORE-(\\d+)$", "$1");
    }
}
