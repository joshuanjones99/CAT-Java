package com.verifone.cat.report.events;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.report.ReportPaths;

/**
 * {@code run.log}: the whole run as a person reads it, one timestamped line per thing that happened.
 *
 * <p>The Python CAT's {@code cat.report.run_log.RunLogWriter}. Every step's start and end, every
 * sub-step's result, every case's start and verdict with each of its {@code .feature} steps, and
 * every ERROR the run logged (plus any {@code cat.pause} line). Each case's own folder holds the long
 * form ({@code steps.log}); this file is the run's summary of them.
 *
 * <pre>
 * 16:40:01  RUN       a1b2c3d4e5f6 on lab: PETROCORE-70960 (Charity Donations Indoor)
 * 16:40:01  STEP 1    System Checks: started
 * 16:40:02              PASS  petroapps current — up to date
 * 16:40:10  STEP 1    System Checks: FAILED in 9s — rig hardware not ready: ...
 * 16:45:33  CASE      PETROCORE-61334: PASSED in 32.1s  (see PETROCORE-61334\steps.log)
 * </pre>
 *
 * <p>Feed it the same events {@link EventEmitter} writes, with the envelope's {@code at}. Never
 * raises: a full disk costs the log, not the run.
 *
 * <p>Two Python details the port keeps as they are: the step names are
 * {@code cat.state.STEP_NAMES} (CAT-Java's {@code runservice.RunState.STEP_NAMES}), and
 * case folder names come from a per-writer {@link ReportPaths.CaseFolders}, one memo per run.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class RunLogWriter implements Closeable {

    /** The file beside {@code events.ndjson}. */
    public static final String RUN_LOG = "run.log";

    /** {@code cat.state.STEP_NAMES} ({@code runservice.RunState.STEP_NAMES}); a step's tag is its position here. */
    static final List<String> STEP_NAMES = com.verifone.cat.runservice.RunState.STEP_NAMES;

    /** A settled row's word, as {@code steps.log} spells it. */
    private static final Map<String, String> WORDS = Map.of(
            "passed", "PASS",
            "failed", "FAIL",
            "warned", "WARN",
            "skipped", "SKIP");

    /** A case's verdict, by {@code test.finished} outcome. */
    private static final Map<String, String> VERDICTS = Map.of(
            "passed", "PASSED",
            "failed", "FAILED",
            "skipped", "SKIPPED",
            "error", "ERROR",
            "timeout", "TIMED OUT",
            "repairing", "PASSED WITH WARNINGS");

    private static final Set<String> LEVELS_KEPT = Set.of("ERROR", "CRITICAL");

    private static final int LINE_WIDTH = 240;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE dd MMMM yyyy", Locale.ENGLISH);

    private static final Logger LOG = LoggerFactory.getLogger(RunLogWriter.class);

    private final Path path;
    private final ReportPaths.CaseFolders folders = new ReportPaths.CaseFolders();
    private final Map<String, String> summaries = new HashMap<>();
    private Writer file;
    private boolean failed;
    private boolean dated;
    private boolean started;

    /** Writes to {@code path}, which is opened (appending) on the first line. */
    public RunLogWriter(Path path) {
        this.path = path;
    }

    /**
     * Formats one event as zero or more lines. Never throws.
     *
     * @param at    the event's envelope time, as {@link EventEmitter#emit} returned it
     * @param event the event as emitted
     */
    public void emit(Instant at, CatEvent event) {
        try {
            emitOne(at, event);
        } catch (RuntimeException e) { // a formatting slip must not stop the run
            LOG.warn("run.log skipped a {}: {}", event.getClass().getSimpleName(), e.toString());
        }
    }

    private void emitOne(Instant at, CatEvent event) {
        if (event instanceof CatEvent.RunStarted run) {
            runStarted(at, run);
        } else if (event instanceof CatEvent.PlanResolved plan) {
            for (CatEvent.PlanCaseDescriptor c : plan.cases()) {
                summaries.put(c.key(), c.summary());
            }
        } else if (event instanceof CatEvent.StepEntered entered) {
            write(at, stepTag(entered.step()), entered.step() + ": started");
        } else if (event instanceof CatEvent.StepCheck check) {
            stepCheck(at, check);
        } else if (event instanceof CatEvent.StepFinished stepDone) {
            stepFinished(at, stepDone);
        } else if (event instanceof CatEvent.TestStarted begun) {
            write(at, "CASE", caseText(begun.nodeid(), true));
        } else if (event instanceof CatEvent.TestStep step) {
            testStep(at, step);
        } else if (event instanceof CatEvent.TestValidation validation) {
            String outcome = validation.passed() ? "passed" : "failed";
            String text = "validation: " + validation.kind() + " against "
                    + (validation.name().isEmpty() ? "its baseline" : validation.name());
            row(at, outcome, text, validation.passed() ? "" : firstLine(validation.diff()));
        } else if (event instanceof CatEvent.TestFinished testDone) {
            testFinished(at, testDone);
        } else if (event instanceof CatEvent.RecoveryRung rung) {
            write(at, "RECOVER", "rung " + rung.rung() + " after " + caseText(rung.nodeid(), false)
                    + ": " + rung.outcome().wire());
        } else if (event instanceof CatEvent.SimulatorState sim) {
            // A start's result is already its step 3 row; a stop is nobody's row.
            if ("stopped".equals(sim.state())) {
                String detail = sim.detail().isEmpty() ? "" : " — " + firstLine(sim.detail());
                write(at, "SIM", sim.name() + ": " + sim.state() + detail);
            }
        } else if (event instanceof CatEvent.LogLine line) {
            // Not the launcher's own step lines (logger `cat`): a failed check is already its
            // FAIL row, and a step's failure its STEP line. A pause is kept at any level.
            if ("cat.pause".equals(line.logger())
                    || (LEVELS_KEPT.contains(line.level()) && !"cat".equals(line.logger()))) {
                write(at, line.level(), "[" + line.logger() + "] " + firstLine(line.message()));
            }
        } else if (event instanceof CatEvent.RunFinished runDone) {
            runFinished(at, runDone);
        }
    }

    private void runStarted(Instant at, CatEvent.RunStarted run) {
        if (started) {
            // The first run.started names the run; a restatement adds nothing a reader needs.
            return;
        }
        started = true;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < run.testPlans().size(); i++) {
            String key = run.testPlans().get(i);
            String name = i < run.planNames().size() ? run.planNames().get(i) : "";
            parts.add(name.isEmpty() ? key : key + " (" + name + ")");
        }
        String plans = String.join(", ", parts);
        String what = plans.isEmpty() ? run.suite() : plans;
        String fake = run.fakePos() ? "  [FAKE POS: evidence about CAT, not about a rig]" : "";
        write(at, "RUN", run.runId() + " on " + run.rig() + ": " + what + fake);
    }

    private void stepCheck(Instant at, CatEvent.StepCheck check) {
        if (SubstepOutcome.RUNNING.equals(check.outcome())) {
            write(at, "", "            ....  " + check.label() + " — started");
            return;
        }
        String took = check.elapsed() != 0 ? "  (" + took(check.elapsed()) + ")" : "";
        row(at, check.outcome(), check.label() + took, firstLine(check.detail()));
    }

    private void stepFinished(Instant at, CatEvent.StepFinished finished) {
        String verdict = "complete".equals(finished.state())
                ? "COMPLETE"
                : finished.state().toUpperCase(Locale.ROOT);
        String took = "skipped".equals(finished.state()) ? "" : " in " + took(finished.elapsed());
        String detail = finished.detail().isEmpty() ? "" : " — " + firstLine(finished.detail());
        write(at, stepTag(finished.step()), finished.step() + ": " + verdict + took + detail);
    }

    private void testStep(Instant at, CatEvent.TestStep step) {
        if (SubstepOutcome.RUNNING.equals(step.outcome())) {
            return;
        }
        String took = "skipped".equals(step.outcome()) ? "" : "  (" + took(step.duration()) + ")";
        String label = (step.index() + ". " + step.keyword() + " " + step.text()).replace("  ", " ");
        row(at, step.outcome(), label + took, firstLine(step.detail()));
    }

    private void testFinished(Instant at, CatEvent.TestFinished finished) {
        String outcome = finished.outcome().wire();
        String verdict = VERDICTS.getOrDefault(outcome, outcome.toUpperCase(Locale.ROOT));
        String where = ReportPaths.caseKey(finished.nodeid()).isEmpty()
                ? ""
                : "  (see " + folders.name(finished.nodeid()) + "\\steps.log)";
        String reason = "";
        if (!"PASSED".equals(verdict) && (!finished.excType().isEmpty() || !finished.detail().isEmpty())) {
            String head = firstLine(finished.detail());
            reason = finished.excType().isEmpty()
                    ? " — " + head
                    : " — " + finished.excType() + ": " + head;
        }
        write(at, "CASE", caseText(finished.nodeid(), false) + ": " + verdict + " in "
                + took(finished.duration()) + reason + where);
    }

    private void runFinished(Instant at, CatEvent.RunFinished finished) {
        // pytest's session end (or the launcher's, standing in for it): steps 6 and 7 follow it.
        String cancelled = finished.cancelled() ? " (stopped by the operator)" : "";
        write(at, "RUN", "tests finished" + cancelled + ": " + finished.passed() + " passed, "
                + finished.failed() + " failed, " + finished.warned()
                + " passed with warnings, in " + took(finished.elapsed()));
    }

    /** A settled row: the word, then the text, then the detail after an em dash. */
    private void row(Instant at, String outcome, String text, String detail) {
        String word = WORDS.getOrDefault(outcome, outcome.toUpperCase(Locale.ROOT));
        write(at, "", String.format(Locale.ROOT, "            %-4s  %s", word, text)
                + (detail.isEmpty() ? "" : " — " + detail));
    }

    private static String stepTag(String step) {
        int index = STEP_NAMES.indexOf(step);
        return index >= 0 ? "STEP " + (index + 1) : "STEP";
    }

    /** The case's key and summary, or the node id when it names no case. */
    private String caseText(String nodeid, boolean withSummary) {
        String key = ReportPaths.caseKey(nodeid);
        if (key.isEmpty()) {
            return nodeid;
        }
        String summary = summaries.getOrDefault(key, "");
        return withSummary && !summary.isEmpty() ? key + ": " + summary : key;
    }

    private void write(Instant at, String tag, String text) {
        if (failed) {
            return;
        }
        try {
            if (file == null) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                file = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
            }
            ZonedDateTime local = at.atZone(ZoneId.systemDefault());
            if (!dated) {
                file.write("CAT run log — " + DATE.format(local) + "\n\n");
                dated = true;
            }
            String clock = CLOCK.format(local);
            file.write(tag.isEmpty()
                    ? clock + "  " + text + "\n"
                    : String.format(Locale.ROOT, "%s  %-9s %s", clock, tag, text) + "\n");
            file.flush();
        } catch (IOException e) { // the run goes on without its log
            failed = true;
            LOG.warn("run.log could not be written ({}): {}", path, e.getMessage());
        }
    }

    /** The first line of {@code text}, stripped, cut to 240 characters with an ellipsis. */
    static String firstLine(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        String line = stripped.lines().findFirst().orElse("");
        return line.length() <= LINE_WIDTH ? line : line.substring(0, LINE_WIDTH - 1) + "…";
    }

    /** {@code 0.8s}, {@code 32s}, {@code 2m 05s}, {@code 1h 02m}. */
    static String took(double seconds) {
        if (seconds < 60) {
            return seconds < 10
                    ? String.format(Locale.ROOT, "%.1fs", seconds)
                    : String.format(Locale.ROOT, "%.0fs", seconds);
        }
        long whole = (long) seconds;
        long minutes = whole / 60;
        long secs = whole % 60;
        if (minutes < 60) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
    }

    /** Closes the file, if one was opened. */
    @Override
    public void close() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException e) {
                LOG.debug("run.log close failed: {}", e.getMessage());
            }
            file = null;
        }
    }
}
