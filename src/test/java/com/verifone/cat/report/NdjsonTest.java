package com.verifone.cat.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class NdjsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private static List<JsonNode> read(Path file) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        NdjsonReader.forEach(file, events::add);
        return events;
    }

    @Test
    void eachEventIsOneLineAndFlushedBeforeTheWriterCloses() throws Exception {
        Path file = dir.resolve(ReportPaths.EVENTS_FILE);

        try (NdjsonWriter writer = new NdjsonWriter(file)) {
            writer.write(Map.of("type", "run.started", "seq", 0));
            // Read while the writer is still open: a reader must see the line already.
            assertEquals(1, Files.readAllLines(file, StandardCharsets.UTF_8).size());
            writer.write(Map.of("type", "test.finished", "seq", 1));
            assertEquals(2, Files.readAllLines(file, StandardCharsets.UTF_8).size());
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("run.started", MAPPER.readTree(lines.get(0)).get("type").asText());
    }

    @Test
    void reopeningAppendsRatherThanTruncating() throws Exception {
        Path file = dir.resolve(ReportPaths.EVENTS_FILE);
        try (NdjsonWriter first = new NdjsonWriter(file)) {
            first.write(Map.of("type", "run.started"));
        }
        try (NdjsonWriter second = new NdjsonWriter(file)) {
            second.write(Map.of("type", "run.finished"));
        }

        List<JsonNode> events = read(file);
        assertEquals(List.of("run.started", "run.finished"),
                events.stream().map(e -> e.get("type").asText()).toList());
    }

    @Test
    void writerCreatesTheRunFolderWhenAbsent() throws Exception {
        Path file = dir.resolve("run-1").resolve(ReportPaths.EVENTS_FILE);

        try (NdjsonWriter writer = new NdjsonWriter(file)) {
            writer.write(Map.of("type", "run.started"));
        }

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void aNewlineInsideAValueDoesNotBreakTheLine() throws Exception {
        Path file = dir.resolve(ReportPaths.EVENTS_FILE);

        try (NdjsonWriter writer = new NdjsonWriter(file)) {
            writer.write(Map.of("type", "log.line", "text", "first\nsecond"));
        }

        assertEquals(1, Files.readAllLines(file, StandardCharsets.UTF_8).size());
        assertEquals("first\nsecond", read(file).get(0).get("text").asText());
    }

    @Test
    void readerSkipsBlankMalformedAndNonObjectLines() throws Exception {
        Path file = dir.resolve(ReportPaths.EVENTS_FILE);
        Files.writeString(file, "{\"type\":\"a\"}\n\n   \nnot json\n42\n[1,2]\n{\"type\":\"b\"}\n",
                StandardCharsets.UTF_8);

        assertEquals(List.of("a", "b"), read(file).stream().map(e -> e.get("type").asText()).toList());
    }

    @Test
    void foldLeavesTypesItDoesNotOwnToTheWriter() throws Exception {
        RunFacts facts = new RunFacts();

        assertFalse(facts.fold(MAPPER.readTree("{\"type\":\"test.finished\",\"nodeid\":\"t\"}")));
        assertTrue(facts.fold(MAPPER.readTree(
                "{\"type\":\"preflight.check\",\"name\":\"n\",\"phase\":\"verify\",\"outcome\":\"pass\"}")));
        assertEquals("", facts.preflightChecks().get(0).detail());
    }

    @Test
    void posDoubleFromAnOlderStreamStillMarksTheRunAsFake() throws Exception {
        RunFacts facts = new RunFacts();

        facts.fold(MAPPER.readTree("{\"type\":\"run.started\",\"run_id\":\"r\",\"pos_double\":true}"));

        assertTrue(facts.fakePos());
    }

    @Test
    void fakePosIsStickyAcrossTwoRunStartedEvents() throws Exception {
        RunFacts facts = new RunFacts();

        facts.fold(MAPPER.readTree("{\"type\":\"run.started\",\"fake_pos\":true}"));
        facts.fold(MAPPER.readTree("{\"type\":\"run.started\",\"fake_pos\":false}"));

        assertTrue(facts.fakePos(), "the launcher's event must not clear the pytest side's fake flag");
    }

    @Test
    void anEmptyTestPlanListKeepsTheEarlierOne() throws Exception {
        RunFacts facts = new RunFacts();

        facts.fold(MAPPER.readTree("{\"type\":\"run.started\",\"test_plans\":[\"PETROCORE-70960\"]}"));
        facts.fold(MAPPER.readTree("{\"type\":\"run.started\",\"test_plans\":[]}"));

        assertEquals(List.of("PETROCORE-70960"), facts.testPlans());
    }

    @Test
    void recoveryRungsKeepTheirOrderPerNodeid() throws Exception {
        RunFacts facts = new RunFacts();

        facts.fold(MAPPER.readTree("{\"type\":\"recovery.rung\",\"nodeid\":\"n\",\"rung\":0,\"outcome\":\"fail\"}"));
        facts.fold(MAPPER.readTree("{\"type\":\"recovery.rung\",\"nodeid\":\"n\",\"rung\":1,\"outcome\":\"pass\"}"));

        List<RunFacts.Rung> rungs = facts.recoveryRungs().get("n");
        assertEquals(List.of(new RunFacts.Rung("0", "fail"), new RunFacts.Rung("1", "pass")), rungs);
    }

    @Test
    void aPlannedCaseWithNoNodeIsNotRunAndKeepsItsSummary() throws Exception {
        RunFacts facts = new RunFacts();

        facts.fold(MAPPER.readTree("{\"type\":\"plan.resolved\",\"cases\":["
                + "{\"key\":\"PETROCORE-1\",\"node\":null,\"summary\":\"Charity\"},"
                + "{\"key\":\"PETROCORE-2\",\"node\":\"tests/x.py::t\"}]}"));

        assertEquals(Map.of("PETROCORE-1", "Charity"), facts.notRunCases());
    }
}
