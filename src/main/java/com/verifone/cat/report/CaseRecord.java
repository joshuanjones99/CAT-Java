package com.verifone.cat.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One case's run: its steps, what it captured, its verdict, and its folder.
 *
 * <p>The data half of the Python CAT's {@code petrotest.report.case_record}. Each case a
 * run reaches gets {@code <run>/PETROCORE-<n>/}, holding {@code steps.log} (written by
 * {@link CaseLog}), the receipts and TLogs it captured, and any failure evidence, so an
 * operator can read a case without the CAT that ran it.
 *
 * <p>Left out, on purpose, is the Python's glue to pytest: the module-level "current case",
 * the pytest-bdd hooks that drive these methods, and the step and validation listeners the
 * Run view subscribes to. Those belong to whatever runs the steps. This class is driven
 * through {@link #beginStep}, {@link #endStep}, {@link #failStep} and {@link #finish}, in
 * the order the Python hooks call them.
 *
 * <p>Validation verdicts and captures are bounded and written as the Python writes them.
 * UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class CaseRecord {

    /** The human-readable log in each case's folder. */
    public static final String STEPS_LOG = "steps.log";

    /** The most characters of a failure's detail kept in {@code steps.log}; the full text is in the run's log. */
    static final int DETAIL_CHARS = 4000;

    /**
     * The most characters of a validation's diff, expected or actual text. Generous, since
     * the Validations view shows the diff whole, but bounded, since it is a single event.
     */
    public static final int VALIDATION_DIFF_CHARS = 20_000;

    /** A file a case wrote into its folder, and what it is. */
    public record Capture(String fileName, String what) {}

    /**
     * A validation's verdict: what was compared, against which baseline, and how.
     *
     * @param kind receipt, tlog, report, packets, payload or overlay
     * @param name the baseline or reference it was compared with, or what names the check
     * @param diff how it differed, bounded; empty for a pass
     * @param expected the baseline's text, where the caller had it; may be empty
     * @param actual what was captured, where the caller had it; may be empty
     * @param nodeid the pytest node of the case that checked it, or empty
     * @param caseKey that case's key (PETROCORE-n), or empty
     */
    public record ValidationRecord(
            String kind, String name, boolean passed, String diff,
            String expected, String actual, String nodeid, String caseKey) {}

    /** One step of a case and how it went. */
    public static final class StepRecord {

        private final int index;
        private final String keyword;
        private final String text;
        private final Instant startedAt;
        private final long startNanos;
        private String outcome = "running";
        private double duration;
        private String detail = "";

        StepRecord(int index, String keyword, String text, Instant startedAt, long startNanos) {
            this.index = index;
            this.keyword = keyword.strip();
            this.text = text;
            this.startedAt = startedAt;
            this.startNanos = startNanos;
        }

        public int index() {
            return index;
        }

        public String keyword() {
            return keyword;
        }

        public String text() {
            return text;
        }

        /** {@code keyword text}, as the step reads in the feature file. */
        public String label() {
            return (keyword + " " + text).strip();
        }

        public Instant startedAt() {
            return startedAt;
        }

        /** {@code passed}, {@code failed}, {@code skipped}, or {@code running} while it runs. */
        public String outcome() {
            return outcome;
        }

        /** Seconds the step took; 0 until it ends. */
        public double duration() {
            return duration;
        }

        public String detail() {
            return detail;
        }
    }

    private final String nodeid;
    private final String key;
    private final Path folder;
    private final Instant startedAt;
    private final List<StepRecord> steps = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();
    private final List<String> pendingFailures = new ArrayList<>();
    private final List<ValidationRecord> validations = new ArrayList<>();
    private String title = "";
    private Instant finishedAt;
    private String outcome = "running";
    private String detail = "";
    private String traceback = "";

    /**
     * @param nodeid the test's node id
     * @param key the case key, {@code PETROCORE-<n>}
     * @param folder the case's folder, usually {@code <run>/<key>/}
     * @param startedAt when the case started
     */
    public CaseRecord(String nodeid, String key, Path folder, Instant startedAt) {
        this.nodeid = nodeid;
        this.key = key;
        this.folder = folder;
        this.startedAt = startedAt;
    }

    /** The feature's title, shown after the key in {@code steps.log}. */
    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String nodeid() {
        return nodeid;
    }

    public String key() {
        return key;
    }

    public Path folder() {
        return folder;
    }

    public String title() {
        return title;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** When the case finished, or {@code null} while it runs. */
    public Instant finishedAt() {
        return finishedAt;
    }

    public List<StepRecord> steps() {
        return List.copyOf(steps);
    }

    public List<Capture> captures() {
        return List.copyOf(captures);
    }

    public List<ValidationRecord> validations() {
        return List.copyOf(validations);
    }

    /** {@code passed}, {@code failed}, {@code skipped}, {@code error}, {@code xfailed}, or {@code running}. */
    public String outcome() {
        return outcome;
    }

    /** Why the case did not pass: the exception's own message. */
    public String detail() {
        return detail;
    }

    /** pytest's full traceback, kept at the end of {@code steps.log}. */
    public String traceback() {
        return traceback;
    }

    /** The step currently running, or empty when none is. */
    public Optional<StepRecord> runningStep() {
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        StepRecord last = steps.get(steps.size() - 1);
        return "running".equals(last.outcome) ? Optional.of(last) : Optional.empty();
    }

    /** A step has started. Any validation failure left over from before it is cleared. */
    public StepRecord beginStep(int index, String keyword, String text) {
        pendingFailures.clear();
        StepRecord step = new StepRecord(index, keyword, text, Instant.now(), System.nanoTime());
        steps.add(step);
        return step;
    }

    /**
     * The running step has returned normally. It reads FAIL when a validation failed inside
     * it, though it returned: the case carried on, as PATS does, and fails at its end.
     */
    public void endStep() {
        runningStep().ifPresent(step -> {
            step.duration = elapsed(step);
            if (pendingFailures.isEmpty()) {
                step.outcome = "passed";
            } else {
                step.outcome = "failed";
                step.detail = boundDetail("validation failed; the case carried on, as PATS does:\n"
                        + String.join("\n", pendingFailures));
                pendingFailures.clear();
            }
        });
    }

    /** The running step raised {@code message}. */
    public void failStep(String message) {
        runningStep().ifPresent(step -> {
            step.duration = elapsed(step);
            step.outcome = "failed";
            step.detail = boundDetail(message);
        });
    }

    /**
     * Lists a step the failing step stopped before. It is {@code skipped} with the detail
     * "not reached", so {@code steps.log} reads the whole scenario.
     */
    public void addUnreachedStep(int index, String keyword, String text) {
        StepRecord step = new StepRecord(index, keyword, text, Instant.now(), System.nanoTime());
        step.outcome = "skipped";
        step.detail = "not reached";
        steps.add(step);
    }

    /** A check in the running step failed and the case carries on (a PATS validation). */
    public void noteStepFailure(String message) {
        pendingFailures.add(message);
    }

    /**
     * Ends the case with its verdict. A step still running is marked failed, since the test
     * ended inside it (a setup error, say).
     */
    public void finish(String outcome, String detail, String traceback, Instant finishedAt) {
        this.outcome = outcome;
        this.detail = detail == null ? "" : detail;
        this.traceback = traceback == null ? "" : traceback;
        this.finishedAt = finishedAt;
        for (StepRecord step : steps) {
            if ("running".equals(step.outcome)) {
                step.outcome = "failed";
                step.duration = elapsed(step);
                if (step.detail.isEmpty()) {
                    step.detail = "the test ended during this step";
                }
            }
        }
    }

    /**
     * Writes one capture into the folder under {@code stem + suffix}, numbered {@code -2},
     * {@code -3} and so on when that name is taken.
     *
     * @return the file name written
     * @throws IOException when the folder or file cannot be written
     */
    public String write(String stem, String suffix, byte[] data, String what) throws IOException {
        String name = nextName(stem, suffix);
        Files.createDirectories(folder);
        Files.write(folder.resolve(name), data);
        captures.add(new Capture(name, what));
        return name;
    }

    /**
     * Keeps a receipt the case captured, as captured (before normalising), as
     * {@code receipt.txt}. A failure to write it leaves the verdict standing without the copy.
     *
     * @return the file name, or empty when it could not be written
     */
    public Optional<String> recordReceipt(String text, String name) {
        try {
            return Optional.of(write("receipt", ".txt", text.getBytes(StandardCharsets.UTF_8),
                    what("receipt", name)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Keeps a transaction log the case read, as {@code tlog.xml}. As for receipts, a failure
     * to write it does not fail the case.
     *
     * @return the file name, or empty when it could not be written
     */
    public Optional<String> recordTlog(String xml, String name) {
        try {
            return Optional.of(write("tlog", ".xml", xml.getBytes(StandardCharsets.UTF_8),
                    what("transaction log", name)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Marks the newest receipt this case kept with how its comparison went, so the
     * {@code steps.log} line for it says whether it matched.
     */
    public void markNewestReceipt(boolean passed) {
        for (int position = captures.size() - 1; position >= 0; position--) {
            Capture capture = captures.get(position);
            if (capture.fileName().startsWith("receipt")) {
                String word = passed ? "matches" : "DIFFERS (the failing step has the diff)";
                captures.set(position, new Capture(capture.fileName(), capture.what() + ": " + word));
                return;
            }
        }
    }

    /**
     * Records a validation's verdict, passed or failed, with its diff and both texts bounded.
     *
     * @param caseKey the case the caller names, when it knows better than this record; else
     *                this case's key
     */
    public ValidationRecord recordValidation(String kind, String name, boolean passed,
            String diff, String expected, String actual, String caseKey) {
        ValidationRecord record = new ValidationRecord(
                kind, name, passed,
                bounded(diff.replaceAll("^\\n+|\\n+$", "")),
                bounded(expected),
                bounded(actual),
                nodeid,
                caseKey == null || caseKey.isEmpty() ? key : caseKey);
        validations.add(record);
        return record;
    }

    private String nextName(String stem, String suffix) {
        String name = stem + suffix;
        int n = 1;
        while (isTaken(name)) {
            n++;
            name = stem + "-" + n + suffix;
        }
        return name;
    }

    private boolean isTaken(String name) {
        return captures.stream().anyMatch(c -> c.fileName().equals(name));
    }

    private static String what(String kind, String name) {
        return "the " + kind + " the case captured" + (name == null || name.isEmpty() ? "" : ", compared with " + name);
    }

    private double elapsed(StepRecord step) {
        return (System.nanoTime() - step.startNanos) / 1_000_000_000.0;
    }

    /**
     * {@code text} as a failure's detail: stripped, and cut to {@link #DETAIL_CHARS} with
     * {@code ...} when longer.
     */
    static String boundDetail(String text) {
        String stripped = text.strip();
        return stripped.length() <= DETAIL_CHARS
                ? stripped
                : stripped.substring(0, DETAIL_CHARS - 3) + "...";
    }

    /**
     * {@code text} cut to {@link #VALIDATION_DIFF_CHARS}, with a note of how much was cut,
     * so a reader knows the text is not the whole of it.
     */
    static String bounded(String text) {
        if (text.length() <= VALIDATION_DIFF_CHARS) {
            return text;
        }
        int cut = text.length() - VALIDATION_DIFF_CHARS;
        return text.substring(0, VALIDATION_DIFF_CHARS).stripTrailing()
                + String.format(Locale.ROOT, "\n… truncated (%,d more characters)", cut);
    }
}
