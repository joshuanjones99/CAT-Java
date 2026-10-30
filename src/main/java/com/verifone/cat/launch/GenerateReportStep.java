package com.verifone.cat.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.report.ArtifactIndex;
import com.verifone.cat.xray.XrayReporter;
import com.verifone.cat.report.JunitXmlWriter;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.html.HtmlReport;

/**
 * Step 6, Generate Report: JUnit XML, the HTML summary and the artifact index, built from the run's
 * {@code events.ndjson}. The Python CAT's {@code cat.launch.steps.report}.
 *
 * <p>The writers read the whole file that the launcher wrote as the run went, so the file is complete
 * here: every line is flushed as it is written ({@code report.NdjsonWriter}). The artifact index is
 * written last, so it lists the two reports too. The writers run on one worker thread, bounded by
 * {@link #WRITE_TIMEOUT}; a timeout is a failed step, and what they had written is partial.
 *
 * <p>With Xray reporting on, the sidecar's state so far ({@code xray.json}) is named in the step's log, one line per
 * execution ({@link XrayReporter#statusLines}), or a WARNING when the sidecar has written none yet: how the operator
 * finds the execution without leaving CAT. The sidecar is still running here; it closes the execution after Cleanup.
 *
 * <p>Not carried: the event tee's dropped-event count (CAT-Java has no socket tee).
 * The JaCoCo coverage report runs first when coverage is on ({@link CoverageLaunch#collect}); asked for and
 * not delivered, it leaves this step failed with {@link CoverageLaunch#FAILED_DETAIL}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the report is built from fake-backed run events only. The Python
 * CAT's summary and JUnit output were checked against a real run's events (HANDOFF.md §4).
 */
final class GenerateReportStep implements LaunchStep {

    /** Bound on the three writers, as the Python CAT's {@code REPORT_WRITE_TIMEOUT}. */
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(120);

    /** The three files this step must have written, in the order it checks them. */
    static final List<String> OUTPUTS = List.of(
            JunitXmlWriter.JUNIT_FILE, HtmlReport.SUMMARY_FILE, ArtifactIndex.INDEX_FILE);

    @Override
    public String name() {
        return LaunchStepNames.REPORT;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        // [coverage slice, wave 16] JaCoCo coverage first, when asked (launch.CoverageLaunch): before the writers, so
        // the index lists it. It reboots the Commander and waits for it, which is why it is here, before Cleanup.
        boolean coverageOk = CoverageLaunch.collect(ctx, step);
        Path folder = ctx.runFolder();
        Path events = folder.resolve(ReportPaths.EVENTS_FILE);
        if (!Files.isRegularFile(events)) {
            step.skipStep("no events.ndjson to read at " + folder);
            step.warn("reports", events + " does not exist, so there is nothing to build them from. "
                    + "The launcher writes every event to that path; an absent file means it never opened.");
            return true;
        }

        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cat-report-writer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Void> written = worker.submit(writeAll(events, folder));
            try {
                written.get(WRITE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                written.cancel(true);
                step.check(false, "reports", "the writers did not finish within "
                        + WRITE_TIMEOUT.toSeconds() + "s reading " + events + "; whatever they had written is partial",
                        true);
                return step.fail("report writers did not finish within " + WRITE_TIMEOUT.toSeconds() + "s");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                step.check(false, "reports", "the writers failed: " + cause, true);
                return step.fail("report writers failed: " + cause.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                written.cancel(true);
                return step.fail("interrupted while the reports were written");
            }
        } finally {
            worker.shutdownNow();
        }

        // Stat each file rather than trusting that the writers returned: a PASS on the strength of
        // no exception is not evidence that the file is there.
        boolean allWritten = true;
        for (String name : OUTPUTS) {
            Path written = folder.resolve(name);
            boolean present = Files.isRegularFile(written);
            step.check(present, name, written.toString(), true);
            allWritten &= present;
        }
        if (!allWritten) {
            return step.fail("a report file is missing from " + folder);
        }
        if (ctx.config().xrayReport()) {
            JsonNode status = XrayReporter.readStatus(folder);
            if (status == null) {
                step.log("Xray: the reporting sidecar has written no status yet", "WARNING");
            } else {
                for (String line : XrayReporter.statusLines(status)) {
                    step.log(line, "INFO");
                }
            }
        }
        step.setDetail(folder.toString());
        if (!coverageOk) {
            // [coverage slice, wave 16] Asked for and not delivered: the reports stand, but a complete step would say
            // the coverage did too. The run still goes on to Cleanup, as the Python's.
            step.fail(folder + CoverageLaunch.FAILED_DETAIL);
        }
        return true;
    }

    /** The three writers, in order: the index last, so it catalogues the reports above. */
    private static Callable<Void> writeAll(Path events, Path folder) {
        return () -> {
            JunitXmlWriter.fromEvents(events).write(folder);
            HtmlReport.fromEvents(events).write(folder);
            ArtifactIndex.write(folder);
            return null;
        };
    }
}
