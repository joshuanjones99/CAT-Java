package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.sims.Clicker;
import com.verifone.cat.sims.ProcessOps;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimStatus;
import com.verifone.cat.sims.Supervisor;

/**
 * Step 3 through the real supervisor, with a process layer that answers from fields and starts nothing.
 *
 * <p>The rows used are not started here: the supervisor's clicker can press nothing ({@code NoBridge}),
 * so a row that needs its Swing buttons pressed is reported {@code needs attention} and never spawned,
 * and the test can assert that the step fails and that no process was recorded. No socket is probed
 * either, so no simulator is adopted or touched.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake process layer only.
 */
class StartSimulatorsStepTest {

    @TempDir
    Path dir;

    /** A process layer that lists nothing and kills nothing. */
    private static final class QuietOps implements ProcessOps {
        @Override
        public Snapshot commandLines(List<String> names, Duration timeout) {
            return Snapshot.ok("");
        }

        @Override
        public Snapshot listenerCommandLine(int port, Duration timeout) {
            return Snapshot.ok("");
        }

        @Override
        public Snapshot listing(List<String> names, List<Integer> ports, Duration timeout) {
            return Snapshot.ok("");
        }

        @Override
        public Snapshot stillAlive(List<Long> pids, Duration timeout) {
            return Snapshot.ok("");
        }

        @Override
        public String taskkill(List<Long> pids, Duration timeout) {
            throw new AssertionError("a test must not kill a process: " + pids);
        }
    }

    /**
     * A clicker that can press nothing, so a row that needs its buttons is refused before it is spawned:
     * the step resolves the real tools root, where a real simulator could otherwise be launched.
     */
    private static final class NoBridge implements Clicker {
        @Override
        public Optional<String> unavailable(SimContext ctx) {
            return Optional.of("no Access Bridge in this test");
        }

        @Override
        public Clicked click(SimEntry entry, Process process, SimContext ctx, long deadline) {
            throw new AssertionError("a test must not click: " + entry.name());
        }

        @Override
        public void minimiseWhenShown(Process process, long deadline) {
            throw new AssertionError("a test must not spawn a windowed row");
        }
    }

    private static Supervisor supervisor() {
        return new Supervisor(new QuietOps(), Supervisor.STALE_BIND_WAIT, new NoBridge());
    }

    private RunContext context(LaunchConfig config, String runId) throws IOException {
        Path folder = dir.resolve(runId);
        return new RunContext(config, LaunchFixtures.paths(dir), runId, folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
    }

    @Test
    void noSimulatorSelectedSkipsTheStepAndSaysSo() throws IOException {
        LaunchConfig config = new LaunchConfig.Builder("").fakePos(true).build();
        RunContext ctx = context(config, "aaaaaaaaaaaa");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertTrue(new StartSimulatorsStep().run(ctx, step));

        assertEquals(StepResult.SKIPPED, step.state());
        assertEquals("no simulators selected — nothing started", step.detail());
    }

    @Test
    void aSelectedNameThatIsNotInTheRegistryIsAWarningRowAndFailsTheStepWhenNothingElseIsLeft()
            throws IOException {
        LaunchConfig config = new LaunchConfig.Builder("").fakePos(true).simulator("No Such Simulator").build();
        RunContext ctx = context(config, "bbbbbbbbbbbb");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertFalse(new StartSimulatorsStep().run(ctx, step));

        assertEquals(StepResult.FAILED, step.state());
        assertEquals("none of the selected simulators are in the registry", step.detail());
        assertTrue(ctx.simulators().isEmpty());
    }

    @Test
    void petroOnARigIsUnavailableBeforeAnythingIsStarted() throws IOException {
        LaunchConfig config = new LaunchConfig.Builder(LaunchFixtures.RIG)
                .simulator(StartSimulatorsStep.PETRO_SIMULATOR).build();
        RunContext ctx = context(config, "cccccccccccc");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertFalse(new StartSimulatorsStep().run(ctx, step));

        assertEquals(StepResult.FAILED, step.state());
        assertTrue(step.detail().startsWith("unavailable in CAT-Java: needs the Petro port holds"), step.detail());
        assertTrue(ctx.simulators().isEmpty());
    }

    @Test
    void aRowThatNeedsItsButtonsPressedNeedsAttentionFailsTheStepAndStartsNothing() throws IOException {
        // MPPA Server Sim is clicked and declares no readiness port: the supervisor finds it absent
        // and reports needs attention without probing a socket or spawning a process.
        LaunchConfig config = new LaunchConfig.Builder("").fakePos(true).simulator("MPPA Server Sim").build();
        RunContext ctx = context(config, "dddddddddddd");
        ctx.supervisor(supervisor());
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertFalse(new StartSimulatorsStep().run(ctx, step), "a needs-attention row must never pass");

        assertEquals(StepResult.FAILED, step.state());
        assertEquals("MPPA Server Sim needs attention: not started, because CAT cannot press \"Load Data\", then "
                + "\"Load Servers\" in its window: no Access Bridge in this test.", step.detail());
        assertTrue(ctx.simulators().isEmpty(), "nothing was spawned, so nothing is owned");
    }

    @Test
    void eachNeedsAttentionRowIsOneOperatorLineInTheStepFailure() throws IOException {
        LaunchConfig config = new LaunchConfig.Builder("").fakePos(true)
                .simulator("PCATS01 Loyalty").simulator("MPPA Server Sim").build();
        RunContext ctx = context(config, "eeeeeeeeeeee");
        ctx.supervisor(supervisor());
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertFalse(new StartSimulatorsStep().run(ctx, step));

        assertEquals(StepResult.FAILED, step.state());
        assertEquals("PCATS01 Loyalty needs attention: not started, because CAT cannot press \"Start Host\" in its "
                + "window: no Access Bridge in this test."
                + "\nMPPA Server Sim needs attention: not started, because CAT cannot press \"Load Data\", then "
                + "\"Load Servers\" in its window: no Access Bridge in this test.", step.detail());
    }

    @Test
    void theOutcomeOfEachSupervisorStateIsTheChecklistOutcome() {
        assertEquals("running", StartSimulatorsStep.outcome(SimStatus.State.STARTING));
        assertEquals("passed", StartSimulatorsStep.outcome(SimStatus.State.READY));
        assertEquals("passed", StartSimulatorsStep.outcome(SimStatus.State.ADOPTED));
        assertEquals("warned", StartSimulatorsStep.outcome(SimStatus.State.STARTED));
        assertEquals("warned", StartSimulatorsStep.outcome(SimStatus.State.NEEDS_ATTENTION));
        assertEquals("failed", StartSimulatorsStep.outcome(SimStatus.State.FAILED));
    }
}
