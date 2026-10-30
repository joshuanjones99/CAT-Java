package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.report.events.CatEvent;

/**
 * Step 6 against a real run folder: the three reports are written from the run's events, the index
 * last, and a run with no events file is a skipped step that writes nothing.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed events only.
 */
class GenerateReportStepTest {

    @TempDir
    Path dir;

    /** A run context whose events are written to {@code folder}, which is the run folder. */
    private RunContext context(String runId, Path folder) throws IOException {
        RunEvents events = RunEvents.open(folder, Clock.systemUTC());
        return new RunContext(LaunchConfig.forRig(LaunchFixtures.RIG), LaunchFixtures.paths(dir),
                runId, folder, events, new PauseGate(), Clock.systemUTC());
    }

    @Test
    void writesTheThreeReportsFromTheRunsEventsAndTheIndexListsThem() throws IOException {
        Path folder = dir.resolve("aaaaaaaaaaaa");
        RunContext ctx = context("aaaaaaaaaaaa", folder);
        ctx.emit(new CatEvent.LogLine("INFO", "cat", "a line the report reads past", LaunchStepNames.REPORT));

        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.REPORT);
        assertTrue(new GenerateReportStep().run(ctx, step));

        assertEquals(StepResult.COMPLETE, step.state());
        assertEquals(folder.toString(), step.detail());
        for (String name : GenerateReportStep.OUTPUTS) {
            assertTrue(Files.isRegularFile(folder.resolve(name)), name);
        }
        String index = Files.readString(folder.resolve("index.json"));
        assertTrue(index.contains("junit.xml") && index.contains("summary.html"), index);
        assertFalse(index.contains("\"index.json\""), "the index does not list itself");
    }

    @Test
    void aRunWithNoEventsFileIsASkippedStepThatWritesNothing() throws IOException {
        Path folder = dir.resolve("bbbbbbbbbbbb");
        Files.createDirectories(folder);
        // The run's events go to another folder, so the run folder has no events.ndjson.
        RunContext ctx = new RunContext(LaunchConfig.forRig(LaunchFixtures.RIG), LaunchFixtures.paths(dir),
                "bbbbbbbbbbbb", folder, RunEvents.open(dir.resolve("elsewhere"), Clock.systemUTC()),
                new PauseGate(), Clock.systemUTC());
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.REPORT);

        assertTrue(new GenerateReportStep().run(ctx, step));

        assertEquals(StepResult.SKIPPED, step.state());
        assertTrue(step.detail().startsWith("no events.ndjson to read at "), step.detail());
        for (String name : GenerateReportStep.OUTPUTS) {
            assertFalse(Files.exists(folder.resolve(name)), name);
        }
    }

    /** With Xray on, the sidecar's state so far is named in the step's log, or a WARNING when it wrote none. */
    @Test
    void theXraySidecarsStatusIsNamedInTheLog() throws IOException {
        for (boolean written : new boolean[] {false, true}) {
            String id = written ? "cccccccccccc" : "dddddddddddd";
            Path folder = dir.resolve(id);
            RunContext ctx = new RunContext(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).build(),
                    LaunchFixtures.paths(dir), id, folder, RunEvents.open(folder, Clock.systemUTC()), new PauseGate(),
                    Clock.systemUTC());
            if (written) {
                Files.writeString(folder.resolve("xray.json"), "{\"executions\":{\"PETROCORE-1\":{\"key\":"
                        + "\"PETROCORE-9\",\"url\":\"https://jira.invalid/PETROCORE-9\",\"pushed\":2,\"cases\":3}}}");
            }
            assertTrue(new GenerateReportStep().run(ctx, new StepEmitter(ctx, LaunchStepNames.REPORT)));
            ctx.closeEvents();
            String events = Files.readString(folder.resolve("events.ndjson"));
            if (written) {
                assertTrue(events.contains("Xray: PETROCORE-1 -> test execution PETROCORE-9 "
                        + "(https://jira.invalid/PETROCORE-9), 2 of 3 case(s) pushed"), events);
            } else {
                assertTrue(events.contains("Xray: the reporting sidecar has written no status yet"), events);
            }
        }
    }
}
