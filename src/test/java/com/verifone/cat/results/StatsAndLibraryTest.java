package com.verifone.cat.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.report.ReportPaths;

/** Statistics over rows (RunStats) and the library that names runs under a root (ResultsLibrary). */
class StatsAndLibraryTest {

    @TempDir
    Path root;

    private static RunSummary.TestRow t(String nodeid, String status, String exc) {
        return new RunSummary.TestRow(nodeid, status, 1.0, exc);
    }

    private static RunSummary row(String id, String status, String rig, boolean fakePos, String head,
            RunSummary.TestRow... tests) {
        return new RunSummary(id, "2026-10-20T0" + Math.abs(id.hashCode() % 9) + ":00:00Z", rig, fakePos,
                List.of(), status, 0, 0, 0, 0, 0, head, "", List.of(), List.of(tests), List.of());
    }

    @Test
    void segmentsAreNeverMixed() {
        List<RunSummary> rows = List.of(
                row("a", "passed", "lab", false, "", t("tests/a.py::x", "passed", "")),
                row("b", "failed", "lab", true, "", t("tests/a.py::x", "failed", "X")));

        RunStats.SegmentStats rig = RunStats.compute(rows, "rig", nodeid -> false, RunStats.WINDOW);
        RunStats.SegmentStats fake = RunStats.compute(rows, "fake_pos", nodeid -> false, RunStats.WINDOW);

        assertEquals(1, rig.runs());
        assertEquals(List.of(1.0), rig.passRates());
        assertEquals(1, fake.runs());
        assertEquals(List.of(0.0), fake.passRates());
    }

    @Test
    void cancelledAndIncompleteRunsAreExcludedAndCounted() {
        List<RunSummary> rows = List.of(
                row("a", "cancelled", "lab", false, "", t("tests/a.py::x", "passed", "")),
                row("b", "incomplete", "lab", false, "", t("tests/a.py::x", "failed", "X")),
                row("c", "passed", "lab", false, "", t("tests/a.py::x", "passed", "")));

        RunStats.SegmentStats stats = RunStats.compute(rows, "rig", nodeid -> false, RunStats.WINDOW);

        assertEquals(3, stats.runs());
        assertEquals(1, stats.finished());
        assertEquals(1, stats.cancelled());
        assertEquals(1, stats.incomplete());
        assertEquals(List.of(1.0), stats.passRates());
    }

    @Test
    void placeholdersAreExcludedFromEveryRate() {
        List<RunSummary> rows = List.of(
                row("a", "failed", "lab", false, "", t("tests/a.py::x", "passed", ""),
                        t("tests/placeholder.py::y", "failed", "Y")));

        RunStats.SegmentStats stats = RunStats.compute(rows, "rig",
                nodeid -> nodeid.startsWith("tests/placeholder.py"), RunStats.WINDOW);

        assertEquals(List.of(1.0), stats.passRates());
        assertEquals(List.of("tests/placeholder.py::y"), stats.unmigratedExcluded());
        assertTrue(stats.reasons().isEmpty());
    }

    @Test
    void aFlakeSaysWhetherItsCommitMatched() {
        List<RunSummary> rows = List.of(
                row("b", "failed", "lab", false, "abc", t("tests/a.py::x", "failed", "X")),
                row("a", "passed", "lab", false, "abc", t("tests/a.py::x", "passed", "")));

        RunStats.SegmentStats stats = RunStats.compute(rows, "rig", nodeid -> false, RunStats.WINDOW);

        assertEquals(1, stats.flakes().size());
        assertEquals(Boolean.TRUE, stats.flakes().get(0).sameCommit());
    }

    @Test
    void aFlakeWithNoRecordedCommitSaysSo() {
        List<RunSummary> rows = List.of(
                row("b", "failed", "lab", false, "", t("tests/a.py::x", "failed", "X")),
                row("a", "passed", "lab", false, "", t("tests/a.py::x", "passed", "")));

        RunStats.SegmentStats stats = RunStats.compute(rows, "rig", nodeid -> false, RunStats.WINDOW);

        assertNull(stats.flakes().get(0).sameCommit());
    }

    @Test
    void passRatesRunOldestToNewest() {
        List<RunSummary> newestFirst = List.of(
                row("c", "failed", "lab", false, "", t("tests/a.py::x", "failed", "X")),
                row("b", "passed", "lab", false, "", t("tests/a.py::x", "passed", "")),
                row("a", "passed", "lab", false, "", t("tests/a.py::x", "passed", "")));

        RunStats.SegmentStats stats = RunStats.compute(newestFirst, "rig", nodeid -> false, 2);

        assertEquals(2, stats.passRates().size(), "the window keeps the newest two");
        assertEquals(List.of(1.0, 0.0), stats.passRates());
        assertEquals("c", stats.window().get(0).runId());
    }

    @Test
    void theReasonsCountTheFailuresOfTheWindow() {
        List<RunSummary> rows = List.of(
                row("a", "failed", "lab", false, "", t("tests/a.py::x", "failed", "AssertionError"),
                        t("tests/a.py::y", "error", "")));

        RunStats.SegmentStats stats = RunStats.compute(rows, "rig", nodeid -> false, RunStats.WINDOW);

        assertEquals(List.of(new RunStats.Reason("AssertionError", 1),
                new RunStats.Reason("(no reason recorded)", 1)), stats.reasons());
    }

    @Test
    void runPathsAreNamedOnlyAsSingleFoldersUnderTheRoot() throws Exception {
        ResultsLibrary library = new ResultsLibrary(root, root.resolve("index.json"));
        java.nio.file.Files.createDirectories(root.resolve("r1"));
        java.nio.file.Files.writeString(root.resolve("r1").resolve(ReportPaths.EVENTS_FILE), "");

        assertTrue(library.runFolder("r1").isPresent());
        for (String bad : List.of("", " ", ".", "..", "a/b", "a\\b", "c:x", "missing")) {
            assertEquals(Optional.empty(), library.runFolder(bad), "'" + bad + "' must not name a run");
        }
        assertFalse(library.load("..", false).isPresent());
    }

    @Test
    void aMissingRunLoadsAsEmpty() throws Exception {
        ResultsLibrary library = new ResultsLibrary(root, root.resolve("index.json"));

        assertEquals(Optional.empty(), library.load("nope", true));
    }
}
