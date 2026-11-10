package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.verifone.cat.commander.connect.CommanderConnect;
import com.verifone.cat.web.ToolsService.Outcome;
import com.verifone.cat.web.ToolsService.Tool;

/**
 * Tools: the operator's one-off actions on the rig, outside a run. The Python CAT's Manual page (empty by design,
 * {@code cat/web/pages/manual.py}) became this page; its first tools are CAT-Java's own (user, 2026-11-10): POS
 * Pairing, Onboard to Commander Connect, Reboot C18 and Reboot Commander.
 *
 * <p>The cards and the log are one polled fragment, so a running tool's state and lines follow it, and every button
 * is disabled while anything holds the rig. Each button asks first, naming the rig.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class ToolsPage {

    /** The panel's address, which the page polls while a tool runs. */
    static final String PATH = "/fragments/tools";
    static final String PANEL_ID = "tools-panel";

    private ToolsPage() {}

    static String body(ToolsService tools) {
        return "<p class=\"muted\">One-off actions on the rig, outside a run. Each one holds the rig while it runs, "
                + "as a run does.</p>"
                + panel(tools);
    }

    /** The four tools' cards, the refusal or notice above them, and the log below. */
    static String panel(ToolsService tools) {
        Tool running = tools.running();
        String why = tools.refusal();
        StringBuilder out = new StringBuilder("<div id=\"" + PANEL_ID + "\"");
        if (running != null) {
            out.append(" hx-get=\"").append(PATH).append("\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\"");
        }
        out.append(">");
        if (!why.isEmpty() && running == null) {
            out.append("<p class=\"warn\" role=\"status\">").append(esc(why)).append("</p>");
        }
        String notice = tools.notice();
        if (!notice.isEmpty()) {
            out.append("<p class=\"warn\" role=\"status\">").append(esc(notice)).append("</p>");
        }
        boolean blocked = !why.isEmpty();
        out.append("<div class=\"cards\">");
        out.append(card(tools, Tool.PAIR,
                "<p>Pairs every register with the Commander: provision (pairing drives the register's browser), then "
                        + "psi-init with pairing, then provision again. A Commander password the PSI installer left "
                        + "is reset first. A register already paired is left as it is.</p>",
                button(tools, Tool.PAIR, "", "Pair POS", blocked)));
        out.append(onboardCard(tools, blocked));
        out.append(rebootC18Card(tools, blocked));
        out.append(card(tools, Tool.REBOOT_COMMANDER,
                "<p>Reboots the Commander over SSH, then waits for SSH from the new boot and for CGILink to answer "
                        + "(its AAA service is the last part up).</p>",
                button(tools, Tool.REBOOT_COMMANDER, "", "Reboot Commander", blocked)));
        out.append(logCard(tools));
        out.append("</div></div>");
        return out.toString();
    }

    private static String onboardCard(ToolsService tools, boolean blocked) {
        CommanderConnect cc = tools.connectSettings();
        List<String> problems = cc.problems();
        StringBuilder text = new StringBuilder("<p>Onboards the Commander to Commander Connect with the Config tab's "
                + "values, starting the cloud tunnel first when none is running (the lab Commander has no WAN link). "
                + "A run's Commander init overwrites the onboarding, so onboard after it.</p>");
        text.append("<dl>")
                .append(row("Lab", cc.lab()))
                .append(row("Email", cc.email().isEmpty() ? "(none)" : cc.email()))
                .append(row("Service id", cc.serviceId().isEmpty() ? "(none)" : cc.serviceId()))
                .append(row("Password", cc.password().isEmpty() ? "(none)" : "set"))
                .append("</dl>");
        if (!problems.isEmpty()) {
            text.append("<p class=\"warn\">Set these on the <a href=\"/run/config\">Config tab</a> first: ")
                    .append(esc(String.join("; ", problems))).append(".</p>");
        } else {
            text.append("<p class=\"muted\">Change them on the <a href=\"/run/config\">Config tab</a>.</p>");
        }
        return card(tools, Tool.ONBOARD, text.toString(),
                button(tools, Tool.ONBOARD, "", "Onboard", blocked || !problems.isEmpty()));
    }

    private static String rebootC18Card(ToolsService tools, boolean blocked) {
        List<String> registers = tools.registers();
        String text = "<p>Reboots a register's C18 over SSH, waits for SSH from the new boot, then runs provision to "
                + "bring its pos-shim back.</p>";
        StringBuilder buttons = new StringBuilder();
        if (registers.isEmpty()) {
            text += "<p class=\"muted\">" + (tools.rigName().isEmpty() ? "No rig, so no register to reboot."
                    : "Rig " + esc(tools.rigName()) + " names no register CAT can read.") + "</p>";
            buttons.append(button(tools, Tool.REBOOT_C18, "", "Reboot C18", true));
        } else {
            for (String register : registers) {
                String label = registers.size() == 1 ? "Reboot C18" : "Reboot " + register;
                buttons.append(button(tools, Tool.REBOOT_C18, register, label, blocked));
            }
        }
        return card(tools, Tool.REBOOT_C18, text, buttons.toString());
    }

    /** One tool's card: its name, what it does, its last outcome, and its buttons. */
    private static String card(ToolsService tools, Tool tool, String text, String buttons) {
        Outcome outcome = tools.outcome(tool);
        StringBuilder out = new StringBuilder("<section class=\"card tool\" id=\"tool-" + tool.slug + "\">");
        out.append("<h2>").append(esc(tool.title)).append("</h2>").append(text);
        if (!outcome.state().isEmpty()) {
            out.append("<p role=\"status\">").append(badge(outcome.state())).append(" ")
                    .append(esc(outcome.message())).append("</p>");
        }
        out.append("<div class=\"sim-actions\">").append(buttons).append("</div>");
        return out.append("</section>").toString();
    }

    private static String badge(String state) {
        return switch (state) {
            case "running" -> Html.badge("run", "Running");
            case "done" -> Html.badge("ok", "Done");
            default -> Html.badge("error", "Failed");
        };
    }

    private static String button(ToolsService tools, Tool tool, String register, String label, boolean disabled) {
        String path = tool.path() + (register.isEmpty() ? ""
                : "?register=" + URLEncoder.encode(register, StandardCharsets.UTF_8));
        String tone = tool == Tool.REBOOT_C18 || tool == Tool.REBOOT_COMMANDER ? "run-danger" : "run-primary";
        return "<button type=\"button\" class=\"" + tone + "\" hx-post=\"" + esc(path) + "\" hx-confirm=\""
                + esc(tools.confirmText(tool, register)) + "\" hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\""
                + (disabled ? " disabled" : "") + ">" + esc(label) + "</button>";
    }

    private static String logCard(ToolsService tools) {
        List<String> lines = tools.lines();
        if (lines.isEmpty()) {
            return "";
        }
        return "<section class=\"card wide\" id=\"tools-log\"><h2>Log</h2><pre class=\"cat-pre\">"
                + esc(String.join("\n", lines)) + "</pre></section>";
    }

    private static String row(String term, String value) {
        return "<dt>" + esc(term) + "</dt><dd>" + esc(value) + "</dd>";
    }
}
