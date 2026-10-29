package com.verifone.cat.runconfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.cat.results.RunIndex;
import com.verifone.cat.results.RunSummary;

/**
 * The Tags count's run-time estimate: the Python CAT's {@code recorded_durations}, {@code duration_words},
 * {@code time_words} and {@code count_markup} ({@code cat.panels.config.scope}).
 *
 * <p>Each case file's newest recorded run time on the rig, from the Results index: the newest rig run that ran the
 * file, its tests' durations summed (a feature of several scenarios is one case). Fake-POS and no-rig sessions are
 * not a rig's time. Read once per index file modification; an index that will not read is no estimate.
 */
public final class RecordedDurations {

    private final Path index;
    private long stamp = Long.MIN_VALUE;
    private Map<String, Double> durations = Map.of();

    public RecordedDurations(Path index) {
        this.index = index;
    }

    /** Node (the case file, without {@code ::}) to seconds; empty with no index. */
    public synchronized Map<String, Double> read() {
        if (index == null) {
            return Map.of();
        }
        long modified;
        try {
            modified = Files.getLastModifiedTime(index).toMillis();
        } catch (Exception e) {
            return Map.of();
        }
        if (modified == stamp) {
            return durations;
        }
        Map<String, Double> out = new HashMap<>();
        List<RunSummary> rows;
        try {
            rows = RunIndex.loadIndex(index);
        } catch (RuntimeException e) {
            rows = List.of();
        }
        for (RunSummary row : rows) { // newest first
            if (!"rig".equals(row.segment())) {
                continue;
            }
            Map<String, Double> run = new LinkedHashMap<>();
            for (RunSummary.TestRow test : row.tests()) {
                String status = test.status();
                if (("passed".equals(status) || "failed".equals(status) || "warned".equals(status))
                        && test.duration() > 0) {
                    String node = test.nodeid().split("::", 2)[0];
                    run.merge(node, test.duration(), Double::sum);
                }
            }
            run.forEach(out::putIfAbsent);
        }
        stamp = modified;
        durations = Map.copyOf(out);
        return durations;
    }

    /** {@code ~31 min}, {@code ~2 h 5 min}. */
    public static String durationWords(double seconds) {
        long minutes = Math.max(1, Math.round(seconds / 60));
        if (minutes < 60) {
            return "~" + minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? "~" + hours + " h" : "~" + hours + " h " + rest + " min";
    }

    /** The time beside the Tags count: {@code  · ~31 min}, {@code  · ~2 h (223 timed)} when only some were. */
    public static String timeWords(List<String> keys, Map<String, String> nodes, Map<String, Double> durations) {
        double total = 0;
        int known = 0;
        for (String key : keys) {
            String node = nodes.get(key);
            Double seconds = node == null ? null : durations.get(node);
            if (seconds != null) {
                total += seconds;
                known++;
            }
        }
        if (known == 0) {
            return "";
        }
        String words = durationWords(total);
        return known == keys.size() ? " · " + words : " · " + words + " (" + known + " timed)";
    }

    /** The Tags count's text: {@code 42 of 358 cases · ~31 min}, as the Python's {@code count_markup}. */
    public static String countText(RunScope scope, ScopeResolver.Result result, Map<String, String> nodes,
            Map<String, Double> durations) {
        if (result == null) {
            return "reading the cases…";
        }
        if (scope.tags().isBlank()) {
            return "type a tag expression, e.g. not @plan_end";
        }
        if (!result.tagError().isEmpty()) {
            return result.tagError();
        }
        String text = result.matched().size() + " of " + result.total() + " cases";
        text += timeWords(result.matched(), nodes, durations);
        if (scope.tagCases() != null) {
            text += " · " + result.chosen().size() + " ticked by hand";
        }
        return text;
    }
}
