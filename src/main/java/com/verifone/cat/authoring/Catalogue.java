package com.verifone.cat.authoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The step library as text, Markdown and JSON: what an author, a reviewer and the drafting model read. Port of
 * {@code petrotest.authoring.catalogue}.
 *
 * <p>{@code cat-java steps list} prints it; the drafting prompt reads {@link #renderJson}. All of it comes from the same
 * {@link StepDef}s the checker binds, so the catalogue cannot list a phrase that does not run. The texts are the
 * Python's, byte for byte ({@code CatalogueParityTest}).
 */
public final class Catalogue {

    /** What each argument type accepts, in the Python's {@code TYPE_HELP} order. */
    public static final Map<String, String> TYPE_HELP = typeHelp();

    private Catalogue() {}

    private static Map<String, String> typeHelp() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("Money", "a sum with two decimals: 11.00, $11.00, 09.50, .50 (keyed digit for digit)");
        out.put("Number", "digits; leading zeros are kept");
        out.put("Hex", "a POS key code like 0x0c");
        out.put("MopKey", "CASH, CREDIT, OTHER MOP, or a key code like 0x3e");
        out.put("DcrKey", "a DCR soft key: L1-L4 or R1-R4");
        out.put("Duration", "a time like 90s, 500ms, 2m");
        out.put("Pad", "numeric, calculator or on-screen");
        out.put("PadKey", "OK, CANCEL or CLEAR");
        out.put("Field", "a PATS field's value between the double quotes; may be empty");
        out.put("Element", "a control by its petrotest.pos.locators name: ITEM_PANEL_N(145421)");
        out.put("PatsAction", "a PATS action's name: NETWORK_REPORT");
        out.put("SoftKey", "a DCR soft key action: L1-L4 or R1-R4");
        out.put("Text", "any text, written between the double quotes");
        return java.util.Collections.unmodifiableMap(out);
    }

    /** One plain map per phrase, in catalogue order (by group, then library order). */
    public static List<Map<String, Object>> entries(List<StepDef> steps) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> StepDef.GROUPS.indexOf(steps.get(i).group()))
                .thenComparingInt(i -> i));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i : order) {
            StepDef d = steps.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("phrase", d.display());
            entry.put("group", d.group());
            entry.put("kind", d.kind());
            entry.put("level", d.level());
            entry.put("arguments", new LinkedHashMap<>(d.types()));
            entry.put("example", d.example());
            entry.put("does", d.doc());
            entry.put("uses", new ArrayList<>(d.fixtures()));
            out.add(entry);
        }
        return out;
    }

    /** {@link #entries(List)} over the whole library. */
    public static List<Map<String, Object>> entries() {
        return entries(StepLibrary.steps());
    }

    private static List<String> typesUsed(List<StepDef> steps) {
        Set<String> used = new LinkedHashSet<>();
        steps.forEach(d -> used.addAll(d.types().values()));
        List<String> out = new ArrayList<>();
        for (String name : TYPE_HELP.keySet()) {
            if (used.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** The console catalogue: each group, each phrase with its example, then the argument legend. */
    public static String renderText(List<StepDef> steps) {
        List<String> lines = new ArrayList<>();
        String group = null;
        for (Map<String, Object> entry : entries(steps)) {
            if (!entry.get("group").equals(group)) {
                group = (String) entry.get("group");
                lines.add("");
                lines.add("== " + group + " ==");
            }
            String flag = switch ((String) entry.get("level")) {
                case "ui" -> "  [UI level]";
                case "pats" -> "  [PATS action]";
                default -> "";
            };
            lines.add("  " + entry.get("phrase") + flag);
            lines.add("      e.g. " + entry.get("example"));
        }
        lines.add("");
        lines.add("Arguments:");
        for (String name : typesUsed(steps)) {
            lines.add("  {" + name + "}: " + TYPE_HELP.get(name));
        }
        return stripLeadingNewlines(String.join("\n", lines)) + "\n";
    }

    public static String renderText() {
        return renderText(StepLibrary.steps());
    }

    /** The Python's {@code docs/step-catalogue.md}, generated: {@code steps list --format markdown}. */
    public static String renderMarkdown(List<StepDef> steps) {
        List<String> out = new ArrayList<>(List.of(
                "# Step catalogue",
                "",
                "<!-- Generated: `petrotest steps list --format markdown --out docs/step-catalogue.md`.",
                "     Do not edit by hand:",
                "     tests/unit/test_authoring_catalogue.py fails when this file is stale. -->",
                "",
                "Every phrase an authored case (`PETROCORE-<n>-TestCase.feature`) may use. Any",
                "keyword works with any phrase — Given, When, Then, And, But. A line that is not",
                "here is refused by `petrotest steps check` before it can reach a rig.",
                "See `docs/authoring.md` for how to write a case.",
                "",
                "| Argument | Accepts |",
                "|---|---|"));
        for (String name : typesUsed(steps)) {
            out.add("| `{" + name + "}` | " + TYPE_HELP.get(name) + " |");
        }
        String group = null;
        for (Map<String, Object> entry : entries(steps)) {
            if (!entry.get("group").equals(group)) {
                group = (String) entry.get("group");
                out.addAll(List.of("", "## " + group, "", "| Phrase | What it does | Example |", "|---|---|---|"));
            }
            String phrase = ((String) entry.get("phrase")).replace("|", "\\|");
            String does = ((String) entry.get("does")).replace("|", "\\|");
            if ("ui".equals(entry.get("level"))) {
                does = "**UI level — warned, not publishable.** " + does;
            } else if ("pats".equals(entry.get("level"))) {
                does = "**PATS action, for migrated cases — warned, not publishable.** " + does;
            }
            String example = ((String) entry.get("example")).replace("|", "\\|");
            out.add("| `" + phrase + "` | " + does + " | `" + example + "` |");
        }
        return String.join("\n", out) + "\n";
    }

    public static String renderMarkdown() {
        return renderMarkdown(StepLibrary.steps());
    }

    /** The legend and the entries as JSON, as {@code json.dumps(indent=2, ensure_ascii=False)} writes them. */
    public static String renderJson(List<StepDef> steps) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String name : typesUsed(steps)) {
            arguments.put(name, TYPE_HELP.get(name));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arguments", arguments);
        payload.put("steps", entries(steps));
        return PyJson.indented(payload, 2, false) + "\n";
    }

    public static String renderJson() {
        return renderJson(StepLibrary.steps());
    }

    private static String stripLeadingNewlines(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '\n') {
            start++;
        }
        return text.substring(start);
    }
}
