package com.verifone.cat.runservice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the pages show of a {@link RunState}, as plain rows and words: the Python CAT's {@code cat.web.view}, with
 * the pieces of {@code cat.labels} and {@code cat.panels.run} ({@code status_word}, {@code verdict_counts}) the rows
 * are made of. No HTML here; the web package lays these out.
 *
 * <p>A case's row follows {@link RunState#caseVerdicts}: a case is one Jira test case, joined to its tests through
 * its file. A run with no plan behind it has no cases, so its rows are its tests.
 */
public final class RunView {

    /** Status word to colour family; the page maps each family to one colour. */
    public static final Map<String, String> TONES = Map.ofEntries(
            Map.entry("passed", "ok"), Map.entry("complete", "ok"), Map.entry("ready", "ok"),
            Map.entry("adopted", "ok"), Map.entry("available", "ok"), Map.entry("connected", "ok"),
            Map.entry("up", "ok"), Map.entry("failed", "bad"), Map.entry("error", "bad"),
            Map.entry("timeout", "bad"), Map.entry("unavailable", "bad"), Map.entry("down", "bad"),
            Map.entry("running", "run"), Map.entry("starting", "run"), Map.entry("repairing", "warn"),
            Map.entry("warned", "warn"), Map.entry("cancelled", "warn"), Map.entry("interrupted", "warn"),
            Map.entry("incomplete", "warn"), Map.entry("skipped", "idle"));

    /** The Status counter's words (the Python {@code STATUS_WORDS}). */
    static final Map<String, String> STATUS_WORDS = Map.of(
            "not_started", "Not started",
            "running", "Running",
            "complete", "Complete",
            "failed", "Failed",
            "cancelled", "Cancelled");

    /** Outcomes that make the Status counter read Failed. */
    static final Set<String> FAILED_OUTCOMES = Set.of("failed", "error", "timeout");

    private static final Pattern CASE_IN_NAME = Pattern.compile("petrocore[-_](\\d+)", Pattern.CASE_INSENSITIVE);

    private RunView() {}

    /** One of the seven steps as the board shows it. */
    public record StepRow(String name, String status, String elapsed, String detail) {}

    /** One plan case (or, with no plan, one test) as the case table shows it. */
    public record CaseRow(String key, String title, String status, String elapsed, String detail) {}

    /** How one test's row reads: its case line, and its function where its file holds more than one test. */
    public record NodeLabel(String text, String function) {}

    /** The colour family of a status word; {@code idle} for any word it does not know. */
    public static String tone(String status) {
        return TONES.getOrDefault(status == null ? "" : status, "idle");
    }

    static boolean isOver(RunState state) {
        return state.outcome() != null || "complete".equals(state.status()) || "cancelled".equals(state.status());
    }

    /** The run's one word: cancelled, its outcome once over, else its status with spaces. */
    public static String runStatus(RunState state) {
        if ("cancelled".equals(state.status())) {
            return "cancelled";
        }
        if (state.outcome() != null) {
            return state.outcome();
        }
        return state.status().replace('_', ' ');
    }

    /** The Status counter: Not started, Running, Complete, Failed or Cancelled ({@code status_word}). */
    public static String statusWord(RunState state) {
        if ("cancelled".equals(state.status())) {
            return STATUS_WORDS.get("cancelled");
        }
        if (state.outcome() != null && FAILED_OUTCOMES.contains(state.outcome())) {
            return STATUS_WORDS.get("failed");
        }
        return STATUS_WORDS.getOrDefault(state.status(), state.status());
    }

