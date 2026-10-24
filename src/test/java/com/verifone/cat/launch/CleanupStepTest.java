package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cleanup's day-close wiring: the guards that skip the close as the Python does, and the step's state
 * while the simulator stop, connection watch and lease check are still unported. No test here reaches a
 * Commander: every run that could close the day stops at a guard first.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class CleanupStepTest {

    @TempDir
    Path dir;

    private RunContext context(String runId, LaunchConfig config) throws IOException {
        Path folder = dir.resolve(runId);
        RunEvents events = RunEvents.open(folder, Clock.systemUTC());
        return new RunContext(config, LaunchFixtures.paths(dir), runId, folder, events, new PauseGate(),
                Clock.systemUTC());
    }

    @Test
    void aRunThatStoppedBeforeCommanderConfigurationSkipsTheDayCloseAndIsUnavailable() throws IOException {
        RunContext ctx = context("stopped", LaunchConfig.forRig(LaunchFixtures.RIG));

        assertFalse(StepRunner.runLocal(ctx, new CleanupStep()));
        ctx.closeEvents();

        StepResult result = ctx.stepResults().get(LaunchStepNames.ALL.indexOf(LaunchStepNames.CLEANUP));
        assertEquals(StepResult.FAILED, result.state());
        assertTrue(result.detail().startsWith("unavailable in CAT-Java: needs the simulator stop"), result.detail());
        String log = Files.readString(ctx.runFolder().resolve("run.log"));
        assertTrue(log.contains("SKIP  " + CleanupStep.DAY_LABEL + " — the run stopped before Commander Configuration"),
                log);
    }

    @Test
    void aRunThatNeverStartedTestsLeavesTheRegisterAloneAndSkipsTheDayClose() throws IOException {
        RunContext ctx = context("no-tests", LaunchConfig.forRig(LaunchFixtures.RIG));
        ctx.recordStep(LaunchStepNames.COMMANDER, StepResult.COMPLETE, "");
        ctx.recordStep(LaunchStepNames.TESTS, StepResult.SKIPPED, "");

        StepRunner.runLocal(ctx, new CleanupStep());
        ctx.closeEvents();

        String log = Files.readString(ctx.runFolder().resolve("run.log"));
        assertTrue(log.contains("SKIP  " + CleanupStep.DAY_LABEL + " — no test ran, so the register is left alone"),
                log);
    }

    @Test
    void aRunWithNoRigSaysSoAndSkipsTheDayClose() throws IOException {
        LaunchConfig fakePos = new LaunchConfig("", List.of(), List.of(),
                UpgradeMode.SKIP, true, true, false, List.of(), null, false, false);
        RunContext ctx = context("fake", fakePos);

        StepRunner.runLocal(ctx, new CleanupStep());
        ctx.closeEvents();

        // A step's own log lines are events, not run.log rows; its checklist row is what run.log keeps.
        String events = Files.readString(ctx.runFolder().resolve("events.ndjson"));
        assertTrue(events.contains("no rig: day not closed"), events);
        assertEquals(StepResult.FAILED,
                ctx.stepResults().get(LaunchStepNames.ALL.indexOf(LaunchStepNames.CLEANUP)).state());
    }
}
