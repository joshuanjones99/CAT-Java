package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The headless command: usage errors, the printed step table, and the exit codes. */
class LaunchCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private int run(String... argv) throws IOException {
        return new LaunchCommand().run(argv, LaunchFixtures.paths(dir), out, err);
    }

    @Test
    void noRigIsAUsageError() throws IOException {
        assertEquals(LaunchCommand.EXIT_USAGE, run());
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("--rig is required."), errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownOptionIsAUsageError() throws IOException {
        assertEquals(LaunchCommand.EXIT_USAGE, run("--rig", "bench", "--bogus", "@smoke"));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Unknown option --bogus."));
    }

    @Test
    void aFlagWithoutItsValueAndABadUpgradeModeAreUsageErrors() throws IOException {
        assertEquals(LaunchCommand.EXIT_USAGE, run("--rig"));
        assertEquals(LaunchCommand.EXIT_USAGE, run("--rig", "bench", "--upgrade", "psi"));
    }

    @Test
    void aRunOfARigThatStopsAtSystemChecksPrintsEachStepAndExitsFailed() throws IOException {
        int code = run("--rig", LaunchFixtures.RIG);

        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertEquals(LaunchCommand.EXIT_FAILED, code, text);
        assertTrue(text.contains("run "), text);
        assertTrue(text.contains("System Checks"), text);
        assertTrue(text.contains("unavailable in CAT-Java: needs"), text);
        assertTrue(text.contains("Cleanup"), text);
    }

    @Test
    void aFakePosRunPrintsTheBannerAndExitsFailedBecauseItsStepsAreUnavailable() throws IOException {
        int code = run("--fake-pos");

        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertEquals(LaunchCommand.EXIT_FAILED, code, text);
        assertTrue(text.contains(LaunchCommand.FAKE_POS_BANNER), text);
    }
}
