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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The step contract, tested with small steps of the test's own: the bracket's terminal states, a
 * thrown exception as a failed step, and the checklist rows a step writes.
 */
class StepBracketTest {

    @TempDir
    Path dir;

    private RunContext context(String runId) throws IOException {
        Path folder = dir.resolve(runId);
        RunEvents events = RunEvents.open(folder, Clock.systemUTC());
        return new RunContext(LaunchConfig.forRig(LaunchFixtures.RIG), LaunchFixtures.paths(dir),
                runId, folder, events, new PauseGate(), Clock.systemUTC());
    }

    private static LaunchStep step(String name, Body body) {
        return new LaunchStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean run(RunContext ctx, StepEmitter step) {
                return body.run(step);
            }
        };
    }

    @FunctionalInterface
    private interface Body {
        boolean run(StepEmitter step);
    }

    @Test
    void aStepThatSucceedsIsCompleteWithItsChecklistRows() throws IOException {
        RunContext ctx = context("ok");
        LaunchStep step = step("Cleanup", s -> {
            s.check(true, "lock", "free", true);
            s.warn("sweep", "one file late");
            s.skip("extra", "not needed");
            return true;
        });

        assertTrue(StepRunner.runLocal(ctx, step));
        ctx.closeEvents();

        StepResult result = ctx.stepResults().get(LaunchStepNames.ALL.indexOf("Cleanup"));
        assertEquals(StepResult.COMPLETE, result.state());
        List<JsonNode> events = LaunchFixtures.events(ctx.runFolder());
        assertTrue(LaunchFixtures.types(events).containsAll(List.of("step.entered", "step.check", "step.finished")));
        String log = Files.readString(ctx.runFolder().resolve("run.log"));
        assertTrue(log.contains("PASS  lock"), log);
        assertTrue(log.contains("WARN  sweep"), log);
        assertTrue(log.contains("SKIP  extra"), log);
    }

    @Test
    void aThrownExceptionIsAFailedStepNotACrash() throws IOException {
        RunContext ctx = context("boom");
        LaunchStep step = step("Connections", s -> {
            throw new IllegalStateException("boom");
        });

        assertFalse(StepRunner.runLocal(ctx, step));
        ctx.closeEvents();

        StepResult result = ctx.stepResults().get(1);
        assertEquals(StepResult.FAILED, result.state());
        assertEquals("IllegalStateException: boom", result.detail());
    }

    @Test
    void aFailedCheckWithoutRequiredIsAWarningRowAndTheStepStillCompletes() throws IOException {
        RunContext ctx = context("soft");
        LaunchStep step = step("Start Simulators", s -> {
            s.check(false, "optional probe", "not there", false);
            return true;
        });

        assertTrue(StepRunner.runLocal(ctx, step));
        ctx.closeEvents();

        assertEquals(StepResult.COMPLETE, ctx.stepResults().get(2).state());
        String log = Files.readString(ctx.runFolder().resolve("run.log"));
        assertTrue(log.contains("WARN  optional probe"), log);
    }

    @Test
    void aFailedRequiredCheckFailsTheStepThroughFail() throws IOException {
        RunContext ctx = context("hard");
        LaunchStep step = step("Commander Configuration", s -> {
            s.check(false, "commander", "no answer", true);
            return s.fail("the Commander did not answer");
        });

        assertFalse(StepRunner.runLocal(ctx, step));
        ctx.closeEvents();

        StepResult result = ctx.stepResults().get(3);
        assertEquals(StepResult.FAILED, result.state());
        assertEquals("the Commander did not answer", result.detail());
    }

    @Test
    void anUnavailableStepSaysWhichPythonModuleAndWhatIsMissing() throws IOException {
        RunContext ctx = context("unavail");
        LaunchStep step = step("Tests Run", s -> s.unavailable("src/cat/launch/steps/tests_run.py", "the relay"));

        assertFalse(StepRunner.runLocal(ctx, step));
        ctx.closeEvents();

        assertEquals("unavailable in CAT-Java: needs the relay (Python: src/cat/launch/steps/tests_run.py)",
                ctx.stepResults().get(4).detail());
    }

    @Test
    void aStepSkippedByTheRunCarriesItsReasonAndNoStart() throws IOException {
        RunContext ctx = context("skip");
        StepRunner.skip(ctx, "Connections", "not reached: System Checks failed");
        ctx.closeEvents();

        StepResult result = ctx.stepResults().get(1);
        assertEquals(StepResult.SKIPPED, result.state());
        assertEquals("not reached: System Checks failed", result.detail());
        assertEquals(List.of("step.finished"), LaunchFixtures.types(LaunchFixtures.events(ctx.runFolder())));
    }
}
