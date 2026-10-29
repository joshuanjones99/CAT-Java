package com.verifone.cat.runservice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.verifone.cat.report.ReportPaths;

/**
 * The Validations view's words and groups: the Python CAT's {@code cat.panels.validations} ({@code KIND_LABELS},
 * {@code group_by_case}, the counts) and the web view's tabs and diff colours ({@code cat.web.components}:
 * {@code VALIDATION_TABS}, {@code _validation_tab}, {@code _diff_class}).
 *
 * <p>Every receipt, TLog, report and packet check a test case made, grouped by case in the order the case's first
 * verdict came, each in order. Receipts, TLog and Packets have a tab each; reports, payloads and overlays share
 * Other, shown only when a run made one.
 */
public final class Validations {

    /** How each kind reads in its row. */
    public static final Map<String, String> KIND_LABELS = Map.of(
            "receipt", "Receipt",
            "tlog", "TLog",
            "report", "Report",
            "packets", "Packets",
            "payload", "Payload",
            "overlay", "Overlay");

    /** One tab of the view: its id, its label and the kinds it holds (Other holds the rest). */
    public record Tab(String id, String label, List<String> kinds) {}

    /** The view's tabs, in order. */
    public static final List<Tab> TABS = List.of(
            new Tab("receipt", "Receipts", List.of("receipt")),
            new Tab("tlog", "TLog", List.of("tlog")),
            new Tab("packets", "Packets", List.of("packets")),
            new Tab("other", "Other", List.of()));

    private Validations() {}

    /** The tab a kind is shown under. */
    public static String tabOf(String kind) {
        for (Tab tab : TABS) {
            if (tab.kinds().contains(kind)) {
                return tab.id();
            }
        }
        return "other";
    }

    /** The kind as its row reads it: its label, else the word capitalised, else "Check". */
    public static String kindLabel(String kind) {
        String label = KIND_LABELS.get(kind);
        if (label != null) {
            return label;
        }
        if (kind == null || kind.isEmpty()) {
            return "Check";
        }
        return kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The case a verdict belongs to: its key, else its test's, else its node id. */
    public static String caseOf(RunState.Validation validation) {
        if (!validation.caseKey().isEmpty()) {
            return validation.caseKey();
        }
        String key = ReportPaths.caseKey(validation.nodeid());
        return key.isEmpty() ? validation.nodeid() : key;
    }

    /** The verdicts by case, cases in the order their first verdict came, each in order. */
    public static Map<String, List<RunState.Validation>> groupByCase(List<RunState.Validation> validations) {
        Map<String, List<RunState.Validation>> groups = new LinkedHashMap<>();
        for (RunState.Validation validation : validations) {
            groups.computeIfAbsent(caseOf(validation), k -> new ArrayList<>()).add(validation);
        }
        return groups;
    }

    /** The validations of one tab, in order. */
    public static List<RunState.Validation> ofTab(List<RunState.Validation> validations, String tab) {
        List<RunState.Validation> out = new ArrayList<>();
        for (RunState.Validation validation : validations) {
            if (tabOf(validation.kind()).equals(tab)) {
                out.add(validation);
            }
        }
        return out;
    }

    /** {@code 3 validations, 1 failed} or {@code 1 validation, all passed}. */
    public static String counts(List<RunState.Validation> validations) {
        long failed = validations.stream().filter(v -> !v.passed()).count();
        int total = validations.size();
        String noun = total == 1 ? "validation" : "validations";
        return total + " " + noun + (failed > 0 ? ", " + failed + " failed" : ", all passed");
    }

    /** The tab the reader is shown first: the first failed validation's, else Receipts. */
    public static String firstTab(List<RunState.Validation> validations) {
        for (RunState.Validation validation : validations) {
            if (!validation.passed()) {
                return tabOf(validation.kind());
            }
        }
        return "receipt";
    }

    /** A diff line's colour, as the TUI colours it: a header bold, removed red, added green, a hunk blue. */
    public static String diffClass(String line) {
        if (line.startsWith("---") || line.startsWith("+++")) {
            return "head";
        }
        if (line.startsWith("-")) {
            return "removed";
        }
        if (line.startsWith("+")) {
            return "added";
        }
        if (line.startsWith("@@")) {
            return "hunk";
        }
        return "";
    }
}
