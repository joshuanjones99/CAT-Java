package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.Map;

/**
 * Install Upgrade, ported from the Python CAT's {@code cat/web/pages/upgrade.py}: the Downloads pickers
 * (shared with Config), a summary of what Start will do, Start, and the stages as they go.
 *
 * <p>Start asks first, naming the rig and the build, because the PSI install reinstalls the Commander.
 * The panel is one polled fragment, so the stages and the Start button follow the install.
 *
 * <p>Not built yet, named on the page: Stop (no cancel in {@code NewInstall}), AU, VHQ, a Simulator
 * Build, and psi-init.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class UpgradePage {

    /** The panel's address, which the page polls while an install is going. */
    static final String PATH = "/fragments/upgrade";
    static final String PANEL_ID = "upgrade-panel";

    private static final Map<String, String> STAGE_WORDS = Map.of(
            "download", "Download the Commander Build",
            "upgrade", "Install it (PSI)",
            "psi-init", "psi-init --no-pairing (after PSI)",
            "simulators", "Replace the simulators");

    private UpgradePage() {}

    /** The page body: the Downloads pickers, then the Install Upgrade panel. */
    static String body(DownloadsService downloads, UpgradeService upgrades) {
        return "<p class=\"muted\">Install a Commander build without running a test. These pickers are the "
                + "Config tab's: a change here is a change there.</p>"
                + DownloadsPanel.fragment(downloads)
                + panel(downloads, upgrades);
    }

    /** The Install Upgrade panel: the summary, what blocks Start, Start, and the stages. */
    static String panel(DownloadsService downloads, UpgradeService upgrades) {
        DownloadsService.Choice choice = downloads.choice();
        boolean busy = !downloads.busy().isEmpty();
        String why = upgrades.refusal();
        StringBuilder out = new StringBuilder("<section id=\"" + PANEL_ID + "\" class=\"card wide\"");
        if (busy) {
            out.append(" hx-get=\"").append(PATH).append("\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\"");
        }
        out.append(">");
        out.append("<h2>What Start will do</h2>");
        out.append("<pre class=\"cat-pre\">").append(esc(upgrades.summary(choice))).append("</pre>");
        if (!why.isEmpty()) {
            out.append("<p class=\"warn\">Cannot start yet: ").append(esc(why)).append("</p>");
        }
        String notice = upgrades.notice();
        if (!notice.isEmpty()) {
            out.append("<p class=\"warn\" role=\"status\">").append(esc(notice)).append("</p>");
        }
        out.append("<div class=\"sim-actions\">");
        out.append("<button type=\"button\" hx-post=\"/upgrade/start\" hx-confirm=\"")
                .append(esc(upgrades.confirmText(choice))).append("\" hx-target=\"#" + PANEL_ID + "\"")
                .append(" hx-swap=\"outerHTML\"").append(why.isEmpty() ? "" : " disabled").append(">")
                .append("Start upgrade</button>");
        out.append("</div>");

        out.append("<h3>Stages</h3><ul class=\"stages\">");
        for (UpgradeService.Stage stage : upgrades.stages()) {
            out.append("<li><span class=\"stage-mark\" aria-hidden=\"true\">").append(mark(stage.status()))
                    .append("</span> ").append(esc(STAGE_WORDS.get(stage.name()))).append(": ")
                    .append(esc(stage.status().isEmpty() ? "waiting" : stage.status()));
            if (!stage.detail().isEmpty()) {
                out.append(" &mdash; ").append(esc(stage.detail()));
            }
            out.append("</li>");
        }
        out.append("</ul>");
        String message = downloads.job().message();
        if (!message.isEmpty() && !downloads.job().state().isEmpty()) {
            out.append("<p class=\"muted\" role=\"status\">").append(esc(message)).append("</p>");
        }
        return out.append("</section>").toString();
    }

    /** The stage's mark: a dot waiting, a turning arrow running, a tick done, a cross failed. */
    private static String mark(String status) {
        return switch (status) {
            case "running" -> "&#8635;";
            case "done" -> "&#10003;";
            case "failed" -> "&#10006;";
            case "skipped" -> "-";
            case "not built" -> "&hellip;";
            default -> "&middot;";
        };
    }
}
