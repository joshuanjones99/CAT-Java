package com.verifone.cat.results;

import static com.verifone.cat.results.RunFixtures.finished;
import static com.verifone.cat.results.RunFixtures.started;
import static com.verifone.cat.results.RunFixtures.test;
import static com.verifone.cat.results.RunFixtures.writeRun;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.report.ReportPaths;

/** The index row's JSON shape (RunSummary) and the stat-keyed cache (RunIndex). */
class IndexAndJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    private static RunSummary sample() {
        return new RunSummary("r1", "2026-10-20T09:00:00Z", "lab", false, List.of("PETROCORE-1"),
                "failed", 3, 1, 0, 2, 12.5, "abc123", "pytest x",
                List.of(new RunSummary.StepRow("Tests Run", "complete", 10.0)),
                List.of(new RunSummary.TestRow("tests/a.py::one", "failed", 1.25, "AssertionError")),
                List.of("Cadence"));
    }

    @Test
    void theRowRoundTripsThroughTheIndexShape() throws Exception {
        RunSummary row = sample();

        RunSummary back = RunSummary.fromJson(MAPPER.readTree(MAPPER.writeValueAsString(row.toJson(MAPPER))));

        assertEquals(row, back);
    }

    @Test
    void aRowFromBeforePlanNamesReadsAsHavingNone() {
        var json = sample().toJson(MAPPER);
        json.remove("plan_names");

        assertEquals(List.of(), RunSummary.fromJson(json).planNames());
    }

    @Test
    void aRowMissingARequiredFieldIsRejected() {
        var json = sample().toJson(MAPPER);
        json.remove("passed");

        assertThrows(IllegalArgumentException.class, () -> RunSummary.fromJson(json));
    }

    @Test
    void theSegmentIsFakePosThenNoRigThenRig() {
        RunSummary base = sample();
        assertEquals("rig", base.segment());
        assertEquals("fake_pos", new RunSummary("r", "", "lab", true, List.of(), "passed", 0, 0, 0, 0, 0,
                "", "", List.of(), List.of(), List.of()).segment());
        assertEquals("no rig", new RunSummary("r", "", "unknown", false, List.of(), "passed", 0, 0, 0, 0, 0,
                "", "", List.of(), List.of(), List.of()).segment());
    }

    @Test
    void theFirstPassReadsEveryRunAndTheSecondReadsNone() throws Exception {
        Path root = tmp.resolve("results");
        writeRun(root, "a", List.of(started("lab", false, "", List.of(), List.of()),
                finished("passed", 1, 0, 0, false, 2.0, List.of())));
        writeRun(root, "b", List.of(started("lab", false, "", List.of(), List.of()),
                finished("failed", 0, 1, 0, false, 2.0, List.of())));
        Path index = tmp.resolve("index.json");

        RunIndex.IndexResult first = RunIndex.build(root, index, null);
        RunIndex.IndexResult second = RunIndex.build(root, index, null);

        assertEquals(2, first.reread());
        assertEquals(0, second.reread());
        assertEquals(2, second.rows().size());
        Map<?, ?> document = MAPPER.readValue(Files.readString(index), Map.class);
        assertEquals(1, document.get("version"));
        assertTrue(((Map<?, ?>) document.get("runs")).containsKey("a"));
    }

    @Test
    void aChangedRunIsRereadAndAVanishedOneDropped() throws Exception {
        Path root = tmp.resolve("results");
        Path a = writeRun(root, "a", List.of(started("lab", false, "", List.of(), List.of()),
                finished("passed", 1, 0, 0, false, 2.0, List.of())));
        writeRun(root, "b", List.of(started("lab", false, "", List.of(), List.of()),
                finished("passed", 1, 0, 0, false, 2.0, List.of())));
        Path index = tmp.resolve("index.json");
        RunIndex.build(root, index, null);

        Files.writeString(a.resolve(ReportPaths.EVENTS_FILE),
                Files.readString(a.resolve(ReportPaths.EVENTS_FILE))
                        + MAPPER.writeValueAsString(test("tests/x.py::t", "passed", 1.0, "")) + "\n");
        RunIndex.IndexResult changed = RunIndex.build(root, index, null);
        assertEquals(1, changed.reread());

        Files.delete(root.resolve("b").resolve(ReportPaths.EVENTS_FILE));
        Files.delete(root.resolve("b"));
        RunIndex.IndexResult vanished = RunIndex.build(root, index, null);
        assertEquals(List.of("a"), vanished.rows().stream().map(RunSummary::runId).toList());
    }

    @Test
    void aCorruptIndexIsACacheMissNotAFailure() throws Exception {
        Path root = tmp.resolve("results");
        writeRun(root, "a", List.of(started("lab", false, "", List.of(), List.of()),
                finished("passed", 1, 0, 0, false, 2.0, List.of())));
        Path index = tmp.resolve("index.json");
        Files.writeString(index, "{\"version\": 1, \"runs\": {");

        RunIndex.IndexResult result = RunIndex.build(root, index, null);

        assertFalse(result.cacheWasUsable());
        assertEquals(1, result.rows().size());
        assertEquals(List.of(), RunIndex.loadIndex(tmp.resolve("absent.json")));
    }

    @Test
    void aRunThatCannotBeReadIsReportedNotFatal() throws Exception {
        Path root = tmp.resolve("results");
        writeRun(root, "good", List.of(started("lab", false, "", List.of(), List.of()),
                finished("passed", 1, 0, 0, false, 2.0, List.of())));
        Files.createDirectories(root.resolve("bad"));
        Files.write(root.resolve("bad").resolve(ReportPaths.EVENTS_FILE), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00});

        RunIndex.IndexResult result = RunIndex.build(root, tmp.resolve("index.json"), null);

        assertEquals(List.of("bad"), result.failed());
        assertEquals(List.of("good"), result.rows().stream().map(RunSummary::runId).toList());
    }

    @Test
    void loadIndexReadsTheCacheWithoutReplayingAnything() throws Exception {
        Path root = tmp.resolve("results");
        writeRun(root, "a", List.of(started("lab", false, "", List.of(), List.of()),
                finished("passed", 1, 0, 0, false, 2.0, List.of())));
        Path index = tmp.resolve("index.json");
        RunIndex.build(root, index, null);
        Files.delete(root.resolve("a").resolve(ReportPaths.EVENTS_FILE));

        assertEquals(1, RunIndex.loadIndex(index).size(), "the cache still holds the run it saw");
    }

    @Test
    void theIndexFileComesFromTheEnvironmentOrTheUsersCatFolder() {
        assertEquals(tmp.resolve("x.json"), RunIndex.indexPath(Map.of("CAT_RESULTS_INDEX", tmp.resolve("x.json").toString())));
        assertEquals(Path.of(System.getProperty("user.home"), ".cat", "java", "results-index.json"),
                RunIndex.indexPath(Map.of()));
    }

    @Test
    void theDefaultIsNeverThePythonCatsFile() {
        Path python = Path.of(System.getProperty("user.home"), ".cat", "results-index.json");
        assertFalse(RunIndex.indexPath(Map.of()).equals(python), "the Python CAT's index must not be shared");
        assertFalse(RunIndex.indexPath(Map.of("CAT_RESULTS_INDEX", " ")).equals(python));
    }
}
