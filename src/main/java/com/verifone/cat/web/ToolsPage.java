package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.verifone.cat.web.ToolsService.Outcome;
import com.verifone.cat.web.ToolsService.Tool;

/**
 * Tools: the operator's one-off actions on the rig, outside a run. The Python CAT's Manual page (empty by design,
 * {@code cat/web/pages/manual.py}) became this page; its first tools are CAT-Java's own (user, 2026-11-10): POS
 * Pairing, Onboard to Commander Connect, Reboot C18 and Reboot Commander.
 *
 * <p>A vertical list of buttons, one per tool (one per register for Reboot C18), with no descriptions: the engineers
 * who use it know what each does (user, 2026-11-10). A row shows only its last outcome and what is wrong with the
 * setup, if anything. The list and the log are one polled fragment, so a running tool's state and lines follow it, and
 * every button is disabled while anything holds the rig. Each button asks first, naming the rig.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class ToolsPage {

    /** The panel's address, which the page polls while a tool runs. */
    static final String PATH = "/fragments/tools";
    static final String PANEL_ID = "tools-panel";

    private ToolsPage() {}

    static String body(ToolsService tools) {
        return panel(tools);
    }

    /** The refusal or notice, the list of tools, and the log below. */
    static String panel(ToolsService tools) {
        Tool running = tools.running();
        String why = tools.refusal();
        StringBuilder out = new StringBuilder("<div id=\"" + PANEL_ID + "\" class=\"tools-panel\"");
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
        out.append("<section class=\"card\"><ul class=\"tool-list\">");
        out.append(row(tools, Tool.PAIR, "", Tool.PAIR.title, blocked, ""));
        out.append(onboardRow(tools, blocked));
        out.append(rebootC18Rows(tools, blocked));
        out.append(row(tools, Tool.REBOOT_COMMANDER, "", Tool.REBOOT_COMMANDER.title, blocked, ""));
        out.append("</ul></section>");
        out.append(logCard(tools));
        return out.append("</div>").toString();
    }

    private static String onboardRow(ToolsService tools, boolean blocked) {
        List<String> problems = tools.connectSettings().problems();
        String warning = problems.isEmpty() ? ""
                : "Set on the <a href=\"/run/config\">Config tab</a> first: " + esc(String.join("; ", problems)) + ".";
        return row(tools, Tool.ONBOARD, "", Tool.ONBOARD.title, blocked || !problems.isEmpty(), warning);
    }

    private static String rebootC18Rows(ToolsService tools, boolean blocked) {
        List<String> registers = tools.registers();
        if (registers.isEmpty()) {
            // No rig is already said above the list; a rig with no readable register is said here.
            String warning = tools.rigName().isEmpty() ? ""
                    : "Rig " + esc(tools.rigName()) + " names no register CAT can read.";
            return row(tools, Tool.REBOOT_C18, "", Tool.REBOOT_C18.title, true, warning);
        }
        StringBuilder rows = new StringBuilder();
        for (String register : registers) {
            String label = registers.size() == 1 ? Tool.REBOOT_C18.title
                    : Tool.REBOOT_C18.title + " (" + register + ")";
            rows.append(row(tools, Tool.REBOOT_C18, register, label, blocked, ""));
        }
        return rows.toString();
    }

    /**
     * One row: the tool's button, its last outcome, and a setup warning ({@code warningHtml}, already escaped), if any.
     * Reboot C18's outcome shows on the row of the register it rebooted.
     */
    private static String row(ToolsService tools, Tool tool, String register, String label, boolean disabled,
            String warningHtml) {
        StringBuilder out = new StringBuilder("<li id=\"tool-" + tool.slug
                + (register.isEmpty() ? "" : "-" + esc(register)) + "\">");
        out.append(button(tools, tool, register, label, disabled));
        Outcome outcome = tools.outcome(tool);
        if (!outcome.state().isEmpty() && (register.isEmpty() || outcome.message().contains("(" + register + ")"))) {
            out.append("<span role=\"status\">").append(badge(outcome.state())).append(" ")
                    .append(esc(outcome.message())).append("</span>");
        }
        if (!warningHtml.isEmpty()) {
            out.append("<span class=\"warn\">").append(warningHtml).append("</span>");
        }
        return out.append("</li>").toString();
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
        return "<section class=\"card\" id=\"tools-log\"><h2>Log</h2><pre class=\"cat-pre\">"
                + esc(String.join("\n", lines)) + "</pre></section>";
    }
}
