package com.verifone.cat.runservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The incremental fold of events.ndjson, and the bounded tail of run.log. */
class RunProgressTest {

    @TempDir
    Path tmp;

    private Path events() {
        return tmp.resolve("events.ndjson");
    }

    private void append(String text) throws IOException {
        Files.writeString(events(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Test
    void aLineStillBeingWrittenWaitsForItsNewline() throws IOException {
        RunProgress progress = new RunProgress(tmp);
        append("{\"type\":\"run.started\",\"run_id\":\"r1\",\"at\":\"2026-10-24T10:00:00Z\"}");

        progress.refresh();
        assertFalse(progress.snapshot().started());

        append("\n");
        progress.refresh();
        assertTrue(progress.snapshot().started());
        assertEquals("r1", progress.snapshot().runId());
    }

    @Test
    void aRefreshFoldsOnlyWhatIsNew() throws IOException {
        RunProgress progress = new RunProgress(tmp);
        append("{\"type\":\"test.finished\",\"nodeid\":\"a\",\"outcome\":\"passed\",\"warnings\":0}\n");
        progress.refresh();
        assertEquals(1, progress.snapshot().passed());

        append("{\"type\":\"test.finished\",\"nodeid\":\"b\",\"outcome\":\"error\",\"warnings\":0,"
                + "\"detail\":\"line one\\nline two\"}\n");
        progress.refresh();
        progress.refresh();

        assertEquals(1, progress.snapshot().passed());
        // With no plan the counters are the reducer's test counts, which count only a "failed" outcome (the
        // Python's _apply_test_finished); the error is still listed under Failures.
        assertEquals(0, progress.snapshot().failed());
        assertEquals("error", progress.snapshot().failures().get(0).outcome());
        assertEquals("line one", progress.snapshot().failures().get(0).detail());
    }

    @Test
    void malformedLinesAreSkippedAndAFinishedRunUsesItsOwnCounts() throws IOException {
        RunProgress progress = new RunProgress(tmp);
        append("not json at all\n[1,2]\n"
                + "{\"type\":\"run.finished\",\"outcome\":\"passed\",\"passed\":7,\"failed\":0,\"warned\":2,"
                + "\"cancelled\":false,\"elapsed\":41.5}\n");
        progress.refresh();

        RunProgress.Snapshot snap = progress.snapshot();
        assertTrue(snap.finished());
        assertEquals(7, snap.passed());
        assertEquals(2, snap.warned());
        assertEquals(0, snap.percent(), 0.001); // case-based (percent_complete): no plan, no collected count
        assertEquals(41.5, snap.seconds(java.time.Instant.now()));
    }

    @Test
    void theStepsAreInRunOrderWithTheCurrentOneRunning() throws IOException {
        RunProgress progress = new RunProgress(tmp);
        append("{\"type\":\"step.entered\",\"step\":\"System Checks\"}\n"
                + "{\"type\":\"step.finished\",\"step\":\"System Checks\",\"state\":\"complete\",\"detail\":\"\"}\n"
                + "{\"type\":\"step.entered\",\"step\":\"Connections\"}\n"
                + "{\"type\":\"step.progress\",\"step\":\"Connections\",\"done\":1,\"total\":4}\n");
        progress.refresh();

        RunProgress.Snapshot snap = progress.snapshot();
        assertEquals("Connections", snap.currentStep());
        assertEquals("running", snap.steps().get(1).status());
        assertEquals("pending", snap.steps().get(2).status());
        assertEquals(0, snap.percent(), 0.001); // case-based, not the step's own progress
    }

    @Test
    void theLogTailIsBoundedAndCutsNoLineInTwo() throws IOException {
        Path log = tmp.resolve("run.log");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60_000; i++) {
            text.append("line ").append(i).append('\n');
        }
        Files.writeString(log, text.toString(), StandardCharsets.UTF_8);

        java.util.List<String> lines = RunLogTail.tail(tmp, 3);
        assertEquals(java.util.List.of("line 59997", "line 59998", "line 59999"), lines);

        java.util.List<String> all = RunLogTail.tail(tmp, RunLogTail.LINES);
        assertEquals(RunLogTail.LINES, all.size());
        for (String line : all) {
            assertTrue(line.matches("line [0-9]+"), line);
        }
        assertEquals("line 59999", all.get(all.size() - 1));
    }

    @Test
    void noRunLogMeansNoLines() throws IOException {
        assertTrue(RunLogTail.tail(tmp, RunLogTail.LINES).isEmpty());
    }
}
