package com.verifone.cat.runconfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.plans.PartialRun;

/**
 * The words the Run scope shows: the summary line, the warnings under the list, and the short
 * keys. Ported from {@code cat.panels.config.scope} ({@code summary_line}, {@code warning_lines},
 * {@code short_key}); the wording is the Python CAT's.
 */
public final class ScopeText {

    /** The warning sign that starts the partial-run warnings. */
    static final String WARNING = "⚠";

    private ScopeText() {}

    /** {@code PETROCORE-36978} to {@code 36978}, as the list and its warning write a case. */
    public static String shortKey(String key) {
        return key.replaceFirst("(?i)^PETROCORE-(\\d+)$", "$1");
    }

    /** The Run scope's line: {@code 44 of 358: tag @fuel and not @dcr, and 1 prerequisite}. */
    public static String summaryLine(RunScope scope, ScopeResolver.Result result) {
        String what;
        if (scope.mode() == RunScope.Mode.TAGS) {
            what = "tag " + result.expression();
            if (scope.tagCases() != null) {
                what += ", ticked by hand";
            }
        } else if (scope.mode() == RunScope.Mode.FROM) {
            what = "from " + shortKey(PartialRun.normaliseKey(scope.fromCase())) + " to the end";
        } else {
            what = keysWords(result.chosen());
        }
        if (!result.added().isEmpty()) {
            what += ", and " + plural(result.added().size(), "prerequisite");
        } else if (!result.leftOut().isEmpty()) {
            what += "; " + plural(result.leftOut().size(), "prerequisite") + " left out";
        }
        return result.runKeys().size() + " of " + result.total() + ": " + what;
    }

    /** The warnings under the list: a partial run, what it adds or leaves out, and why. */
    public static List<String> warningLines(RunScope scope, ScopeResolver.Result result) {
        String leftBy = scope.mode() == RunScope.Mode.TAGS ? "the filter" : "the selection";
        List<String> lines = new ArrayList<>();
        if (!result.added().isEmpty()) {
            int count = result.added().size();
            lines.add(WARNING + " A partial run: " + plural(count, "case") + " " + leftBy + " left out "
                    + (count == 1 ? "is" : "are") + " added (" + groups(result.added()) + "). "
                    + "p: leave prerequisites out");
        } else if (!result.leftOut().isEmpty()) {
            int count = result.leftOut().size();
            lines.add(WARNING + " A partial run: " + plural(count, "case") + " " + leftBy + " left out "
                    + (count == 1 ? "changes" : "change") + " rig state for the cases after "
                    + (count == 1 ? "it" : "them") + " and " + (count == 1 ? "does" : "do")
                    + " not run (" + groups(result.leftOut()) + "). p: add prerequisites");
        } else {
            lines.add(WARNING + " A partial run: " + result.runKeys().size() + " of " + result.total()
                    + " cases; no case " + leftBy + " left out ahead of them changes rig state that CAT can see.");
        }
        if (result.unreadable() > 0) {
            int count = result.unreadable();
            lines.add(plural(count, "case") + " left out ahead of the selection "
                    + (count == 1 ? "has" : "have") + " no PATS actions CAT can read; "
                    + "what they change is not known.");
        }
        if (!result.planEnd().isEmpty()) {
            int count = result.planEnd().size();
            lines.add(keysWords(result.planEnd()) + " " + (count == 1 ? "is a" : "are") + " @plan_end "
                    + (count == 1 ? "case" : "cases") + ": judged only in a whole run, so this run skips "
                    + (count == 1 ? "it" : "them") + ".");
        }
        return lines;
    }

    static String plural(int count, String word) {
        return count + " " + word + (count == 1 ? "" : "s");
    }

    static String keysWords(List<String> keys) {
        int limit = 4;
        List<String> shown = keys.stream().limit(limit).map(ScopeText::shortKey).toList();
        String words = String.join(", ", shown);
        return keys.size() <= limit ? words : words + " and " + (keys.size() - limit) + " more";
    }

    /** {@code 79588, 79590, before 36847; 36977, before 36978}: each by the case it precedes. */
    static String groups(List<PartialRun.Prerequisite> prerequisites) {
        int limit = 3;
        Map<String, List<String>> byNeed = new LinkedHashMap<>();
        for (PartialRun.Prerequisite case_ : prerequisites) {
            byNeed.computeIfAbsent(case_.neededBy(), k -> new ArrayList<>()).add(shortKey(case_.key()));
        }
        List<String> words = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, List<String>> group : byNeed.entrySet()) {
            if (index++ == limit) {
                break;
            }
            words.add(String.join(", ", group.getValue()) + ", before " + shortKey(group.getKey()));
        }
        String joined = String.join("; ", words);
        return byNeed.size() > limit ? joined + "; …" : joined;
    }
}