    /**
     * Passed, failed, warned, skipped and total, in one unit: cases when a plan resolved, tests when none did
     * ({@code verdict_counts}).
     */
    public static Map<String, Integer> verdictCounts(RunState state) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!state.cases().isEmpty()) {
            Map<String, Integer> counts = state.caseVerdictCounts();
            out.put("passed", counts.getOrDefault("passed", 0));
            out.put("failed", counts.getOrDefault("failed", 0));
            out.put("warned", counts.getOrDefault("warned", 0));
            out.put("skipped", counts.getOrDefault("skipped", 0));
            out.put("total", state.cases().size());
            return out;
        }
        int skipped = (int) state.tests().values().stream().filter(t -> "skipped".equals(t.status())).count();
        out.put("passed", state.testCountPassed());
        out.put("failed", state.testCountFailed());
        out.put("warned", state.testCountWarned());
        out.put("skipped", skipped);
        Integer total = state.testCountTotal();
        out.put("total", total != null && total != 0 ? total
                : state.testCountPassed() + state.testCountFailed() + state.testCountWarned() + skipped);
        return out;
    }

    /** The seven steps with a one-line detail each; a step still running once the run is over reads interrupted. */
    public static List<StepRow> stepRows(RunState state) {
        boolean over = isOver(state);
        List<StepRow> rows = new ArrayList<>();
        for (RunState.StepState step : state.steps()) {
            String detail = step.progressDetail().isEmpty() ? step.detail() : step.progressDetail();
            if (detail.isEmpty() && step.progressTotal() != null && step.progressTotal() != 0) {
                detail = step.progressDone() + " of " + step.progressTotal();
            }
            String elapsed = step.elapsed() != 0 ? elapsedText(step.elapsed()) : "";
            String status = over && "running".equals(step.status()) ? "interrupted" : step.status();
            rows.add(new StepRow(step.name(), status, elapsed, detail));
        }
        return rows;
    }

    /** The step the board's current-step card shows: the running one, else the last that is not pending. */
    public static int activeStep(List<StepRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            String status = rows.get(i).status();
            if ("running".equals(status) || "repairing".equals(status)) {
                return i;
            }
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (!"pending".equals(rows.get(i).status())) {
                return i;
            }
        }
        return 0;
    }

    static String testDetail(String status, String excType, String detail, List<RunState.SubStep> steps) {
        if ("running".equals(status) && !steps.isEmpty()) {
            return "step " + steps.size() + ": " + steps.get(steps.size() - 1).label();
        }
        return reason(excType, detail);
    }

    /** {@code ExcType: first line of the detail}, either part alone, or "". */
    public static String reason(String excType, String detail) {
        List<String> parts = new ArrayList<>();
        if (!excType.isEmpty()) {
            parts.add(excType);
        }
        String head = firstLine(detail);
        if (!head.isEmpty()) {
            parts.add(head);
        }
        return String.join(": ", parts);
    }

    static String firstLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int cut = text.indexOf('\n');
        return (cut < 0 ? text : text.substring(0, cut)).replace("\r", "");
    }

    /**
     * Every case in plan order, or every test when the run has no plan. A case with no verdict reads running when
     * one of its tests is, not migrated with no file, else queued; once the run is over, interrupted or not run.
     */
    public static List<CaseRow> caseRows(RunState state) {
        List<CaseRow> rows = new ArrayList<>();
        if (state.cases().isEmpty()) {
            for (RunState.TestState test : state.tests().values()) {
                rows.add(new CaseRow(shortNodeid(test.nodeid()), "", test.status(),
                        test.duration() != 0 ? elapsedText(test.duration()) : "",
                        testDetail(test.status(), test.excType(), test.detail(), test.steps())));
            }
            return rows;
        }
        Map<String, List<RunState.TestState>> byFile = new HashMap<>();
        for (RunState.TestState test : state.tests().values()) {
            byFile.computeIfAbsent(RunState.nodeFile(test.nodeid()), k -> new ArrayList<>()).add(test);
        }
        Map<String, String> verdicts = state.caseVerdicts();
        boolean over = isOver(state);
        for (RunState.CaseState planned : state.cases().values()) {
            List<RunState.TestState> tests = byFile.getOrDefault(planned.node() == null ? "" : planned.node(),
                    List.of());
            String status;
            if (verdicts.containsKey(planned.key())) {
                status = verdicts.get(planned.key());
            } else if (planned.node() == null) {
                status = "not migrated";
            } else if (tests.stream().anyMatch(t -> "running".equals(t.status()))) {
                status = over ? "interrupted" : "running";
            } else {
                status = over ? "not run" : "queued";
            }
            double duration = tests.stream().filter(t -> RunState.VERDICT_STATUSES.contains(t.status()))
                    .mapToDouble(RunState.TestState::duration).sum();
            RunState.TestState shown = tests.stream().filter(t -> "running".equals(t.status())).findFirst()
                    .orElse(tests.stream().filter(t -> RunState.FAILING_STATUSES.contains(t.status())).findFirst()
                            .orElse(null));
            String detail = shown == null ? ""
                    : testDetail(shown.status(), shown.excType(), shown.detail(), shown.steps());
            rows.add(new CaseRow(planned.key(), planned.summary(), status,
                    duration != 0 ? elapsedText(duration) : "", detail));
        }
        return rows;
    }

    /** The tests of one case: those in its file, or, with no such case, the test whose short id is the key. */
    public static List<RunState.TestState> caseTests(RunState state, String key) {
        RunState.CaseState planned = state.cases().get(key);
        List<RunState.TestState> out = new ArrayList<>();
        for (RunState.TestState test : state.tests().values()) {
            boolean ofCase = planned != null && planned.node() != null
                    && RunState.nodeFile(test.nodeid()).equals(planned.node());
            boolean byKey = planned == null && shortNodeid(test.nodeid()).equals(key);
            if (ofCase || byKey) {
                out.add(test);
            }
        }
        return out;
    }

    /** {@code run c50ca70c38af · lab · fake POS · 39 cases}: the line under a run's title. */
    public static String describe(RunState state) {
        List<String> parts = new ArrayList<>();
        if (!state.runId().isEmpty()) {
            parts.add("run " + state.runId());
        }
        if (!state.rig().isEmpty()) {
            parts.add(state.rig());
        }
        if (state.fakePos()) {
            parts.add("fake POS");
        }
        if (!state.cases().isEmpty()) {
            parts.add(state.cases().size() + " cases");
        } else if (!state.tests().isEmpty()) {
            parts.add(state.tests().size() + " tests");
        }
        return String.join(" · ", parts);
    }

    /** How long the run has gone: its recorded elapsed once over, else since it started. */
    public static double runSeconds(RunState state, Instant now) {
        if (isOver(state) && state.elapsed() != 0) {
            return state.elapsed();
        }
        if (state.startedAt() == null) {
            return 0.0;
        }
        return Math.max(0.0, (now.toEpochMilli() - state.startedAt().toEpochMilli()) / 1000.0);
    }

    /** {@code 0:04:09}: the run timer, which keeps its seconds past an hour. */
    public static String clockText(double seconds) {
        long whole = (long) seconds;
        return String.format(Locale.ROOT, "%d:%02d:%02d", whole / 3600, (whole % 3600) / 60, whole % 60);
    }

    /** {@code 12s} / {@code 1m 05s} / {@code 1h 02m}: the time a finished step or run took. */
    public static String elapsedText(double seconds) {
        long whole = (long) seconds;
        if (whole < 60) {
            return whole + "s";
        }
        long minutes = whole / 60;
        long secs = whole % 60;
        if (minutes < 60) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
    }

    /** {@code PETROCORE-79588: Import Config-...}, or the key alone. */
    public static String caseLine(String key, String summary) {
        return summary == null || summary.isEmpty() ? key : key + ": " + summary;
    }

    /** The case key a test file's name carries ({@code PETROCORE-61337}), or "". */
    public static String caseKeyOf(String path) {
        String file = path.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1);
        var match = CASE_IN_NAME.matcher(file);
        return match.find() ? "PETROCORE-" + match.group(1) : "";
    }

    /**
     * {@code PETROCORE-61337::test_check_sale}: the file, then the test, minus {@code -TestCase} and a test name that
     * only restates the file or the case key.
     */
    public static String shortNodeid(String nodeid) {
        int cut = nodeid.indexOf("::");
        String path = cut < 0 ? nodeid : nodeid.substring(0, cut);
        String name = cut < 0 ? "" : nodeid.substring(cut + 2);
        String file = path.replace('\\', '/');
        file = file.substring(file.lastIndexOf('/') + 1);
        String stem = removeSuffix(removeSuffix(removeSuffix(file, ".py"), ".feature"), "-TestCase");
        boolean restatesKey = CASE_IN_NAME.matcher(stem).matches()
                && name.equals("test_" + stem.toLowerCase(Locale.ROOT).replace('-', '_'));
        if (name.isEmpty() || name.equals(stem) || restatesKey) {
            return stem;
        }
        return stem + "::" + name;
    }

    private static String removeSuffix(String text, String suffix) {
        return text.endsWith(suffix) ? text.substring(0, text.length() - suffix.length()) : text;
    }

    /** Every node id's label, joined to its case through the run's own cases ({@code state_labels}). */
    public static Map<String, NodeLabel> stateLabels(Map<String, RunState.CaseState> cases,
            Iterable<String> nodeids) {
        Map<String, String[]> byFile = new HashMap<>();
        for (RunState.CaseState planned : cases.values()) {
            if (planned.node() != null && !planned.node().isEmpty()) {
                byFile.putIfAbsent(planned.node(), new String[] {planned.key(), planned.summary()});
            }
        }
        List<String> ids = new ArrayList<>();
        nodeids.forEach(ids::add);
        Map<String, Integer> perFile = new HashMap<>();
        for (String nodeid : ids) {
            perFile.merge(RunState.nodeFile(nodeid), 1, Integer::sum);
        }
        Map<String, NodeLabel> labels = new LinkedHashMap<>();
        for (String nodeid : ids) {
            String file = RunState.nodeFile(nodeid);
            String[] owner = byFile.get(file);
            if (owner == null || owner[1].isEmpty()) {
                labels.put(nodeid, new NodeLabel(shortNodeid(nodeid), ""));
                continue;
            }
            int cut = nodeid.indexOf("::");
            String function = perFile.get(file) > 1 && cut >= 0 ? nodeid.substring(cut + 2) : "";
            labels.put(nodeid, new NodeLabel(caseLine(owner[0], owner[1]), function));
        }
        return labels;
    }

    /**
     * What a run is running: its plans by name when one name was recorded per plan, else the keys; with no plan,
     * the suite folders by their own names.
     */
    public static String plansLabel(List<String> testPlans, String suite, List<String> recorded) {
        if (testPlans.isEmpty()) {
            return suiteNames(suite);
        }
        List<String> names = recorded.size() == testPlans.size() ? recorded : testPlans;
        return String.join(", ", names);
    }

    /** {@code tests/suites/cadence tests/suites/charity} to {@code cadence, charity}. */
    public static String suiteNames(String suite) {
        Set<String> leaves = new LinkedHashSet<>();
        for (String part : suite.replace('\\', '/').split("\\s+")) {
            String trimmed = part.replaceAll("/+$", "");
            String leaf = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            if (!leaf.isEmpty()) {
                leaves.add(leaf);
            }
        }
        return String.join(", ", leaves);
    }

    /** What the run ran, by plan name, else "Run". */
    public static String runTitle(RunState state) {
        String label = plansLabel(state.testPlans(), state.suite(), state.planNames());
        return label.isEmpty() ? "Run" : label;
    }
}
