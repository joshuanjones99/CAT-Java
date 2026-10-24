package com.verifone.cat.xray;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.verifone.cat.results.RunSummary;
import com.verifone.cat.xray.Adf.Block;
import com.verifone.cat.xray.Adf.Bullets;
import com.verifone.cat.xray.Adf.Heading;
import com.verifone.cat.xray.Adf.Paragraph;
import com.verifone.cat.xray.Adf.Span;
import com.verifone.cat.xray.Adf.Table;
import com.verifone.cat.xray.XrayRunState.TestState;

/**
 * The end-of-run summary for the Test Execution, written for a product owner. Port of the Python
 * CAT's {@code cat.xray.summary}.
 *
 * <p>Plain English, every sentence derived from the run's own events or the Results index. Three
 * questions: what ran and when, what failed, and whether the failures are anything to worry about.
 * The last rests on two deterministic rules: {@link #classify} sorts each failing case by what kind
 * of thing went wrong, and {@link #historyFromRows} says whether the same test failed in earlier
 * rig runs of the plan.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the wording is the Python's first draft and has not been read
 * against a live execution by this port.
 */
public final class Summary {

    /** The category of a failing case: what kind of thing went wrong. */
    public static final String PRODUCT = "product";
    public static final String EQUIPMENT = "equipment";
    public static final String CASCADE = "cascade";
    public static final String HARNESS = "harness";
    public static final String WARNED = "warned";

    /** What each category means, in the words the summary uses. */
    public static final Map<String, String> CATEGORY_LABELS = Map.of(
            PRODUCT, "The POS produced a different result from the expected one",
            EQUIPMENT, "The test could not drive the equipment in time",
            CASCADE, "Not judged: earlier cases in the plan failed",
            HARNESS, "The automation hit an error of its own",
            WARNED, "Passed, with warnings");

    private static final Set<String> EQUIPMENT_EXCEPTIONS = Set.of(
            "DeviceUnavailable", "DeviceUnreachable", "DeviceProtocolError", "DeviceError",
            "TimeoutExpired", "TimeoutError", "ConnectionError", "ConnectionRefusedError",
            "ConnectionResetError", "OSError", "RecoveryFailed");
    private static final List<String> EQUIPMENT_MARKERS = List.of(
            "TimeoutExpired", "WAIT_", "exceeded its", "did not answer", "unreachable");
    private static final List<String> PRODUCT_MARKERS = List.of(
            "VALIDATE", "validation(s) failed", "differs from", "mismatch");
    private static final String CASCADE_MARKER = "plan_end verdict untrusted";

    private static final Pattern CASE_PREFIX = Pattern.compile("^PETROCORE-\\d+:?\\s*");
    private static final int REASON_MAX = 220;

    private static final Set<String> PASSING = Set.of("passed", "warned");
    private static final Set<String> FAILING = Set.of("failed", "error", "timeout");
    private static final int HISTORY_RUNS = 5;
    private static final DateTimeFormatter LOCAL_DATE_TIME =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter LOCAL_DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

    private Summary() {}

    /** What the launcher knows about the run and the event stream does not carry. */
    public record SummaryFacts(String build, String dist, String branch, String upgradeMode,
            String controller, String catVersion) {

        /** Facts with nothing known. */
        public static SummaryFacts none() {
            return new SummaryFacts("", "", "", "", "", "");
        }
    }

    /** One earlier verdict of a test: when its run started, and its status. */
    public record Verdict(String startedAt, String status) {}

    /** One failing case, as the summary's table and bullets need it. */
    public record CaseFailure(String key, String summary, String category, String reason,
            String seen, boolean isNew, boolean isRepeat) {}

    /**
     * Everything the summary says, as numbers and rows, for the tests and the comment.
     * {@code byCategory} counts the failing cases by category.
     */
    public record Digest(int ran, int passed, int warned, int failed, int cascade, int noTest,
            int notReached, List<CaseFailure> failures, String stoppedAt, String stoppedDetail,
            Map<String, Integer> byCategory) {

        int category(String name) {
            return byCategory.getOrDefault(name, 0);
        }
    }

