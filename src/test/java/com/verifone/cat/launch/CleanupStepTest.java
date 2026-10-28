package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.sims.SimProcess;

/**
 * Cleanup over a recording day close: the guards that skip the close as the Python's {@code _close_day} does, the
 * forced-AEOD WARN, a failed close failing the step while the rest still runs, the simulator stop, the connection
 * watch and the lease check. No test here reaches a Commander or a real process.
 */
class CleanupStepTest {

    @TempDir
    Path dir;

    /** Records each close it is asked for, and answers as told. */
    private static final class Closer implements DayClose.Closer {

        final List<String> closed = new ArrayList<>();
        CloseDay.Outcome outcome = CloseDay.Outcome.CLOSED;
        RuntimeException thrown;

        @Override
        public CloseDay.Outcome close(com.verifone.cat.rig.Rig rig, com.verifone.cat.commander.Say say) {
            closed.add(rig.name());
            say.out("close day on commander.invalid: sent cclosepdnow");
            if (thrown != null) {
                throw thrown;
            }
            if (outcome == CloseDay.Outcome.FAILED) {
                say.err("close day on commander.invalid failed: the close was cancelled");
            }
            return outcome;
        }
    }

    private RunContext context(String runId, LaunchConfig config, boolean withRig) throws IOException {
        Path folder = dir.resolve(runId);
        RunEvents events = RunEvents.open(folder, Clock.systemUTC());
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), runId, folder, events, new PauseGate(),
                Clock.systemUTC());
        if (withRig) {
            ctx.rig(RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir()));
        }
        return ctx;
    }

    /** A rig run that reached step 4 and began step 5. */
    private RunContext reachedTests(String runId) throws IOException {
        RunContext ctx = context(runId, LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.commanderReached(true);
        ctx.testsStarted(true);
        return ctx;
    }

    private record Outcome(StepResult result, Map<String, String> rows, Map<String, String> details,
            List<String> logs) {}

    private Outcome run(RunContext ctx, Closer closer) throws IOException {
        return run(ctx, new CleanupStep(closer));
    }

    private Outcome run(RunContext ctx, CleanupStep cleanup) throws IOException {
        StepRunner.runLocal(ctx, cleanup);
        ctx.closeEvents();
        Map<String, String> rows = new LinkedHashMap<>();
        Map<String, String> details = new LinkedHashMap<>();
        List<String> logs = new ArrayList<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            String type = event.get("type").asText();
            if (type.equals("step.check")) {
                rows.put(event.get("label").asText(), event.get("outcome").asText());
                details.put(event.get("label").asText(), event.get("detail").asText());
            } else if (type.equals("log.line")) {
                logs.add(event.get("message").asText());
            }
        }
        StepResult result = ctx.stepResults().get(LaunchStepNames.ALL.indexOf(LaunchStepNames.CLEANUP));
        return new Outcome(result, rows, details, logs);
    }

    @Test
    void aRunThatStoppedBeforeCommanderConfigurationSkipsTheDayCloseAndStillCleansUp() throws IOException {
        RunContext ctx = context("stopped", LaunchConfig.forRig(LaunchFixtures.RIG), true);
        Closer closer = new Closer();
        Outcome out = run(ctx, closer);

        assertTrue(closer.closed.isEmpty());
        assertEquals(StepResult.COMPLETE, out.result().state());
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(CleanupStep.DAY_LABEL));
        assertEquals("the run stopped before Commander Configuration", out.details().get(CleanupStep.DAY_LABEL));
        assertEquals("day not closed: run stopped before Commander Configuration; no CAT-started simulators; "
                + "rig lease free", out.result().detail());
    }

    @Test
    void aRunThatNeverStartedTestsLeavesTheRegisterAloneAndSkipsTheDayClose() throws IOException {
        RunContext ctx = context("no-tests", LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.commanderReached(true);
        ctx.recordStep(LaunchStepNames.TESTS, StepResult.SKIPPED, "");
        Closer closer = new Closer();
        Outcome out = run(ctx, closer);

        assertTrue(closer.closed.isEmpty());
        assertEquals("no test ran, so the register is left alone", out.details().get(CleanupStep.DAY_LABEL));
        assertTrue(out.result().detail().startsWith("day not closed: no test ran"), out.result().detail());
    }

    @Test
    void aTestsRunStepThatEndedStandsInForTheTestsStartedFlag() throws IOException {
        RunContext ctx = context("tests-ended", LaunchConfig.forRig(LaunchFixtures.RIG), true);
        ctx.commanderReached(true);
        ctx.recordStep(LaunchStepNames.TESTS, StepResult.FAILED, "2 failed");
        Closer closer = new Closer();
        run(ctx, closer);
        assertEquals(List.of(LaunchFixtures.RIG), closer.closed);
    }

    @Test
    void aPsiInstallThatNeverReachedPsiInitIsNotLoggedInto() throws IOException {
        RunContext ctx = reachedTests("psi");
        ctx.commanderLoginReady(false);
        Closer closer = new Closer();
        Outcome out = run(ctx, closer);

        assertTrue(closer.closed.isEmpty());
        assertEquals("PSI install did not reach psi-init, manager password not reset",
                out.details().get(CleanupStep.DAY_LABEL));
        assertTrue(out.result().detail().startsWith("day not closed: install did not reach psi-init"));
    }

    @Test
    void aRunWithNoRigSaysSoAndSkipsTheDayClose() throws IOException {
        LaunchConfig fakePos = new LaunchConfig("", List.of(), List.of(),
                UpgradeMode.SKIP, true, true, false, List.of(), null, false, false);
        RunContext ctx = context("fake", fakePos, false);
        ctx.commanderReached(true);
        Closer closer = new Closer();
        Outcome out = run(ctx, closer);

        assertTrue(closer.closed.isEmpty());
        assertFalse(out.rows().containsKey(CleanupStep.DAY_LABEL));
        assertEquals(StepResult.COMPLETE, out.result().state());
        assertEquals("no rig: day not closed; no CAT-started simulators; no rig lease (fake POS)",
                out.result().detail());
    }

    @Test
    void aClosedDayPassesAndItsLinesGoToTheStepsLog() throws IOException {
        Closer closer = new Closer();
        Outcome out = run(reachedTests("closed"), closer);

        assertEquals(List.of(LaunchFixtures.RIG), closer.closed);
        assertEquals(SubstepOutcome.PASSED, out.rows().get(CleanupStep.DAY_LABEL));
        assertEquals("day closed", out.details().get(CleanupStep.DAY_LABEL));
        assertTrue(out.logs().contains("close day on commander.invalid: sent cclosepdnow"), out.logs().toString());
        assertEquals(StepResult.COMPLETE, out.result().state());
        assertEquals("day closed; no CAT-started simulators; rig lease free", out.result().detail());
    }

    @Test
    void aForcedAutomaticEndOfDayIsOnlyAWarnInCleanup() throws IOException {
        Closer closer = new Closer();
        closer.outcome = CloseDay.Outcome.FORCED_AEOD;
        Outcome out = run(reachedTests("aeod"), closer);

        assertEquals(SubstepOutcome.WARNED, out.rows().get(CleanupStep.DAY_LABEL));
        assertEquals(StepResult.COMPLETE, out.result().state());
        assertTrue(out.result().detail().startsWith("day not closed: the Commander forces AEOD (warned)"));
        assertTrue(out.logs().stream().anyMatch(l -> l.startsWith("WARN  close-day — the Commander forces automatic")),
                out.logs().toString());
    }

    @Test
    void aFailedCloseFailsTheStepWithItsLastErrorLineAndTheRestStillRuns() throws IOException {
        RunContext ctx = reachedTests("failed");
        SimProcess petro = FakeSims.spawned(StartSimulatorsStep.PETRO_SIMULATOR);
        ctx.addSimulator(petro);
        AtomicBoolean watching = new AtomicBoolean(true);
        ctx.watching(() -> watching.set(false));
        Closer closer = new Closer();
        closer.outcome = CloseDay.Outcome.FAILED;
        Outcome out = run(ctx, closer);

        assertEquals(StepResult.FAILED, out.result().state());
        assertEquals("close day FAILED (close day on commander.invalid failed: the close was cancelled); connection "
                + "watch stopped; 1 simulator(s) stopped; rig lease free", out.result().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(CleanupStep.DAY_LABEL));
        assertFalse(watching.get());
        assertFalse(petro.process().isAlive());
        assertTrue(ctx.simulators().isEmpty());
        assertEquals(SubstepOutcome.PASSED, out.rows().get("stop simulators"));
        assertEquals("stopped 1 CAT-started simulator(s): Petro Simulator Suite", out.details().get("stop simulators"));
        assertEquals(SubstepOutcome.PASSED, out.rows().get("connection watch"));
    }

    @Test
    void aCloseThatThrowsIsAFailedClauseNamingTheFault() throws IOException {
        Closer closer = new Closer();
        closer.thrown = new IllegalStateException("no route to host");
        Outcome out = run(reachedTests("threw"), closer);
        assertEquals(StepResult.FAILED, out.result().state());
        assertTrue(out.result().detail().startsWith("close day FAILED (IllegalStateException: no route to host)"),
                out.result().detail());
    }

    @Test
    void aRigThatDidNotLoadCannotBeClosed() throws IOException {
        RunContext ctx = context("unloaded", LaunchConfig.forRig(LaunchFixtures.RIG), false);
        ctx.commanderReached(true);
        ctx.testsStarted(true);
        Closer closer = new Closer();
        Outcome out = run(ctx, closer);
        assertTrue(closer.closed.isEmpty());
        assertTrue(out.result().detail().startsWith("close day FAILED (the rig was not loaded in System Checks)"));
    }

    @Test
    void aRigLeaseStillHeldIsAWarnNamingTheRig() throws IOException {
        RunContext ctx = context("leased", LaunchConfig.forRig(LaunchFixtures.RIG), true);
        // Another holder in this process: the lock is taken, so the check cannot.
        try (RigLease other = new RigLease(LaunchFixtures.RIG, ctx.paths().leaseDir(), "a stray worker")) {
            other.acquire(CleanupStep.RIG_LEASE_CHECK_TIMEOUT);
            Outcome out = runWithShortLease(ctx);
            assertEquals(SubstepOutcome.WARNED, out.rows().get("rig lease"));
            assertTrue(out.result().detail().endsWith("rig lease STILL HELD (bench)")
                    || out.result().detail().endsWith("rig lease unknown"), out.result().detail());
        }
    }

    private Outcome runWithShortLease(RunContext ctx) throws IOException {
        return run(ctx, new CleanupStep(new Closer(), java.time.Duration.ofMillis(50)));
    }
}
