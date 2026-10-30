package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.build.BuildCatalogue;
import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.launch.UpgradeMode;

/**
 * The Downloads pickers, ported from the Python CAT's {@code cat/web/pages/downloads_ui.py}: Commander
 * Build (branch, then build, or this PC's builds under Don't download), Distribution, Upgrade mode,
 * Simulator Build, Refresh, and the Download button.
 *
 * <p>A fragment, not a page: the Config tab includes it at {@link #PATH} and the Install Upgrade page
 * renders it inline. Every picker posts to {@code /downloads/set} and gets this panel back whole, so
 * the choice lives on the server and both pages show it. The panel polls itself only while a listing
 * or a download is going.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class DownloadsPanel {

    /** The fragment's address, which the Config tab includes and the panel polls. */
    static final String PATH = "/fragments/downloads";
    static final String PANEL_ID = "downloads-panel";

    private static final String TARGET = "hx-target=\"#" + PANEL_ID + "\" hx-swap=\"outerHTML\"";

    private DownloadsPanel() {}

    /** The panel as a fragment: pickers, hints, the Download button, and the download's progress. */
    static String fragment(DownloadsService d) {
        DownloadsService.Choice c = d.choice();
        boolean busy = !d.busy().isEmpty();
        boolean live = busy || d.listingPending();
        StringBuilder out = new StringBuilder("<section id=\"" + PANEL_ID + "\" class=\"card wide downloads\"");
        if (live) {
            out.append(" hx-get=\"").append(PATH).append("\" hx-trigger=\"every 2s\" hx-swap=\"outerHTML\"");
        }
        out.append(">");
        out.append("<h2>Downloads</h2>");
        out.append("<p class=\"muted\">The choice Config and Install Upgrade share. Listings are read from "
                + "Artifactory; a download fetches the Commander Build's files for the Upgrade mode into ")
                .append(esc(d.root())).append(".</p>");

        out.append("<div class=\"pick-grid\">");
        commanderRow(out, d, c, busy);
        distRow(out, d, c, busy);
        if (d.foundationShown()) {
            foundationRow(out, d, c, busy);
        }
        modeRow(out, c, busy);
        simulatorRow(out, d, c, busy);
        out.append("</div>");

        out.append("<div class=\"sim-actions\">");
        out.append("<button type=\"button\" class=\"ghost\" hx-post=\"/downloads/refresh\" ").append(TARGET)
                .append(busy ? " disabled" : "").append(">Refresh listings</button>");
        boolean canFetch = c.commanderChosen() && !busy && c.mode() != UpgradeMode.VHQ;
        out.append("<button type=\"button\" hx-post=\"/downloads/fetch\" ").append(TARGET)
                .append(canFetch ? "" : " disabled").append(">Download Commander Build</button>");
        out.append("</div>");

        out.append(status(d));
        out.append(jobView(d.job(), busy));
        return out.append("</section>").toString();
    }

    /** The listing status and the last refusal, as one line each. */
    private static String status(DownloadsService d) {
        StringBuilder out = new StringBuilder();
        String branches = d.error(DownloadsService.BRANCHES);
        if (!branches.isEmpty()) {
            out.append("<p class=\"warn\">Branches: ").append(esc(branches)).append("</p>");
        } else if (d.branches() == null) {
            out.append("<p class=\"muted\">Listing branches…</p>");
        } else {
            out.append("<p class=\"muted\">").append(d.branches().size())
                    .append(" branches on Artifactory (Refresh listings asks again).</p>");
        }
        String notice = d.notice();
        if (!notice.isEmpty()) {
            out.append("<p class=\"warn\" role=\"status\">").append(esc(notice)).append("</p>");
        }
        return out.toString();
    }

    /** The job's state, message, transfers and last log lines; "" when no action has run. */
    static String jobView(JobLog job, boolean busy) {
        String state = job.state();
        if (state.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<div class=\"job\" role=\"status\">");
        out.append("<p class=\"").append("failed".equals(state) ? "warn" : "muted").append("\">")
                .append(esc(job.message())).append("</p>");
        for (JobLog.Transfer file : job.transfers()) {
            long got = file.received();
            long pct = file.expected() <= 0 ? 100 : Math.min(100, got * 100 / file.expected());
            out.append("<p class=\"muted\">").append(esc(file.name())).append(": ")
                    .append(got).append(" of ").append(file.expected()).append(" bytes (")
                    .append(pct).append("%)</p>");
        }
        List<String> lines = job.lines();
        if (!lines.isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (String line : lines) {
                text.append(esc(line)).append('\n');
            }
            out.append("<pre class=\"sim-log-pre\">").append(text).append("</pre>");
        }
        if (busy) {
            out.append("<p class=\"muted\">Working…</p>");
        }
        return out.append("</div>").toString();
    }

    private static void commanderRow(StringBuilder out, DownloadsService d, DownloadsService.Choice c,
            boolean busy) {
        String disabled = busy ? " disabled" : "";
        out.append("<div class=\"pick\"><span class=\"pick-label\">Commander Build</span>");
        out.append("<div class=\"pick-controls\">");
        out.append("<select name=\"value\" aria-label=\"Commander Build branch\" hx-post=\"")
                .append(set("commanderBranch")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(disabled).append(">");
        out.append(option("", "Don't download", c.commanderBranch().isEmpty()));
        List<String> branches = d.branches();
        if (branches != null) {
            for (String name : branches) {
                out.append(option(name, name, name.equals(c.commanderBranch())));
            }
        }
        if (!c.commanderBranch().isEmpty() && (branches == null || !branches.contains(c.commanderBranch()))) {
            out.append(option(c.commanderBranch(), c.commanderBranch() + " (saved; not listed)", true));
        }
        out.append("</select>");

        out.append("<select name=\"value\" aria-label=\"Commander Build number\" hx-post=\"")
                .append(set("commanderBuild")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(disabled).append(">");
        if (c.commanderBranch().isEmpty()) {
            out.append(option(DownloadsService.NEWEST_LOCAL, "Newest on this PC",
                    c.localBuild().isEmpty()));
            List<Path> local = d.localBuilds(c.mode(), c.dist());
            for (Path build : local) {
                String name = build.getParent().getFileName().toString();
                String built = BuildDiscovery.buildDate(build);
                out.append(option(name, built.isEmpty() ? name : name + "   " + built.substring(0, 10),
                        name.equals(c.localBuild())));
            }
            if (!c.localBuild().isEmpty() && local.stream()
                    .noneMatch(p -> c.localBuild().equals(p.getParent().getFileName().toString()))) {
                out.append(option(c.localBuild(), c.localBuild() + " (not on this PC for this mode and dist)",
                        true));
            }
        } else {
            List<BuildCatalogue.NightlyBuild> builds = d.builds(c.commanderBranch());
            if (builds == null) {
                if (!c.commanderBuild().isEmpty()) {
                    out.append(option(c.commanderBuild(), c.commanderBuild() + " (saved; not listed yet)", true));
                }
            } else {
                for (BuildCatalogue.NightlyBuild build : builds) {
                    String created = build.created().length() >= 10 ? build.created().substring(0, 10) : "";
                    out.append(option(build.build(), build.build() + (created.isEmpty() ? "" : "   " + created),
                            build.build().equals(c.commanderBuild())));
                }
            }
        }
        out.append("</select></div>");
        out.append("<p class=\"muted pick-hint\">").append(esc(commanderHint(d, c))).append("</p></div>");
    }

    private static String commanderHint(DownloadsService d, DownloadsService.Choice c) {
        if (c.commanderBranch().isEmpty()) {
            return existingBuildMeaning(d, c);
        }
        if (c.commanderBuild().isEmpty()) {
            return "Pick a build of " + c.commanderBranch() + ".";
        }
        String key = DownloadsService.distsKey(c.commanderBranch(), c.commanderBuild());
        String error = d.error(key);
        if (!error.isEmpty()) {
            return c.commanderBranch() + " " + c.commanderBuild() + ": " + error;
        }
        List<String> dists = d.dists(c.commanderBranch(), c.commanderBuild());
        if (dists == null) {
            return "Reading " + c.commanderBranch() + " " + c.commanderBuild() + "…";
        }
        boolean holds = dists.stream().anyMatch(name -> name.equalsIgnoreCase(c.dist()));
        if (!holds) {
            return c.commanderBranch() + " " + c.commanderBuild() + " holds no " + c.dist()
                    + " folder: pick one of " + String.join(", ", dists) + ", or another build.";
        }
        return c.commanderBranch() + " " + c.commanderBuild() + " holds " + c.dist()
                + ". Download fetches its overlay.zip" + jarsSentence(c.mode()) + ".";
    }

    /** "AU" or "PSI": how the mode installs, in the Python's words ({@code _HOW}). */
    private static String how(UpgradeMode mode) {
        return mode == UpgradeMode.CLASSIC ? "AU" : "PSI";
    }

    /**
     * The Commander Build's hint under Don't download: the Python's {@code _existing_build_meaning}, word for word
     * where the port has the facts (this PC's builds under the builds root).
     */
    static String existingBuildMeaning(DownloadsService d, DownloadsService.Choice c) {
        String dist = c.dist();
        Path root = d.root();
        String folder = root + java.io.File.separator + "<build>" + java.io.File.separator + dist
                + java.io.File.separator;
        List<String> needs = new ArrayList<>(List.of(DownloadsService.OVERLAY));
        needs.addAll(DownloadsService.jarsFor(c.mode()));
        String needed = String.join(" and ", needs);
        String skipped = c.mode() == UpgradeMode.SKIP ? "Upgrade mode is Skip" : "VHQ";
        if (!c.localBuild().isEmpty()) {
            Path where = root.resolve(c.localBuild()).resolve(dist);
            if (c.installing()) {
                return how(c.mode()) + " installs " + where + ", already on this PC.";
            }
            return skipped + ", so nothing is installed. AU or PSI would install " + where + ".";
        }
        String first = c.installing()
                ? how(c.mode()) + " installs the newest " + dist + " build already on this PC: CAT looks for "
                        + folder + " holding " + needed + "."
                : skipped + ", so nothing is installed. AU or PSI would install the newest " + dist
                        + " build already on this PC, from " + folder + ".";
        List<Path> local = d.localBuilds(c.mode(), dist);
        if (!local.isEmpty()) {
            Path found = local.get(0);
            String built = BuildDiscovery.buildDate(found);
            return first + "\nExisting build: " + found.getParent().getFileName()
                    + (built.isEmpty() ? "" : ", built " + built) + ".";
        }
        if (!java.nio.file.Files.isDirectory(root)) {
            return first + "\nExisting build: none, " + root + " does not exist. Pick one to download.";
        }
        String nothing = "no " + dist + " folder there holds " + needed;
        return first + "\nExisting build: none, " + nothing + (c.installing() ? ". Pick one to download." : ".");
    }

    /** The Distribution's hint: the Python's {@code dist_hint}. */
    static String distHint(DownloadsService d, DownloadsService.Choice c) {
        String dist = c.dist();
        if (!c.commanderBranch().isEmpty()) {
            String where = c.commanderBranch() + " " + c.commanderBuild();
            if (c.commanderBuild().isEmpty()) {
                return "Listed once a build of " + c.commanderBranch() + " is picked.";
            }
            List<String> available = d.dists(c.commanderBranch(), c.commanderBuild());
            if (available == null) {
                return "Listed once " + where + " has been read.";
            }
            if (available.isEmpty()) {
                return where + " holds no dist folder: pick another build.";
            }
            boolean lacks = available.stream().noneMatch(name -> name.equalsIgnoreCase(dist));
            return "Offered from " + where + ": " + String.join(", ", available) + "."
                    + (lacks ? " It has no " + dist + ": pick one of these, or another build." : "");
        }
        List<String> available = d.localDists();
        if (available.isEmpty()) {
            return "No build in " + d.root() + " holds an overlay.zip: pick a Commander Build.";
        }
        boolean lacks = available.stream().noneMatch(name -> name.equalsIgnoreCase(dist));
        return "Offered from the builds already in " + d.root() + ": " + String.join(", ", available) + "."
                + (lacks ? " None of them has " + dist + "." : "");
    }

    /** The Simulator Build's hint: the Python's {@code _simulator_meaning} (the build's contents are not read). */
    static String simulatorMeaning(DownloadsService.Choice c) {
        if (c.simBranch().isEmpty()) {
            return "The staged simulators are used as they are.";
        }
        if (c.simBuild().isEmpty()) {
            return "Pick a build of " + c.simBranch() + ".";
        }
        return c.simBranch() + " " + c.simBuild() + "'s simulators replace the staged ones completely, config files "
                + "and all. A running simulator is refused: stop it first.";
    }

    /** The Foundation Build's hint: the Python's {@code _foundation_meaning}, without the build's contents. */
    static String foundationMeaning(DownloadsService.Choice c) {
        if (c.foundationBranch().isEmpty()) {
            return "Pick the foundation build this viper build is merged onto.";
        }
        if (c.foundationBuild().isEmpty()) {
            return "Pick a build of " + c.foundationBranch() + ".";
        }
        return c.foundationBranch() + " " + c.foundationBuild() + ": its overlay and installer jars are used";
    }

    private static void foundationRow(StringBuilder out, DownloadsService d, DownloadsService.Choice c,
            boolean busy) {
        String disabled = busy ? " disabled" : "";
        out.append("<div class=\"pick\"><span class=\"pick-label\">Foundation Build</span>");
        out.append("<div class=\"pick-controls\">");
        out.append("<select name=\"value\" aria-label=\"Foundation Build branch\" hx-post=\"")
                .append(set("foundationBranch")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(disabled).append(">");
        out.append(option("", "Pick a foundation", c.foundationBranch().isEmpty()));
        List<String> branches = d.branches();
        if (branches != null) {
            for (String name : branches) {
                out.append(option(name, name, name.equals(c.foundationBranch())));
            }
        }
        if (!c.foundationBranch().isEmpty() && (branches == null || !branches.contains(c.foundationBranch()))) {
            out.append(option(c.foundationBranch(), c.foundationBranch() + " (saved; not listed yet)", true));
        }
        out.append("</select>");
        out.append("<select name=\"value\" aria-label=\"Foundation Build number\" hx-post=\"")
                .append(set("foundationBuild")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(c.foundationBranch().isEmpty() ? " disabled" : disabled).append(">");
        out.append(option("", c.foundationBuild().isEmpty() ? "Pick a build" : c.foundationBuild(), true));
        List<BuildCatalogue.NightlyBuild> builds = c.foundationBranch().isEmpty() ? null
                : d.builds(c.foundationBranch());
        if (builds != null) {
            for (BuildCatalogue.NightlyBuild build : builds) {
                out.append(option(build.build(), build.build(), build.build().equals(c.foundationBuild())));
            }
        }
        out.append("</select></div>");
        out.append("<p class=\"muted pick-hint\">").append(esc(foundationMeaning(c))).append("</p></div>");
    }

    private static String jarsSentence(UpgradeMode mode) {
        List<String> jars = DownloadsService.jarsFor(mode);
        if (jars.isEmpty()) {
            return "";
        }
        return " and " + String.join(" and ", jars);
    }

    private static void distRow(StringBuilder out, DownloadsService d, DownloadsService.Choice c, boolean busy) {
        List<String> options = new ArrayList<>();
        if (c.commanderChosen()) {
            List<String> listed = d.dists(c.commanderBranch(), c.commanderBuild());
            if (listed != null) {
                options.addAll(listed);
            }
        } else {
            options.addAll(d.localDists());
        }
        if (options.stream().noneMatch(name -> name.equalsIgnoreCase(c.dist()))) {
            options.add(0, c.dist());
        }
        out.append("<div class=\"pick\"><span class=\"pick-label\">Distribution (FEP)</span>");
        out.append("<div class=\"pick-controls\"><select name=\"value\" aria-label=\"Distribution\" hx-post=\"")
                .append(set("dist")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(busy ? " disabled" : "").append(">");
        for (String name : options) {
            out.append(option(name, name, name.equals(c.dist())));
        }
        out.append("</select></div><p class=\"muted pick-hint\">").append(esc(distHint(d, c))).append("</p></div>");
    }

    private static void modeRow(StringBuilder out, DownloadsService.Choice c, boolean busy) {
        out.append("<div class=\"pick\"><span class=\"pick-label\">Upgrade mode</span>");
        out.append("<div class=\"pick-controls\"><select name=\"value\" aria-label=\"Upgrade mode\" hx-post=\"")
                .append(set("mode")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(busy ? " disabled" : "").append(">");
        for (UpgradeMode mode : UpgradeMode.values()) {
            out.append(option(mode.wire(), label(mode), mode == c.mode()));
        }
        out.append("</select></div><p class=\"muted pick-hint\">").append(esc(modeHint(c.mode())))
                .append("</p></div>");
    }

    private static void simulatorRow(StringBuilder out, DownloadsService d, DownloadsService.Choice c,
            boolean busy) {
        String disabled = busy ? " disabled" : "";
        out.append("<div class=\"pick\"><span class=\"pick-label\">Simulator Build</span>");
        out.append("<div class=\"pick-controls\">");
        out.append("<select name=\"value\" aria-label=\"Simulator Build branch\" hx-post=\"")
                .append(set("simBranch")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(disabled).append(">");
        out.append(option("", "Don't download", c.simBranch().isEmpty()));
        List<String> branches = d.branches();
        if (branches != null) {
            for (String name : branches) {
                out.append(option(name, name, name.equals(c.simBranch())));
            }
        }
        out.append("</select>");
        out.append("<select name=\"value\" aria-label=\"Simulator Build number\" hx-post=\"")
                .append(set("simBuild")).append("\" hx-trigger=\"change\" ").append(TARGET)
                .append(c.simChosen() ? disabled : " disabled").append(">");
        List<BuildCatalogue.NightlyBuild> builds = c.simChosen() ? d.builds(c.simBranch()) : null;
        out.append(option("", c.simBuild().isEmpty() ? "Pick a build" : c.simBuild(), true));
        if (builds != null) {
            for (BuildCatalogue.NightlyBuild build : builds) {
                out.append(option(build.build(), build.build(), build.build().equals(c.simBuild())));
            }
        }
        out.append("</select></div>");
        String hint = simulatorMeaning(c);
        out.append("<p class=\"muted pick-hint\">").append(esc(hint)).append("</p></div>");
    }

    /** The Upgrade mode's own words, as the Python's Config and Install Upgrade show them. */
    static String modeHint(UpgradeMode mode) {
        return switch (mode) {
            case SKIP -> "Skip: nothing is installed.";
            case CLASSIC -> "AU: pushes the build with Auto_Upgrade.jar.";
            case VHQ -> "VHQ: cloud deployment. Not implemented (petrotest/upgrade/vhq.py).";
            case NEW_INSTALL -> "PSI: a fresh install with Petro_Suite_Installer.jar and Auto_Upgrade.jar.";
        };
    }

    /** The mode's label, as the Python's Config tab words it. */
    static String label(UpgradeMode mode) {
        return switch (mode) {
            case SKIP -> "Skip - do not upgrade, test current build";
            case CLASSIC -> "AU - local Auto_Upgrade.jar push";
            case VHQ -> "VHQ - cloud deployment (not implemented)";
            case NEW_INSTALL -> "PSI - fresh install via Petro_Suite_Installer.jar";
        };
    }

    /** The POST address for one choice field. The field names are fixed here, never typed by the page. */
    static String set(String field) {
        return "/downloads/set?field=" + URLEncoder.encode(field, StandardCharsets.UTF_8);
    }

    private static String option(String value, String text, boolean selected) {
        return "<option value=\"" + esc(value) + "\"" + (selected ? " selected" : "") + ">" + esc(text) + "</option>";
    }
}