    /** The blocks of the description and the digest they were built from. */
    public record Built(List<Block> blocks, Digest digest) {}

    // ------------------------------------------------------------------ classification

    /** Which kind of thing went wrong for one test, from its verdict and exception. */
    public static String classify(String status, String excType, String detail) {
        if (WARNED.equals(status)) {
            return WARNED;
        }
        String text = detail == null ? "" : detail;
        String exc = excType == null ? "" : excType;
        if ("skipped".equals(status) || text.contains(CASCADE_MARKER)) {
            return CASCADE;
        }
        // A validation that found the wrong result is about the product even when what it waited
        // for never came; a wait for a device prompt that never came is about the equipment.
        if (PRODUCT_MARKERS.stream().anyMatch(text::contains)) {
            return PRODUCT;
        }
        if (EQUIPMENT_EXCEPTIONS.contains(exc) || EQUIPMENT_MARKERS.stream().anyMatch(text::contains)) {
            return EQUIPMENT;
        }
        if (List.of("AssertionError", "PatsStepError", "SnapshotMissingError").contains(exc)) {
            return PRODUCT;
        }
        return HARNESS;
    }

    /** One readable line of a failure's detail, without the case-key prefix, bounded. */
    public static String reasonHead(String detail, String excType) {
        List<String> lines = new ArrayList<>();
        if (detail != null) {
            detail.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(lines::add);
        }
        if (lines.isEmpty()) {
            return excType == null ? "" : excType;
        }
        String head = CASE_PREFIX.matcher(lines.get(0)).replaceFirst("");
        if (head.contains("validation(s) failed")) {
            String first = lines.stream().skip(1).filter(line -> line.startsWith("#")).findFirst().orElse("");
            head = head.split("\\(PATS carries on", 2)[0].strip() + (first.isEmpty() ? "" : " — " + first);
        }
        head = head.replaceAll("\\s+", " ");
        return head.length() > REASON_MAX ? head.substring(0, REASON_MAX) + "…" : head;
    }

    // ------------------------------------------------------------------ history

    /**
     * Each test's verdicts in the previous rig runs of the same plan(s), newest first. Only finished
     * rig runs that share a plan key count; the current run, fake POS runs and no-rig sessions
     * never do.
     */
    public static Map<String, List<Verdict>> historyFromRows(Collection<RunSummary> rows, String runId,
            Collection<String> testPlans, String rig) {
        Set<String> plans = new HashSet<>(testPlans);
        List<RunSummary> newestFirst = new ArrayList<>(rows);
        newestFirst.sort(Comparator.comparing(RunSummary::startedAt)
                .thenComparing(RunSummary::runId).reversed());
        Map<String, List<Verdict>> history = new LinkedHashMap<>();
        int kept = 0;
        for (RunSummary row : newestFirst) {
            if (row.runId().equals(runId) || !"rig".equals(row.segment()) || !row.rig().equals(rig)) {
                continue;
            }
            boolean sharesPlan = row.testPlans().stream().anyMatch(plans::contains);
            if (!sharesPlan || !("passed".equals(row.status()) || "failed".equals(row.status()))) {
                continue;
            }
            for (RunSummary.TestRow test : row.tests()) {
                if (PASSING.contains(test.status()) || FAILING.contains(test.status())) {
                    history.computeIfAbsent(test.nodeid(), k -> new ArrayList<>())
                            .add(new Verdict(row.startedAt(), test.status()));
                }
            }
            kept++;
            if (kept >= HISTORY_RUNS) {
                break;
            }
        }
        return history;
    }

