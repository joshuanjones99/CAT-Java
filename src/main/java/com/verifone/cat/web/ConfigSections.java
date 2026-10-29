package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.Map;

import com.verifone.cat.runconfig.ConfigControls;
import com.verifone.cat.runconfig.ConfigForm;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * The Config tab's sections after Run scope: Test plan branch, Downloads (the shared {@link DownloadsPanel}),
 * Commander Connect and Execution, as {@code cat/web/pages/config.py} draws them, and the Refresh from Confluence
 * control. Every input posts {@code /config/set} on change and is saved to the form's file at once.
 *
 * <p>The Config tab's page ({@code RunConfigPage}) is the webrun slice's; it includes these sections by calling
 * {@link #html} in place of its placeholders, or by loading {@link #PATH} (wave-16 hook). The Refresh button here
 * replaces the page's disabled one.
 *
 * <p>Every POST needs htmx's {@code HX-Request} header, as the other Config changes do. A name or value the form does
 * not offer is refused and never echoed. The Commander Connect password is shown masked, with Show, as the Python's
 * {@code password_toggle_button}.
 */
final class ConfigSections {

    /** The fragment's address. */
    static final String PATH = "/fragments/config/controls";
    static final String PANEL_ID = "config-controls";

    private static final String TARGET = "hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\"";

    private ConfigSections() {}

    static void register(Javalin app, ConfigForm form, DownloadsService downloads) {
        app.get(PATH, ctx -> ctx.html(html(form, downloads)));
        app.post("/config/set", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            String name = ctx.formParam("name");
            String value = ctx.formParam("value");
            if (name == null || !ConfigForm.CHOICES.contains(name)) {
                ctx.status(HttpStatus.BAD_REQUEST).result("Not a Config choice.");
                return;
            }
            // A switch's unticked checkbox posts nothing: that is off.
            if (!form.set(name, value == null ? "" : value)) {
                ctx.status(HttpStatus.BAD_REQUEST).result("That value is not offered.");
                return;
            }
            ctx.html(html(form, downloads));
        });
        app.post("/config/refresh", ctx -> {
            if (!fromPage(ctx)) {
                return;
            }
            form.refreshFromConfluence();
            ctx.html(refreshControl(form));
        });
        app.get("/fragments/config/refresh", ctx -> ctx.html(refreshControl(form)));
    }

    /** Refresh from Confluence, with the catalogue's age beside it ("refreshing…" while it reads). */
    static String refreshControl(ConfigForm form) {
        boolean refreshing = form.refreshing();
        StringBuilder out = new StringBuilder("<span id=\"config-refresh\" class=\"config-refresh\"");
        if (refreshing) {
            out.append(" hx-get=\"/fragments/config/refresh\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\"");
        }
        out.append("><button type=\"button\" class=\"ghost\" hx-post=\"/config/refresh\" hx-target=\"#config-refresh\" "
                + "hx-swap=\"outerHTML\"").append(refreshing ? " disabled" : "")
                .append(" title=\"").append(esc(ConfigHelpWords.get("config-plans"))).append("\">")
                .append("Refresh from Confluence</button> <span class=\"muted\">")
                .append(esc(form.view().catalogueAge())).append("</span></span>");
        return out.toString();
    }

    /** The sections, inside {@code #config-controls}. */
    static String html(ConfigForm form, DownloadsService downloads) {
        ConfigControls c = form.controls();
        StringBuilder out = new StringBuilder("<div id=\"" + PANEL_ID + "\" class=\"config-controls\">");
        out.append("<section class=\"card config-section\"><h2>Test plan branch</h2>");
        row(out, "Test plan branch", "config-tp-branch", c.help(),
                input("tpBranch", "text", c.testPlanBranch(), "master", "Test plan branch"));
        out.append("</section>");

        out.append("<section class=\"card config-section\"><h2>Downloads</h2>");
        out.append(DownloadsPanel.fragment(downloads));
        out.append("</section>");

        out.append("<section class=\"card config-section\"><h2>Commander Connect</h2>");
        row(out, "Onboard", "config-cc-onboard", c.help(), toggle("ccOnboard", c.ccOnboard(), "Onboard"));
        out.append("<div class=\"config-fields").append(c.ccOnboard() ? "" : " dim").append("\">");
        StringBuilder labs = new StringBuilder();
        for (String lab : c.ccLabs()) {
            labs.append(option(lab, lab, lab.equals(c.ccLab())));
        }
        row(out, "Lab", "config-cc-lab", c.help(), select("ccLab", labs.toString(), "Lab"));
        row(out, "Email", "config-cc-email", c.help(),
                input("ccEmail", "email", c.ccEmail(), "name@example.com", "Email"));
        row(out, "Password", "config-cc-password", c.help(), password(c.ccPassword()));
        row(out, "Service ID", "config-cc-service-id", c.help(),
                input("ccServiceId", "text", c.ccServiceId(), "", "Service ID"));
        out.append("</div></section>");

        out.append("<section class=\"card config-section\"><h2>Execution</h2>");
        StringBuilder exec = new StringBuilder();
        for (Map.Entry<String, String> o : c.execOptions()) {
            exec.append(option(o.getValue(), o.getKey(), o.getValue().equals(c.execMode())));
        }
        row(out, "Execution mode", "config-exec", c.help(), select("execMode", exec.toString(), "Execution mode"));
        StringBuilder ifsf = new StringBuilder();
        for (Map.Entry<String, String> o : c.ifsfOptions()) {
            ifsf.append(option(o.getValue(), o.getKey(), o.getValue().equals(c.ifsfMode())));
        }
        row(out, "IFSF mode", "config-ifsf", c.help(), select("ifsfMode", ifsf.toString(), "IFSF mode"));
        row(out, "JaCoCo coverage", "config-coverage", c.help(), toggle("coverage", c.coverage(), "JaCoCo coverage"));
        row(out, "Report to Xray", "config-xray", c.help(), toggle("xray", c.xray(), "Report to Xray"));
        out.append("</section>");
        if (!c.fetchingCases().isEmpty()) {
            out.append("<p class=\"muted\" role=\"status\">Fetching the case list for ")
                    .append(esc(String.join(", ", c.fetchingCases()))).append(" from Jira…</p>");
        }
        return out.append("</div>").toString();
    }

    private static void row(StringBuilder out, String label, String helpKey, Map<String, String> help, String body) {
        out.append("<div class=\"config-row\"><div class=\"config-label\" title=\"")
                .append(esc(help.getOrDefault(helpKey, ""))).append("\">").append(esc(label))
                .append(" <span class=\"muted\" aria-hidden=\"true\">&#9432;</span></div><div class=\"config-body\">")
                .append(body).append("</div></div>");
    }

    /** One text input; the form posts on change and on Enter. */
    private static String input(String name, String type, String value, String placeholder, String label) {
        return "<form class=\"config-set\" hx-post=\"/config/set\" hx-trigger=\"change, submit\" " + TARGET + ">"
                + "<input type=\"hidden\" name=\"name\" value=\"" + esc(name) + "\">"
                + "<input type=\"" + type + "\" name=\"value\" value=\"" + esc(value) + "\" placeholder=\""
                + esc(placeholder) + "\" aria-label=\"" + esc(label) + "\" autocomplete=\"off\"></form>";
    }

    private static String password(String value) {
        return "<form class=\"config-set setup-input\" hx-post=\"/config/set\" hx-trigger=\"change, submit\" " + TARGET
                + "><input type=\"hidden\" name=\"name\" value=\"ccPassword\">"
                + "<input id=\"config-cc-password\" type=\"password\" name=\"value\" value=\"" + esc(value)
                + "\" aria-label=\"Password\" autocomplete=\"off\">"
                + "<button type=\"button\" class=\"ghost\" data-action=\"reveal\" data-target=\"config-cc-password\">"
                + "Show</button></form>";
    }

    private static String select(String name, String options, String label) {
        return "<form class=\"config-set\" hx-post=\"/config/set\" hx-trigger=\"change\" " + TARGET + ">"
                + "<input type=\"hidden\" name=\"name\" value=\"" + esc(name) + "\">"
                + "<select name=\"value\" aria-label=\"" + esc(label) + "\">" + options + "</select></form>";
    }

    /**
     * A switch: the checkbox posts "on" when ticked, ahead of the hidden "off", so the first {@code value} is the
     * switch's state either way.
     */
    private static String toggle(String name, boolean on, String label) {
        return "<form class=\"config-set\" hx-post=\"/config/set\" hx-trigger=\"change\" " + TARGET + ">"
                + "<input type=\"hidden\" name=\"name\" value=\"" + esc(name) + "\">"
                + "<label class=\"config-check\"><input type=\"checkbox\" name=\"value\" value=\"on\""
                + (on ? " checked" : "") + " aria-label=\"" + esc(label) + "\"> " + (on ? "On" : "Off")
                + "</label><input type=\"hidden\" name=\"value\" value=\"off\"></form>";
    }

    private static String option(String value, String text, boolean selected) {
        return "<option value=\"" + esc(value) + "\"" + (selected ? " selected" : "") + ">" + esc(text) + "</option>";
    }

    private static boolean fromPage(Context ctx) {
        if ("true".equals(ctx.header("HX-Request"))) {
            return true;
        }
        ctx.status(HttpStatus.FORBIDDEN).result("Config changes come from the CAT-Java page only.");
        return false;
    }

    /** The help words, from the form's table. */
    private static final class ConfigHelpWords {
        static String get(String key) {
            return com.verifone.cat.runconfig.ConfigHelp.HELP.getOrDefault(key, "");
        }
    }
}
