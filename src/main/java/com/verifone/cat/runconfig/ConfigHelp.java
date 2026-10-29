package com.verifone.cat.runconfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Config tab's fixed choices and help text: the Python CAT's {@code cat.panels.config.options} ({@code _HELP},
 * {@code _EXEC_OPTIONS}, {@code _IFSF_OPTIONS}), word for word. The page shows a control's help as its label's
 * tooltip.
 */
public final class ConfigHelp {

    /** {@code (label, value)}: dev runs the suites; BDD is recorded, not wired. */
    public static final List<Map.Entry<String, String>> EXEC_OPTIONS = List.of(
            Map.entry("dev", "dev"), Map.entry("BDD (recorded, not wired)", "BDD"));

    /** {@code (label, value)}: a legacy Java runner flag, recorded, not wired. */
    public static final List<Map.Entry<String, String>> IFSF_OPTIONS = List.of(
            Map.entry("(none)", ""), Map.entry("-ifsfPOS", "-ifsfPOS"), Map.entry("-ifsfEPS", "-ifsfEPS"),
            Map.entry("-ifsfAuth", "-ifsfAuth"));

    /** Each control's help, by the Python's control id. */
    public static final Map<String, String> HELP = help();

    private ConfigHelp() {}

    private static Map<String, String> help() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("config-plans", "The Confluence Automated-Features catalogue, read from the cached copy; Refresh is"
                + " the one control that reaches Confluence. A plan runs the migrated tests its Jira"
                + " cases name; cases with no migrated test are counted as Not Run.");
        out.put("config-dist", "The primary FEP: one of the dist folders the Commander Build above holds, listed"
                + " once that build has been read. With Don't download, one the builds already in"
                + " C:/CICD/Builds hold. A dist the build does not hold is marked, and an AU or PSI"
                + " install will not start on it.");
        out.put("config-tp-branch", "The petroapps branch the run fetches and pulls tests from.");
        out.put("config-upgrade", "Skip tests the build already installed. AU pushes Auto_Upgrade.jar; PSI installs"
                + " fresh with Petro_Suite_Installer.jar; VHQ is not implemented yet.");
        out.put("config-exec", "dev runs the pytest suites. BDD is recorded, not wired.");
        out.put("config-ifsf", "Recorded, not wired - a legacy Java runner flag.");
        out.put("config-coverage", "On: step 4 gets the JaCoCo agent loaded on the Commander and every register"
                + " (rebooting one that has the jar but no agent running); step 6 dumps each Tomcat,"
                + " reboots the Commander so its other JVMs write their data, builds per-JVM reports"
                + " against the build's classes, and attaches them to the run's Xray execution (or"
                + " test plan) in Jira. Adds a Commander reboot to the run. Off by default.");
        out.put("config-cmd-build", "The Commander build to install: a branch of RMS_Nightly_CLW/Petro (Solutions,"
                + " master, a release line, a PETROCORE ticket), then one of its builds, newest first."
                + " With AU or PSI, step 4 downloads it and installs from it; a viper-only build is"
                + " merged onto a foundation build first. Don't download installs the newest build"
                + " already in C:/CICD/Builds.");
        out.put("config-fnd-build", "Only for a viper-only (SOS) Commander Build that no foundation build pairs with by"
                + " exe name and build time: the foundation build to merge it onto, whose overlay and"
                + " installer jars are used. Left on the paired foundation otherwise.");
        out.put("config-sim-build", "A branch and build whose simulators/ REPLACE the staged simulators completely,"
                + " config files and all, in System Checks, before any is started. A running one is"
                + " refused. Solutions builds carry no simulators. Don't download keeps the staged ones.");
        out.put("config-xray", "On: a separate process creates an Xray Test Execution for each plan, updates every"
                + " case as it finishes, and writes a plain-English summary on the execution when the"
                + " run ends. It never slows or fails the run. Always off for a fake POS run.");
        out.put("config-cc-onboard", "On: step 4 sets the Lab, Email, Password and Service ID below on the Commander."
                + " Off: Commander Connect is left as it is.");
        out.put("config-cc-lab", "Which Commander Connect environment the Commander is onboarded to. A change restarts"
                + " coresvcs. QAT and DEV need a test-signed Commander.");
        out.put("config-cc-email", "The Commander Connect account email.");
        out.put("config-cc-password", "Saved with the rest of the form. Leave empty to read it from "
                + "$COMMANDER_CONNECT_PASSWORD.");
        out.put("config-cc-service-id", "The Commander's Service ID: 7 to 20 digits.");
        out.put("config-tags", "A Cucumber tag expression, as PATS's cucumber.filter.tags: @tag, not, and, or,"
                + " brackets; commas AND their parts. Beside it, how many cases it matches and their"
                + " newest recorded run time on the rig, where the Results tab has one.");
        return java.util.Collections.unmodifiableMap(out);
    }
}
