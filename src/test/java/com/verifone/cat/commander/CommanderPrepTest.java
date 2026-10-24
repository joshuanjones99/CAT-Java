package com.verifone.cat.commander;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.provision.StepResult;
import com.verifone.cat.provision.StepStatus;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.InMemoryTransport;

/**
 * {@link CommanderPrep} against {@link InMemoryTransport}, as the Python's {@code test_commander_prep.py}. The
 * file layout is the one read off the lab Commander on 2026-09-16; nothing here writes to a real Commander.
 */
class CommanderPrepTest {

    private static final String LICENSE = CommanderPrep.LICENSE_JSON;
    private static final String MARKER = CommanderPrep.GEMREG_MARKER;
    private static final String READ = "cat " + LICENSE;
    private static final String CHECK = "test -e " + MARKER;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);
    private static final Duration T = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    /** {@code license.json}'s shape on the lab Commander: an array of entities. */
    private static String license(String... stops) {
        List<String> entries = new ArrayList<>();
        for (String stop : stops) {
            entries.add("{\"pk\": \"asm\", \"license\": {\"id\": \"abc\", \"trial\": false, \"entitlement\": "
                    + "{\"validity\": {\"start\": \"2026-08-20T00:00:00Z\", \"stop\": \"" + stop + "\", "
                    + "\"gracePeriodDays\": 30, \"expirationWarningDays\": 1}, \"usageCount\": 0}}, "
                    + "\"deleted\": false, \"source\": \"CLOUD\"}");
        }
        return "[" + String.join(", ", entries) + "]";
    }

    private static CommandResult ok(String text) {
        return new CommandResult(0, text, "");
    }

    private static InMemoryTransport reading(CommandResult read) {
        return new InMemoryTransport().command(READ, read);
    }

    private static String written(InMemoryTransport t, String path) {
        Optional<byte[]> content = t.get(path, T);
        return new String(content.orElseThrow(), StandardCharsets.UTF_8);
    }

    @Test
    void aPastStopMovesToThirtyDaysOutAndOwnershipIsRestored() throws Exception {
        InMemoryTransport t = reading(ok(license("2026-09-14T23:59:59Z", "2026-09-14T23:59:59Z")));

        StepResult result = CommanderPrep.extendLicense(t, TODAY, T);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of(READ, "chown coresvcs:vfi " + LICENSE), t.ran());
        assertEquals(Optional.of(0644), t.modeOf(LICENSE));
        List<String> stops = new ArrayList<>();
        for (JsonNode entry : JSON.readTree(written(t, LICENSE))) {
            stops.add(entry.at("/license/entitlement/validity/stop").asText());
        }
        assertEquals(List.of("2026-10-16T23:59:59Z", "2026-10-16T23:59:59Z"), stops);
    }

    @Test
    void onlyStopValuesChange() {
        String original = license("2026-09-14T23:59:59Z");
        InMemoryTransport t = reading(ok(original));

        CommanderPrep.extendLicense(t, TODAY, T);

        assertEquals(original.replace("2026-09-14T23:59:59Z", "2026-10-16T23:59:59Z"), written(t, LICENSE));
    }

    @Test
    void aStopDueWithinTheWindowIsRenewed() {
        assertEquals(StepStatus.DONE,
                CommanderPrep.extendLicense(reading(ok(license("2026-09-23T23:59:59Z"))), TODAY, T).status());
    }

    @Test
    void aStopBeyondTheWindowIsLeftAloneAndNothingIsWritten() {
        // Most runs: one cat, no SFTP, so no setenforce 0 either.
        InMemoryTransport t = reading(ok(license("2026-09-24T23:59:59Z")));

        StepResult result = CommanderPrep.extendLicense(t, TODAY, T);

        assertEquals(StepStatus.ALREADY, result.status());
        assertTrue(t.writes().isEmpty());
        assertTrue(t.reads().isEmpty());
        assertEquals(List.of(READ), t.ran());
    }

    @Test
    void anUnreadableFileFailsWithoutWriting() {
        InMemoryTransport t = reading(new CommandResult(1, "", "No such file or directory"));

        StepResult result = CommanderPrep.extendLicense(t, TODAY, T);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("No such file"), result.detail());
        assertTrue(t.writes().isEmpty());
    }

    @Test
    void aFileThatIsNotJsonIsNotTouched() {
        InMemoryTransport t = reading(ok("{\"stop\": \"2026-09-14T23:59:59Z\""));
        assertEquals(StepStatus.FAILED, CommanderPrep.extendLicense(t, TODAY, T).status());
        assertTrue(t.writes().isEmpty());
    }

    @Test
    void aWriteFailureIsReportedNotRaised() {
        InMemoryTransport t = reading(ok(license("2026-09-14T23:59:59Z"))).failWritesTo(LICENSE);
        assertEquals(StepStatus.FAILED, CommanderPrep.extendLicense(t, TODAY, T).status());
    }

    @Test
    void aFailedChownFailsTheStep() {
        InMemoryTransport t = reading(ok(license("2026-09-14T23:59:59Z")))
                .command("chown coresvcs:vfi " + LICENSE, new CommandResult(1, "", "no such user"));

        StepResult result = CommanderPrep.extendLicense(t, TODAY, T);

        assertEquals(StepStatus.FAILED, result.status());
        assertTrue(result.detail().contains("no such user"), result.detail());
    }

    @Test
    void aPresentMarkerIsLeftAlone() {
        InMemoryTransport t = new InMemoryTransport().command(CHECK, ok(""));
        assertEquals(StepStatus.ALREADY, CommanderPrep.ensureGemregMarker(t, T).status());
        assertTrue(t.writes().isEmpty());
    }

    @Test
    void aMissingMarkerIsWrittenWithThePackageText() {
        InMemoryTransport t = new InMemoryTransport().command(CHECK, new CommandResult(1, "", ""));

        StepResult result = CommanderPrep.ensureGemregMarker(t, T);

        assertEquals(StepStatus.DONE, result.status());
        assertEquals(List.of(CHECK, "chown coresvcs:vfi " + MARKER), t.ran());
        assertArrayEquals("This file forces Gemreg to be registered\n".getBytes(StandardCharsets.UTF_8),
                t.get(MARKER, T).orElseThrow());
    }

    @Test
    void theCommandRunsBothPartsOnTheRigsCommanderAndPrintsALineEach() {
        InMemoryTransport commander = reading(ok(license("2026-09-24T23:59:59Z"))).command(CHECK, ok(""));
        Map<String, InMemoryTransport> registers = new HashMap<>();
        Say.Recorded say = new Say.Recorded();

        boolean ok = CommanderPrep.run(CommanderFixtures.access(CommanderFixtures.rig(dir), commander, registers), TODAY,
                say);

        assertTrue(ok);
        assertEquals(List.of("extend_license: already — every stop is after 2026-09-23T23:59:59Z",
                "ensure_gemreg_marker: already"), say.outLines());
        assertTrue(commander.closed());
        assertTrue(registers.isEmpty());
    }

    @Test
    void aFailedPartFailsTheCommandOnStderr() {
        InMemoryTransport commander = reading(new CommandResult(1, "", "denied")).command(CHECK, ok(""));
        Say.Recorded say = new Say.Recorded();

        assertFalse(CommanderPrep.run(CommanderFixtures.access(CommanderFixtures.rig(dir), commander, new HashMap<>()),
                TODAY, say));
        assertEquals(List.of("extend_license: failed — cannot read " + LICENSE + ": denied"), say.errLines());
    }
}
