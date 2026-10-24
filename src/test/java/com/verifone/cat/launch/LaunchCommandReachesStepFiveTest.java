package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The headless CLI, {@code launch --plan PETROCORE-70960 --fake-pos}, through System Checks: step 1 passes, and the run
 * reaches step 5 (Tests Run). What step 5 then does is not this test's concern.
 *
 * <p>Every root is under {@code @TempDir}: the results folder, the rig folder, the lease folder, this checkout, the
 * plan catalogue's cache, the setup environment and the petroapps checkout, which is a throwaway git clone holding one
 * Charity case. Nothing touches {@code C:\CICD}, {@code C:\gitrepos\petroapps} or {@code ~/.cat}.
 */
class LaunchCommandReachesStepFiveTest {

    @TempDir
    Path dir;

    @Test
    void theFakePosPlanRunPassesSystemChecksAndStartsStepFive() throws Exception {
        Assumptions.assumeTrue(PetroappsRepo.gitAvailable(), "git is not on PATH");
        LaunchPaths paths = LaunchFixtures.paths(dir, PetroappsRepo.withOneCase(dir));
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        new LaunchCommand().run(new String[] {"--plan", "PETROCORE-70960", "--fake-pos"}, paths, out, err);

        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains(LaunchCommand.FAKE_POS_BANNER), text + errBytes);
        String systemChecks = text.lines().filter(line -> line.startsWith("  System Checks")).findFirst().orElse("");
        assertTrue(systemChecks.contains(" complete"), "System Checks did not complete:\n" + text + errBytes);

        List<JsonNode> events = LaunchFixtures.events(onlyRunFolder(paths));
        boolean stepFiveStarted = events.stream().anyMatch(event -> "step.entered".equals(event.get("type").asText())
                && LaunchStepNames.TESTS.equals(event.get("step").asText()));
        assertTrue(stepFiveStarted, "step 5 did not start:\n" + text + errBytes);
        assertEquals(LaunchStepNames.SYSTEM_CHECKS, firstStepEntered(events));
    }

    private Path onlyRunFolder(LaunchPaths paths) throws Exception {
        try (Stream<Path> runs = Files.list(paths.resultsRoot())) {
            List<Path> folders = runs.filter(Files::isDirectory).toList();
            assertEquals(1, folders.size(), "one run folder: " + folders);
            return folders.get(0);
        }
    }

    private static String firstStepEntered(List<JsonNode> events) {
        return events.stream().filter(event -> "step.entered".equals(event.get("type").asText()))
                .findFirst().map(event -> event.get("step").asText()).orElse("");
    }
}
