package com.verifone.cat.xray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.events.RunEnded;
import com.verifone.cat.xray.XraySidecar.Options;

/** The sidecar's entry point: its arguments, its stop rule, and a whole run through the fake. */
class XraySidecarTest {

    private static final String RUN = Events.RUN_ID;

    @TempDir
    Path tmp;

    private Options options(Path root, boolean once, boolean dryRun, AtlassianClient client, Path credentials) {
        return new Options(RUN, root, once, dryRun, "", "", "", "",
                0.01, 0.002, 0.05, 60.0, credentials, client,
                key -> Events.CASES, key -> Events.PLAN_NAME, state -> Map.of());
    }

    private static void writeStream(Path root, List<ObjectNode> events) throws IOException {
        Path run = ReportPaths.runFolder(root, RUN);
        Files.createDirectories(run);
        String text = events.stream().map(e -> e.toString() + "\n").collect(Collectors.joining());
        Files.writeString(run.resolve(ReportPaths.EVENTS_FILE), text, StandardCharsets.UTF_8);
    }

    @Test
    void theRunIdIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> XraySidecar.parse(new String[] {"--once"}, Map.of()));
    }

    @Test
    void theFlagsAndTheDryRunEnvironmentAreRead() {
        Options parsed = XraySidecar.parse(new String[] {"--run", RUN, "--results-root", "C:/x",
                "--once", "--build", "Solutions90", "--dist", "buypass", "--branch", "master",
                "--upgrade", "classic", "--flush-interval", "2.5"}, Map.of("CAT_XRAY_DRY_RUN", "1"));
        assertEquals(RUN, parsed.runId());
        assertTrue(parsed.once());
        assertTrue(parsed.dryRun(), "CAT_XRAY_DRY_RUN=1 is the same as --dry-run");
        assertEquals("Solutions90", parsed.build());
        assertEquals("classic", parsed.upgrade());
        assertEquals(2.5, parsed.flushIntervalSeconds());
    }

    @Test
    void aBadFlagOrAnUnusableIntervalIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> XraySidecar.parse(new String[] {"--run", RUN, "--flush-interval", "soon"}, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> XraySidecar.parse(new String[] {"--run", RUN, "--wat"}, Map.of()));
    }

    @Test
    void aRunMarkedEndedWithoutCleanupStopsInSeconds() throws IOException {
        writeStream(tmp, List.of(Events.runStarted(), Events.planResolved(1)));
        Files.writeString(ReportPaths.runFolder(tmp, RUN).resolve(RunEnded.RUN_ENDED_FILE),
                "2026-10-15T16:00:00+00:00\n");
        long started = System.nanoTime();

        int code = XraySidecar.run(options(tmp, false, true, null, null));

        assertEquals(0, code);
        assertTrue((System.nanoTime() - started) / 1e9 < 20, "the sidecar must not wait out its idle limit");
        assertEquals("finished", XrayReporter.readStatus(ReportPaths.runFolder(tmp, RUN)).get("state").asText());
    }

    @Test
    void aLiveRunThroughTheFakeIsReportedAndFinished() throws IOException {
        try (XrayFake fake = new XrayFake()) {
            writeStream(tmp, Events.stream());

            int code = XraySidecar.run(options(tmp, true, false, Events.client(fake), null));

            assertEquals(0, code);
            // An intermediate flush may push EXECUTING; the first and last statuses are fixed.
            List<String> passed = fake.statuses("PETROCORE-61396");
            List<String> failed = fake.statuses("PETROCORE-37043");
            assertEquals("TODO", passed.get(0));
            assertEquals("PASSED", passed.get(passed.size() - 1));
            assertEquals("TODO", failed.get(0));
            assertEquals("FAILED", failed.get(failed.size() - 1));
            assertEquals(List.of("TODO"), fake.statuses("PETROCORE-99999"));
            JsonNode description = fake.requests("PUT", "/issue/PETROCORE-900001").stream()
                    .map(r -> Events.json(r.body()))
                    .filter(b -> b.at("/fields").has("description"))
                    .findFirst().orElseThrow().at("/fields/description");
            assertTrue(description.toString().contains("1 failed"));
            assertTrue(fake.requests("POST", "/transitions").get(fake.requests("POST", "/transitions").size() - 1)
                    .body().contains("\"id\":\"31\""));
            Path run = ReportPaths.runFolder(tmp, RUN);
            assertTrue(Files.isRegularFile(run.resolve(XrayReporter.SUMMARY_FILE)));
            ObjectNode status = XrayReporter.readStatus(run);
            assertEquals("finished", status.get("state").asText());
            assertEquals("PETROCORE-900001", status.at("/executions/PETROCORE-70960/key").asText());
            assertFalse(fake.sawText("testEnvironments"));
        }
    }

    @Test
    void aMissingCredentialsFileDisablesReportingAndNamesTheSetupStep() throws IOException {
        writeStream(tmp, List.of(Events.runStarted()));

        int code = XraySidecar.run(options(tmp, true, false, null, tmp.resolve("absent.properties")));

        assertEquals(0, code);
        ObjectNode status = XrayReporter.readStatus(ReportPaths.runFolder(tmp, RUN));
        assertEquals("disabled", status.get("state").asText());
        assertTrue(status.get("reason").asText().contains("jira_api_token"), status.toString());
    }
}
