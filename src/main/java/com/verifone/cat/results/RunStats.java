package com.verifone.cat.results;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Results statistics that are honest: the Python CAT's {@code cat.results.stats}.
 *
 * <p>A single pass rate over every run on disk would be a lie: the population mixes rig
 * runs, fake-POS runs, developer sessions with no rig, cancelled runs, runs that never
 * finished, and unmigrated placeholders that can never pass. So this segments before it
 * aggregates and states every exclusion as a number the page prints.
 *
 * <ul>
 *   <li>one segment at a time: fake POS and no rig are never folded into a rig figure;</li>
 *   <li>a cancelled run is not a failed one, and an incomplete run gets no verdict; both are
 *       counted and left out;</li>
 *   <li>a test whose file is an unmigrated placeholder is left out of every rate.</li>
 * </ul>
 *
 * <p>"Flaky" here means both passed and failed inside the window. It is only "at the same
 * commit" when both runs recorded the same commit, which only runs from 2026-09-18 on do.
 */
public final class RunStats {

    /** How many most-recent rated runs the trend, history and flake views cover. */
    public static final int WINDOW = 30;

    /** How many of the slowest tests are listed. */
    static final int SLOWEST = 8;

    private static final Set<String> PASSING = Set.of("passed", "warned");
    private static final Set<String> FAILING = Set.of("failed", "error", "timeout");
    private static final Set<String> VERDICTS = Set.of("passed", "warned", "failed", "error", "timeout");

    /**
     * A test that both passed and failed in the window.
     *
     * @param sameCommit true: a pass and a fail share a commit; false: commits were recorded and
     *                   differ; null: not enough commits recorded to say
     */
    public record Flake(String nodeid, int passes, int fails, Boolean sameCommit) {}

    /** A test's median duration over the window. */
    public record Timing(String nodeid, double median, int count) {}

    /** A step's mean seconds over the window. */
    public record StepMean(String name, double mean, int count) {}

    /** A failure reason and how often it occurred. */
    public record Reason(String excType, int count) {}

    /**
     * Everything the statistics view draws for one segment.
     *
     * <p>{@code window} is newest first; {@code passRates} and each {@code history} row are
     * oldest first, the way a sparkline reads.
     */
    public record SegmentStats(
            String segment,
            int runs,
            int finished,
            Map<String, Integer> byStatus,
            int cancelled,
            int incomplete,
            int noVerdicts,
            List<RunSummary> window,
            List<Double> passRates,
            List<String> unmigratedExcluded,
            Map<String, List<String>> history,
            List<Flake> flakes,
            List<Timing> slowest,
            List<StepMean> stepMeans,
            List<Reason> reasons) {}

    private RunStats() {}

    /**
     * Aggregates {@code rows} (newest first) for one {@code segment}.
     *
     * @param placeholder true for a node id whose file is an unmigrated placeholder
     * @param window      how many rated runs to cover
     */
    public static SegmentStats compute(List<RunSummary> rows, String segment,
            Predicate<String> placeholder, int window) {
        Map<String, Boolean> placeholderCache = new HashMap<>();
        Predicate<String> isPlaceholder = nodeid ->
                placeholderCache.computeIfAbsent(nodeid, placeholder::test);

        List<RunSummary> mine = rows.stream().filter(row -> segment.equals(row.segment())).toList();
        List<RunSummary> finished = mine.stream()
                .filter(row -> !row.status().equals("cancelled") && !row.status().equals("incomplete"))
                .toList();
        List<RunSummary> withVerdicts = finished.stream().filter(row -> !rated(row, isPlaceholder).isEmpty()).toList();
        List<RunSummary> recent = withVerdicts.subList(0, Math.min(window, withVerdicts.size()));
        List<RunSummary> oldestFirst = new ArrayList<>(recent);
        java.util.Collections.reverse(oldestFirst);

        Set<String> excluded = new HashSet<>();
        for (RunSummary row : finished) {
            for (RunSummary.TestRow test : row.tests()) {
                if (isPlaceholder.test(test.nodeid())) {
                    excluded.add(test.nodeid());
                }
            }
        }

        List<Double> rates = new ArrayList<>();
        Map<String, List<String>> verdicts = new HashMap<>();
        Map<String, List<Double>> durations = new HashMap<>();
        Map<String, Map<String, Set<String>>> heads = new HashMap<>();
        for (int column = 0; column < oldestFirst.size(); column++) {
            RunSummary row = oldestFirst.get(column);
            List<RunSummary.TestRow> rated = rated(row, isPlaceholder);
            long passing = rated.stream().filter(t -> PASSING.contains(t.status())).count();
            rates.add((double) passing / rated.size());
            for (RunSummary.TestRow test : rated) {
                verdicts.computeIfAbsent(test.nodeid(), k -> blankCells(oldestFirst.size()))
                        .set(column, test.status());
                durations.computeIfAbsent(test.nodeid(), k -> new ArrayList<>()).add(test.duration());
                String side = PASSING.contains(test.status()) ? "pass" : "fail";
                heads.computeIfAbsent(test.nodeid(), k -> Map.of("pass", new HashSet<>(), "fail", new HashSet<>()))
                        .get(side).add(row.gitHead());
            }
        }

        List<Flake> flakes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : verdicts.entrySet()) {
            String nodeid = entry.getKey();
            List<String> cells = entry.getValue();
            int passes = (int) cells.stream().filter(PASSING::contains).count();
            int fails = (int) cells.stream().filter(FAILING::contains).count();
            if (passes > 0 && fails > 0) {
                Set<String> passHeads = new HashSet<>(heads.get(nodeid).get("pass"));
                Set<String> failHeads = new HashSet<>(heads.get(nodeid).get("fail"));
                passHeads.remove("");
                failHeads.remove("");
                Boolean same = null;
                if (!passHeads.isEmpty() && !failHeads.isEmpty()) {
                    same = passHeads.stream().anyMatch(failHeads::contains);
                }
                flakes.add(new Flake(nodeid, passes, fails, same));
            }
        }
        flakes.sort(Comparator.comparingInt((Flake f) -> -Math.min(f.passes(), f.fails()))
                .thenComparing(Flake::nodeid));

