package com.verifone.cat.report.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.report.NdjsonReader;
import com.verifone.cat.report.NdjsonWriter;

/** The wire contract: type names, field names, envelope, defaults and bounds. */
class CatEventWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-08-04T10:00:00Z");

    @TempDir
    Path dir;

    /** One valid instance of every event record. A new record must be added here or the test fails. */
    static List<CatEvent> samples() {
        return List.of(
                new CatEvent.PlanResolved(List.of(
                        new CatEvent.PlanCaseDescriptor("PETROCORE-61391", "Charity: receipt", null))),
                new CatEvent.SimulatorState("Viper", "started"),
                new CatEvent.RunStarted("run-1", "lab", "tests/suites/charity", "pytest", 1, T0,
                        "1.0.0", null, null, null, false, null, false),
                new CatEvent.RunManifest(
                        List.of(new CatEvent.SimulatorDescriptor("Viper", "supervised", "ready")),
                        List.of(new CatEvent.ConnectionDescriptor("Commander", "ssh", null)),
                        null),
                new CatEvent.RunFinished("run-1", Outcome.PASSED, 1, 0, 0, null, false, 1.0, 0),
                new CatEvent.StepEntered("Tests Run"),
                new CatEvent.StepProgress("Tests Run", 1, 2, "case 1"),
                new CatEvent.StepFinished("Tests Run", "complete", 3.0, ""),
                new CatEvent.StepCheck("System Checks", "rig lease", SubstepOutcome.PASSED, "", 0),
                new CatEvent.TestCollected(2),
                new CatEvent.TestStarted("tests/suites/charity/test_a.py::test_a"),
                new CatEvent.TestStep("tests/suites/charity/test_a.py::test_a", 1, "Given",
                        "the cashier is signed on", SubstepOutcome.PASSED, 0.8, ""),
                new CatEvent.TestValidation("tests/suites/charity/test_a.py::test_a",
                        "PETROCORE-61334", "receipt", "baseline.txt", true, "", "", ""),
                new CatEvent.TestFinished("tests/suites/charity/test_a.py::test_a",
                        Outcome.PASSED, 1.2, 0, "", ""),
                new CatEvent.PreflightCheck("System Checks", Phase.VERIFY, Outcome.PASSED, "", 0.1),
                new CatEvent.RecoveryRung("tests/suites/charity/test_a.py::test_a", 0,
                        Outcome.PASSED),
                new CatEvent.ConnectionState("Commander", "up", "", null),
                new CatEvent.LogLine("INFO", "petrotest.devices.pinpad", "a line", null));
    }

    @Test
    void theRecordsAndTheTypeNamesAreOneToOne() {
        List<CatEvent> samples = samples();
        Set<EventType> types = samples.stream().map(CatEvent::type)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EventType.class)));

        assertEquals(samples.size(), types.size(), "two records share a type name");
        assertEquals(EnumSet.allOf(EventType.class), types, "a type has no record sample");
        long recordClasses = Arrays.stream(CatEvent.class.getDeclaredClasses())
                .filter(CatEvent.class::isAssignableFrom).count();
        assertEquals(recordClasses, samples.size(), "a record class has no sample above");
    }

    @Test
    void theTypeNamesAreTheReadersSpelling() {
        assertEquals("run.started", EventType.RUN_STARTED.wire());
        assertEquals("plan.resolved", EventType.PLAN_RESOLVED.wire());
        assertEquals("step.check", EventType.STEP_CHECK.wire());
        assertEquals("test.step", EventType.TEST_STEP.wire());
        assertEquals("test.validation", EventType.TEST_VALIDATION.wire());
        assertEquals("simulator.state", EventType.SIMULATOR_STATE.wire());
        assertEquals("log.line", EventType.LOG_LINE.wire());
    }

    @Test
    void everySampleRoundTripsThroughTheWriterAndTheReader() throws IOException {
        Path file = dir.resolve("events.ndjson");
        List<CatEvent> samples = samples();
        try (NdjsonWriter writer = new NdjsonWriter(file);
                EventEmitter emitter = new EventEmitter(writer, "session", fixed(T0))) {
            for (CatEvent event : samples) {
                emitter.emit(event);
            }
        }

        List<JsonNode> lines = new ArrayList<>();
        NdjsonReader.forEach(file, lines::add);
        assertEquals(samples.size(), lines.size());
        for (int i = 0; i < samples.size(); i++) {
            JsonNode line = lines.get(i);
            assertEquals(samples.get(i).type().wire(), line.get("type").asText());
            assertEquals(1, line.get("v").asInt());
            assertEquals(i, line.get("seq").asLong());
            assertEquals("session", line.get("worker").asText());
        }
    }

    @Test
    void theLinesMatchTheGoldenFile() throws IOException {
        Path file = dir.resolve("events.ndjson");
        try (NdjsonWriter writer = new NdjsonWriter(file);
                EventEmitter emitter = new EventEmitter(writer, "session", fixed(T0))) {
            emitter.emit(new CatEvent.RunStarted("a1b2c3d4e5f6", "lab", "tests/suites/charity",
                    "pytest tests/suites/charity", 1, T0, "1.0.0", null,
                    List.of("PETROCORE-70960"), List.of("Charity Donations Indoor"), false, "", false));
            emitter.emit(new CatEvent.StepCheck("System Checks", "petroapps current",
                    SubstepOutcome.PASSED, "up to date", 0.5));
            emitter.emit(new CatEvent.TestFinished(
                    "tests/suites/charity/PETROCORE-61334-TestCase.feature::charity",
                    Outcome.FAILED, 32.1, 0, "AssertionError", "expected 1 got 2"));
            emitter.emit(new CatEvent.RunFinished("a1b2c3d4e5f6", Outcome.FAILED, 0, 1, 0,
                    List.of(), false, 40.0, 0));
        }

        List<JsonNode> written = new ArrayList<>();
        NdjsonReader.forEach(file, written::add);
        List<String> golden = readGolden();
        assertEquals(golden.size(), written.size());
        for (int i = 0; i < golden.size(); i++) {
            assertEquals(MAPPER.readTree(golden.get(i)), written.get(i), "line " + i);
        }
    }

    @Test
    void theEnvelopeIsTheFourFieldsAndTheTypeComesFirst() throws IOException {
        JsonNode line = EventEmitter.toLine(new CatEvent.StepEntered("Cleanup"),
                new Envelope(1, T0, 7, "gw1"));
        assertEquals("step.entered", line.get("type").asText());
        assertEquals("2026-08-04T10:00:00Z", line.get("at").asText());
        assertEquals(7, line.get("seq").asLong());
        assertEquals("gw1", line.get("worker").asText());
        assertEquals("Cleanup", line.get("step").asText());
    }

    @Test
    void emitterCountsSeqFromZeroAndReturnsTheEnvelopeItWrote() throws IOException {
        Path file = dir.resolve("events.ndjson");
        try (NdjsonWriter writer = new NdjsonWriter(file);
                EventEmitter emitter = new EventEmitter(writer, "session", fixed(T0))) {
            assertEquals(0, emitter.emit(new CatEvent.StepEntered("Cleanup")).seq());
            Envelope second = emitter.emit(new CatEvent.TestCollected(0));
            assertEquals(1, second.seq());
            assertEquals(T0, second.at());
        }
    }

    @Test
    void aBlankWorkerIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new EventEmitter(null, " ", fixed(T0)));
    }

    @Test
    void defaultsAreAppliedWhereThePythonModelsDefaultThem() throws IOException {
        CatEvent.RunStarted run = new CatEvent.RunStarted("r", "lab", "s", "c", 1, T0, "1",
                null, null, null, false, null, false);
        assertEquals("skip", run.upgradeMode());
        assertEquals(List.of(), run.testPlans());
        assertEquals(List.of(), run.planNames());
        assertEquals("", run.gitHead());

        CatEvent.SimulatorState sim = new CatEvent.SimulatorState("Viper", "started");
        assertEquals("supervised", sim.kind());
        assertEquals("CAT", sim.owner());
        assertEquals("", sim.bind());
        assertEquals("", sim.detail());

        CatEvent.PlanCaseDescriptor unmigrated = new CatEvent.PlanCaseDescriptor("PETROCORE-1", null, null);
        assertEquals("", unmigrated.summary());
        assertEquals(null, unmigrated.node());

        JsonNode line = EventEmitter.toLine(run, new Envelope(1, T0, 0, "session"));
        assertEquals("skip", line.get("upgrade_mode").asText());
        assertEquals(0, line.get("test_plans").size());
    }

    @Test
    void theDetailOfAStepIsNotCutByTheModel() {
        // The Python models state a bound; the plugin enforces it. The record keeps what it is given.
        String long3000 = "x".repeat(3000);
        CatEvent.TestFinished finished = new CatEvent.TestFinished("n", Outcome.FAILED, 1, 0, "E", long3000);
        assertEquals(3000, finished.detail().length());
        assertEquals(2000, Limits.truncateDetail(long3000).length());
        assertTrue(Limits.truncateDetail(long3000).endsWith("..."));
    }

    @Test
    void theTruncationKeepsShortTextWhole() {
        assertEquals("short", Limits.truncate("  short \n", 10));
        assertEquals(10, Limits.truncate("y".repeat(50), 10).length());
    }

    @Test
    void theLimitsAreThePythonValues() {
        assertEquals(2000, Limits.DETAIL_MAX_CHARS);
        assertEquals(20_000, Limits.VALIDATION_DIFF_MAX_CHARS);
    }

    @Test
    void rangesThePythonModelsEnforceAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.RunStarted(
                "r", "lab", "s", "c", 0, T0, "1", null, null, null, false, null, false));
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.TestStep(
                "n", 0, "Given", "t", "passed", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.StepFinished(
                "s", "complete", -1, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.StepFinished(
                "s", "complete", Double.NaN, ""));
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.TestCollected(-1));
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.RunFinished(
                "r", Outcome.PASSED, 0, 0, 0, null, false, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new CatEvent.RunFinished(
                "r", null, 0, 0, 0, null, false, 1, 0));
    }

    @Test
    void theEnvelopeRefusesAMissingTime() {
        assertThrows(IllegalArgumentException.class, () -> new Envelope(1, null, 0, "session"));
    }

    @Test
    void theOutcomeAndPhaseWriteTheirLowercaseValues() throws IOException {
        JsonNode line = EventEmitter.toLine(
                new CatEvent.PreflightCheck("System Checks", Phase.REVERIFY, Outcome.TIMEOUT, "", 1),
                new Envelope(1, T0, 0, "session"));
        assertEquals("reverify", line.get("phase").asText());
        assertEquals("timeout", line.get("outcome").asText());
        assertFalse(line.has("elapsed") && line.get("elapsed").isNull());
    }

    @Test
    void theRunStartedFieldNamesAreTheReadersNames() {
        JsonNode line = EventEmitter.toLine(
                new CatEvent.RunStarted("r", "lab", "s", "c", 1, T0, "1", "skip", List.of(),
                        List.of(), true, "abc", true),
                new Envelope(1, T0, 0, "session"));
        for (String name : List.of("run_id", "rig", "suite", "cmd", "workers", "started_at",
                "cat_version", "upgrade_mode", "test_plans", "plan_names", "fake_pos", "git_head",
                "restated")) {
            assertTrue(line.has(name), "missing " + name);
        }
        assertTrue(line.get("fake_pos").asBoolean());
    }

    private static Clock fixed(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    private static List<String> readGolden() throws IOException {
        try (InputStream in = CatEventWireTest.class.getResourceAsStream(
                "/report/events/golden-events.ndjson")) {
            assertTrue(in != null, "golden file missing from the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(s -> !s.isBlank()).toList();
        }
    }
}
