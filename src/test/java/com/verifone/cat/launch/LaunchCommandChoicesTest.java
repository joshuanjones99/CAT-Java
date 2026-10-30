package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resolved upgrade, coverage and Commander Connect choices printed with their source; a destructive upgrade mode
 * the Python CAT's fallback file alone supplies refused; and the exit verdict as {@code run_plan}'s.
 */
class LaunchCommandChoicesTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    private Path python;
    private Path java;

    private int run(LastConfig saved, String... argv) throws IOException {
        return new LaunchCommand().run(argv, LaunchFixtures.paths(dir), saved, Map.of(), out, err);
    }

    private LastConfig files(String pythonJson, String javaJson) throws IOException {
        python = dir.resolve("python-home").resolve("last-config.json");
        java = dir.resolve("java-home").resolve("last-config.json");
        Files.createDirectories(python.getParent());
        Files.createDirectories(java.getParent());
        if (pythonJson != null) {
            Files.writeString(python, pythonJson);
        }
        if (javaJson != null) {
            Files.writeString(java, javaJson);
        }
        return LastConfig.load(java, python);
    }

    private String text() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private long runFolders() throws IOException {
        try (Stream<Path> listing = Files.list(dir.resolve("results"))) {
            return listing.count();
        }
    }

    @Test
    void aNewInstallOnlyThePythonFileSaysIsRefusedBeforeAnythingStarts() throws IOException {
        LastConfig saved = files("{\"config-upgrade\": \"new_install\", \"config-coverage\": true}", null);

        int code = run(saved, "--rig", LaunchFixtures.RIG);

        assertEquals(LaunchCommand.EXIT_USAGE, code, text());
        assertTrue(text().contains("[run] upgrade new_install (from " + python + ")"), text());
        assertTrue(text().contains("[run] coverage on (from " + python + ")"), text());
        assertTrue(text().contains("[run] refused: upgrade new_install comes only from the Python CAT's " + python),
                text());
        assertTrue(text().contains("Pass --upgrade new_install to install it, or --upgrade skip."), text());
        assertEquals(0, runFolders(), "a refused run makes no run folder");
    }

    @Test
    void aClassicOnlyThePythonFileSaysIsRefusedToo() throws IOException {
        int code = run(files("{\"config-upgrade\": \"classic\"}", null), "--rig", LaunchFixtures.RIG);

        assertEquals(LaunchCommand.EXIT_USAGE, code, text());
        assertTrue(text().contains("[run] refused: upgrade classic comes only from the Python CAT's"), text());
    }

    @Test
    void anExplicitUpgradeFlagRunsAndSaysWhereTheModeCameFrom() throws IOException {
        int code = run(files("{\"config-upgrade\": \"new_install\"}", null), "--rig", LaunchFixtures.RIG,
                "--upgrade", "new_install");

        // The bench rig stops at System Checks: a run, not a refusal.
        assertEquals(LaunchCommand.EXIT_FAILED, code, text());
        assertTrue(text().contains("[run] upgrade new_install (from --upgrade)"), text());
        assertFalse(text().contains("refused"), text());
    }

    @Test
    void catJavasOwnSavedNewInstallIsHonouredAsBefore() throws IOException {
        int code = run(files("{\"config-upgrade\": \"skip\"}", "{\"config-upgrade\": \"new_install\"}"),
                "--rig", LaunchFixtures.RIG);

        assertEquals(LaunchCommand.EXIT_FAILED, code, text());
        assertTrue(text().contains("[run] upgrade new_install (from " + java + ")"), text());
        assertFalse(text().contains("refused"), text());
    }

    @Test
    void aPythonSkipIsNotRefusedAndNothingSavedIsTheDefault() throws IOException {
        assertEquals(LaunchCommand.EXIT_FAILED, run(files("{\"config-upgrade\": \"skip\"}", null),
                "--rig", LaunchFixtures.RIG), text());
        assertTrue(text().contains("[run] upgrade skip (from " + python + ")"), text());
        assertTrue(text().contains("[run] coverage off (default)"), text());
        assertTrue(text().contains("[run] Commander Connect onboard off (default)"), text());
    }

    @Test
    void theFlagsAreNamedAsTheSourceOfCoverageAndCommanderConnect() throws IOException {
        run(files("{\"config-coverage\": true}", null), "--rig", LaunchFixtures.RIG, "--no-coverage", "--cc-onboard");

        assertTrue(text().contains("[run] upgrade skip (default)"), text());
        assertTrue(text().contains("[run] coverage off (from --no-coverage)"), text());
        assertTrue(text().contains("[run] Commander Connect onboard on (from --cc-onboard)"), text());
    }

    @Test
    void eachKeyRemembersTheFileItCameFrom() throws IOException {
        LastConfig saved = files("{\"config-upgrade\": \"new_install\", \"config-dist\": \"x\"}",
                "{\"config-dist\": \"y\"}");

        assertTrue(saved.onlyInPythonFile(LastConfig.UPGRADE));
        assertFalse(saved.onlyInPythonFile(LastConfig.DIST));
        assertEquals(java, saved.source(LastConfig.DIST).file());
        assertEquals(null, saved.source(LastConfig.COVERAGE));
        assertFalse(LastConfig.of(Map.of(LastConfig.UPGRADE, "new_install")).onlyInPythonFile(LastConfig.UPGRADE));
    }

    @Test
    void theExitVerdictIsRunPlansFailedCasesCountAndSkippedStepsDoNot() {
        List<StepResult> skippedOnly = List.of(new StepResult("System Checks", StepResult.COMPLETE, ""),
                new StepResult("Start Simulators", StepResult.SKIPPED, "none"),
                new StepResult("Cleanup", StepResult.COMPLETE, ""));
        assertTrue(new LaunchResult("r", dir, skippedOnly, false, 0).complete());
        assertFalse(new LaunchResult("r", dir, skippedOnly, false, 2).complete(), "a failed case fails the run");
        assertFalse(new LaunchResult("r", dir, skippedOnly, true, 0).complete(), "a stopped run is not complete");
        assertFalse(new LaunchResult("r", dir, List.of(new StepResult("Tests Run", StepResult.FAILED, "x")), false, 0)
                .complete());
    }

    @Test
    void abandonIsANoOpBeforeALaunchAndAfterOne() throws IOException {
        RunLauncher before = new RunLauncher(LaunchFixtures.paths(dir));
        before.abandon();

        RunLauncher after = new RunLauncher(LaunchFixtures.paths(dir));
        LaunchResult result = after.launch(LaunchConfig.forRig(LaunchFixtures.RIG));
        Files.delete(result.runFolder().resolve("run.ended"));
        after.abandon();
        assertFalse(Files.exists(result.runFolder().resolve("run.ended")), "a finished run is not marked again");
    }
}