    /** The "Seen before?" cell: new, repeated, intermittent, or nothing to compare with. */
    static String seenBefore(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return "No earlier run to compare";
        }
        long fails = verdicts.stream().filter(v -> FAILING.contains(v.status())).count();
        Verdict last = verdicts.get(0);
        String when = shortDate(last.startedAt());
        if (PASSING.contains(last.status())) {
            return "New: passed in the previous run (" + when + ")";
        }
        if (fails == verdicts.size()) {
            return fails > 1
                    ? "Failed in the last " + fails + " runs too"
                    : "Also failed last run (" + when + ")";
        }
        return "Intermittent: failed " + fails + " of the last " + verdicts.size() + " runs";
    }

    // ------------------------------------------------------------------ digest

    /** The digest of a folded run, with the history it is compared against. */
    public static Digest digest(XrayRunState state, Map<String, List<Verdict>> history) {
        Map<String, String> verdicts = state.caseVerdicts();
        Map<String, Integer> counts = state.verdictCounts();
        Map<String, List<TestState>> byFile = new LinkedHashMap<>();
        state.testsByFile().forEach((file, tests) -> byFile.put(file, tests.stream().toList()));
        List<CaseFailure> failures = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (XrayRunState.CaseState kase : state.cases().values()) {
            String verdict = verdicts.get(kase.key());
            if (verdict == null || "passed".equals(verdict) || kase.node() == null) {
                continue;
            }
            List<TestState> tests = byFile.getOrDefault(kase.node(), List.of());
            TestState worst = tests.isEmpty()
                    ? new TestState(kase.node(), verdict, 0.0, 0, "", "")
                    : worst(tests);
            String category = classify(verdict, worst.excType(), worst.detail());
            byCategory.merge(category, 1, Integer::sum);
            List<Verdict> seenVerdicts = history.getOrDefault(worst.nodeid(), List.of());
            String seen = seenBefore(seenVerdicts);
            failures.add(new CaseFailure(kase.key(), kase.summary(), category,
                    reasonHead(worst.detail(), worst.excType()), seen,
                    seen.startsWith("New"),
                    !seenVerdicts.isEmpty() && FAILING.contains(seenVerdicts.get(0).status())));
        }
        long runnable = state.cases().values().stream().filter(c -> c.node() != null).count();
        String[] stopped = stoppedStep(state);
        return new Digest(
                verdicts.size(),
                counts.getOrDefault("passed", 0),
                counts.getOrDefault(WARNED, 0),
                counts.getOrDefault("failed", 0),
                counts.getOrDefault("skipped", 0),
                state.cases().size() - (int) runnable,
                (int) runnable - verdicts.size(),
                List.copyOf(failures),
                stopped[0],
                stopped[1],
                byCategory);
    }

    /** The worst test of a case's file: failed, then skipped, warned, passed. */
    private static TestState worst(List<TestState> tests) {
        TestState best = tests.get(0);
        for (TestState test : tests) {
            if (rank(test.status()) < rank(best.status())) {
                best = test;
            }
        }
        return best;
    }

    private static int rank(String status) {
        return switch (status) {
            case "failed", "error", "timeout" -> 0;
            case "skipped" -> 1;
            case "warned" -> 2;
            case "passed" -> 3;
            default -> 4;
        };
    }

    /** The step that ended the run before the tests, as {name, detail}; both "" when none did. */
    private static String[] stoppedStep(XrayRunState state) {
        for (int index = 0; index < XrayRunState.TESTS_RUN_INDEX; index++) {
            XrayRunState.StepState step = state.steps().get(XrayRunState.STEP_NAMES.get(index));
            if (step != null && "failed".equals(step.status())) {
                return new String[] {step.name(), step.detail()};
            }
        }
        return new String[] {"", ""};
    }

    // ------------------------------------------------------------------ the description

    /** The execution's description: what ran, the result, the concerns, the failures. */
    public static Built build(XrayRunState state, SummaryFacts facts, Map<String, String> planNames,
            Map<String, List<Verdict>> history, String runDir) {
        Digest d = digest(state, history == null ? Map.of() : history);
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading("Automated test run", 1));
        blocks.add(whatRan(state, facts, planNames == null ? Map.of() : planNames));
        blocks.add(result(d));
        blocks.add(Heading.of("Does this need attention?"));
        blocks.add(concerns(d));
        List<CaseFailure> failures = d.failures().stream()
                .filter(f -> !CASCADE.equals(f.category()) && !WARNED.equals(f.category())).toList();
        if (!failures.isEmpty()) {
            blocks.add(Heading.of("What failed"));
            blocks.add(failureTable(failures));
        }
        List<CaseFailure> warned = d.failures().stream().filter(f -> WARNED.equals(f.category())).toList();
        if (!warned.isEmpty()) {
            blocks.add(Heading.of("Passed with warnings"));
            blocks.add(Bullets.ofText(warned.stream().map(f -> f.key() + " — " + f.summary()).toList()));
        }
        List<CaseFailure> cascaded = d.failures().stream().filter(f -> CASCADE.equals(f.category())).toList();
        if (!cascaded.isEmpty()) {
            blocks.add(Heading.of("Could not be judged"));
            blocks.add(Bullets.ofText(cascaded.stream().map(f -> f.key() + " — " + f.summary()).toList()));
        }
        // A run where every case passed has nothing to chase, so no "Where to look".
        if (!allPassed(d)) {
            List<String> where = new ArrayList<>();
            if (runDir != null && !runDir.isEmpty()) {
                where.add("Evidence folder: " + runDir + " (summary.html, junit.xml, per-case screenshots)");
            }
            where.add("Statuses on this execution were updated live as each case finished.");
            blocks.add(Heading.of("Where to look"));
            blocks.add(Bullets.ofText(where));
        }
        return new Built(List.copyOf(blocks), d);
    }

    private static Paragraph whatRan(XrayRunState state, SummaryFacts facts, Map<String, String> planNames) {
        String plans = String.join(", ", state.testPlans().stream().map(key -> {
            String name = planNames.getOrDefault(key, "");
            return name.isEmpty() ? key : name + " (" + key + ")";
        }).toList());
        if (plans.isEmpty()) {
            plans = state.suite().isEmpty() ? "the selected tests" : state.suite();
        }
        OffsetDateTime started = state.startedMoment().orElse(null);
        String finishedText = started == null
                ? "?"
                : Instant.ofEpochSecond(started.toEpochSecond())
                        .plusMillis(Math.round(state.runElapsed() * 1000))
                        .atZone(ZoneId.systemDefault()).format(LOCAL_TIME);
        List<Span> spans = new ArrayList<>();
        spans.add(Span.bold("What ran."));
        spans.add(Span.plain(" CAT ran " + plans + ", " + plural(state.cases().size(), "case")
                + ", on the " + (state.rig().isEmpty() ? "unknown" : state.rig())
                + " rig from " + local(started) + " to " + finishedText + " ("
                + duration(state.runElapsed()) + "). "));
        if (!facts.build().isEmpty()) {
            String installed = switch (facts.upgradeMode()) {
                case "new_install" -> "installed fresh (PSI)";
                case "classic" -> "installed by Auto Upgrade";
                case "vhq" -> "installed through VHQ";
                default -> "already on the rig";
            };
            spans.add(Span.plain("Software under test: " + facts.build()));
            spans.add(Span.plain(facts.dist().isEmpty() ? "" : " (" + facts.dist() + ")"));
            spans.add(Span.plain(", " + installed + ". "));
        } else if (!facts.upgradeMode().isEmpty() && !"skip".equals(facts.upgradeMode())) {
            spans.add(Span.plain("The build was installed first (" + facts.upgradeMode() + "). "));
        }
        if (!facts.branch().isEmpty()) {
            spans.add(Span.plain("Test scripts from the petroapps branch " + facts.branch() + ". "));
        }
        if (!facts.controller().isEmpty()) {
            spans.add(Span.plain("Run from " + facts.controller() + ". "));
        }
        spans.add(Span.plain("Run ID " + (state.runId().isEmpty() ? "unknown" : state.runId())
                + (facts.catVersion().isEmpty() ? "" : ", CAT " + facts.catVersion()) + "."));
        return new Paragraph(spans.stream().filter(s -> s.bold() || !s.text().isEmpty()).toList());
    }

    private static Paragraph result(Digest d) {
        if (!d.stoppedAt().isEmpty()) {
            return Paragraph.of(
                    Span.bold("The run did not reach the tests."),
                    Span.plain(" It stopped at the " + d.stoppedAt() + " step: "
                            + (d.stoppedDetail().isEmpty() ? "no detail recorded" : d.stoppedDetail())
                            + ". No case was run, so the case statuses on this execution are not from this run."));
        }
        StringBuilder first = new StringBuilder(
                (d.passed() + d.warned()) + " of the " + d.ran() + " cases that ran passed");
        if (d.warned() > 0) {
            first.append(" (").append(d.warned()).append(" with warnings)");
        }
        first.append('.');
        List<String> parts = new ArrayList<>();
        parts.add(first.toString());
        if (d.failed() > 0) {
            parts.add(d.failed() + " failed.");
        }
        if (d.cascade() > 0) {
            parts.add(d.cascade() + " could not be judged.");
        }
        if (d.notReached() > 0) {
            parts.add(d.notReached() + " were not reached.");
        }
        if (d.noTest() > 0) {
            parts.add(d.noTest() + " of the plan's cases have no automated test yet.");
        }
        return Paragraph.of(Span.bold("Result."), Span.plain(" " + String.join(" ", parts)));
    }

    private static Bullets concerns(Digest d) {
        List<List<Span>> items = new ArrayList<>();
        if (!d.stoppedAt().isEmpty()) {
            items.add(List.of(
                    Span.bold("The run itself needs attention first."),
                    Span.plain(" Nothing was tested, so this says nothing about the product. The automation "
                            + "team should look at why the run stopped and run it again.")));
            return new Bullets(items);
        }
        List<CaseFailure> product = d.failures().stream().filter(f -> PRODUCT.equals(f.category())).toList();
        if (!product.isEmpty()) {
            long repeat = product.stream().filter(CaseFailure::isRepeat).count();
            long fresh = product.stream().filter(CaseFailure::isNew).count();
            StringBuilder text = new StringBuilder(
                    " The POS did something different from what the test expected. Each one could be a "
                    + "product defect or an expected result that is out of date; the test team should "
                    + "check these first.");
            if (repeat > 0) {
                text.append(' ').append(repeat).append(" of them failed the same way in the previous run, so ")
                        .append(repeat == 1 ? "it is" : "they are").append(" not new.");
            }
            if (fresh > 0) {
                text.append(' ').append(fresh).append(' ').append(fresh == 1 ? "is" : "are").append(" new: ")
                        .append(fresh == 1 ? "it" : "they").append(" passed in the previous run on this rig.");
            }
            items.add(List.of(
                    Span.bold(plural(product.size(), "case") + (product.size() == 1 ? " needs" : " need")
                            + " a look."),
                    Span.plain(text.toString())));
        }
        if (d.category(EQUIPMENT) > 0) {
            items.add(List.of(
                    Span.bold(plural(d.category(EQUIPMENT), "case") + " failed because the test could not drive the rig."),
                    Span.plain(" A pump, card reader or screen did not answer in time. That usually points at the "
                            + "test equipment, not the product; a rerun normally clears it.")));
        }
        if (d.category(HARNESS) > 0) {
            items.add(List.of(
                    Span.bold(plural(d.category(HARNESS), "case") + " hit an error in the automation itself."),
                    Span.plain(" Not evidence about the product; the automation team owns these.")));
        }
        if (d.category(CASCADE) > 0) {
            items.add(List.of(
                    Span.bold(plural(d.category(CASCADE), "case") + " could not be judged."),
                    Span.plain(" They are judged at the end of the plan and only count when everything before them "
                            + "passed. They will be judged again once the failures above are fixed.")));
        }
        if (d.category(WARNED) > 0) {
            items.add(List.of(
                    Span.bold(plural(d.category(WARNED), "case") + " passed with warnings."),
                    Span.plain(" They passed; the warnings are notes for the automation team.")));
        }
        if (items.isEmpty()) {
            items.add(List.of(Span.bold("Nothing needs attention."), Span.plain(" Every case that ran passed.")));
        }
        if (d.notReached() > 0) {
            items.add(List.of(Span.plain(plural(d.notReached(), "case") + " with an automated test "
                    + (d.notReached() == 1 ? "was" : "were") + " not reached, so "
                    + (d.notReached() == 1 ? "it stays" : "they stay") + " To Do on this execution.")));
        }
        if (d.noTest() > 0) {
            items.add(List.of(Span.plain(plural(d.noTest(), "case") + " of the plan have no automated test yet and "
                    + (d.noTest() == 1 ? "was" : "were") + " not run; "
                    + (d.noTest() == 1 ? "it stays" : "they stay") + " To Do.")));
        }
        return new Bullets(items);
    }

    private static Table failureTable(List<CaseFailure> failures) {
        List<List<String>> rows = new ArrayList<>();
        for (CaseFailure f : failures) {
            String label = CATEGORY_LABELS.get(f.category());
            rows.add(List.of(f.key(), f.summary() == null ? "" : f.summary(),
                    f.reason().isEmpty() ? label : label + ": " + f.reason(), f.seen()));
        }
        return new Table(List.of("Case", "What it checks", "What happened", "Seen before?"), rows);
    }

    private static boolean allPassed(Digest d) {
        return d.stoppedAt().isEmpty() && d.ran() > 0 && d.passed() + d.warned() == d.ran() && d.notReached() == 0;
    }

    /** The Jira summary of the execution: {@code Automation Test Execution - <plan> (<key>)}. */
    public static String executionSummaryLine(String planKey, String planName) {
        String name = planName == null || planName.isEmpty() ? planKey : planName + " (" + planKey + ")";
        return "Automation Test Execution - " + name;
    }

    /** The short comment posted when the run ends: the headline and the verdict. */
    public static List<Block> commentBlocks(Digest d) {
        String head;
        if (!d.stoppedAt().isEmpty()) {
            head = "Run finished without testing: it stopped at " + d.stoppedAt() + ".";
        } else {
            head = "Run finished: " + (d.passed() + d.warned()) + " of " + d.ran() + " cases passed"
                    + (d.failed() > 0 ? ", " + d.failed() + " failed" : "")
                    + (d.cascade() > 0 ? ", " + d.cascade() + " not judged" : "") + ".";
        }
        int product = d.category(PRODUCT);
        String verdict;
        if (!d.stoppedAt().isEmpty()) {
            verdict = "Nothing here is evidence about the product.";
        } else if (product > 0) {
            verdict = plural(product, "case") + (product == 1 ? " needs" : " need")
                    + " a look by the team; the rest point at the rig or the automation.";
        } else if (d.failed() > 0) {
            verdict = "No failure points at the product: they are rig or automation problems.";
        } else {
            verdict = "Nothing needs attention.";
        }
        return List.of(Paragraph.of(Span.bold(head), Span.plain(" " + verdict + " Details are in the description.")));
    }

    // ------------------------------------------------------------------ helpers

    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /** {@code 2 h 05 min}, {@code 7 min} or {@code 40 s}, as the Python's {@code _duration}. */
    static String duration(double seconds) {
        long total = (long) seconds;
        long minutes = total / 60;
        long secs = total % 60;
        long hours = minutes / 60;
        minutes = minutes % 60;
        if (hours > 0) {
            return hours + " h " + String.format(Locale.ROOT, "%02d", minutes) + " min";
        }
        return minutes > 0 ? minutes + " min" : secs + " s";
    }

    private static String local(OffsetDateTime moment) {
        return moment == null ? "unknown time" : moment.atZoneSameInstant(ZoneId.systemDefault()).format(LOCAL_DATE_TIME);
    }

    private static String shortDate(String iso) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(LOCAL_DAY);
        } catch (DateTimeParseException e) {
            return iso.substring(0, Math.min(10, iso.length()));
        }
    }
}