        Map<String, List<Double>> stepTimes = new HashMap<>();
        for (RunSummary row : recent) {
            for (RunSummary.StepRow step : row.steps()) {
                if (!step.status().equals("pending") && !step.status().equals("skipped")) {
                    stepTimes.computeIfAbsent(step.name(), k -> new ArrayList<>()).add(step.elapsed());
                }
            }
        }
        List<StepMean> stepMeans = new ArrayList<>();
        for (String name : RunReplay.STEP_NAMES) {
            List<Double> times = stepTimes.get(name);
            if (times != null && !times.isEmpty()) {
                double mean = times.stream().mapToDouble(Double::doubleValue).sum() / times.size();
                stepMeans.add(new StepMean(name, mean, times.size()));
            }
        }

        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (RunSummary row : recent) {
            for (RunSummary.TestRow test : rated(row, isPlaceholder)) {
                if (FAILING.contains(test.status())) {
                    String reason = test.excType().isEmpty() ? "(no reason recorded)" : test.excType();
                    reasons.merge(reason, 1, Integer::sum);
                }
            }
        }
        List<Reason> reasonRows = new ArrayList<>();
        reasons.forEach((reason, count) -> reasonRows.add(new Reason(reason, count)));
        reasonRows.sort(Comparator.comparingInt(Reason::count).reversed());

        List<Timing> slowest = new ArrayList<>();
        durations.forEach((nodeid, d) -> slowest.add(new Timing(nodeid, median(d), d.size())));
        slowest.sort(Comparator.comparingDouble(Timing::median).reversed().thenComparing(Timing::nodeid));

        Map<String, Integer> byStatus = new TreeMap<>();
        for (RunSummary row : finished) {
            byStatus.merge(row.status(), 1, Integer::sum);
        }
        Map<String, List<String>> history = new TreeMap<>(verdicts);

        return new SegmentStats(
                segment,
                mine.size(),
                finished.size(),
                byStatus,
                (int) mine.stream().filter(r -> r.status().equals("cancelled")).count(),
                (int) mine.stream().filter(r -> r.status().equals("incomplete")).count(),
                finished.size() - withVerdicts.size(),
                recent,
                rates,
                excluded.stream().sorted().toList(),
                history,
                flakes,
                slowest.subList(0, Math.min(SLOWEST, slowest.size())),
                stepMeans,
                reasonRows);
    }

    /** The row's tests that carry a verdict and are not placeholders. */
    private static List<RunSummary.TestRow> rated(RunSummary row, Predicate<String> placeholder) {
        return row.tests().stream()
                .filter(t -> VERDICTS.contains(t.status()) && !placeholder.test(t.nodeid()))
                .toList();
    }

    private static List<String> blankCells(int size) {
        List<String> cells = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            cells.add("");
        }
        return cells;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
