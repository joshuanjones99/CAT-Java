package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.devices.DeviceUnreachable;

/**
 * Petro's pump-state port holds across the steps, with a fake opener (no socket is bound), and the port guard's
 * reading of {@code netstat}. Evidence about this repository only.
 */
class PetroPortsTest {

    @TempDir
    Path dir;

    /** An opener that records each open and close, and refuses the ports in {@code taken}. */
    private static final class Opener implements PetroPorts.Opener {
        final List<String> calls = new ArrayList<>();
        final Set<Integer> taken;

        Opener(Set<Integer> taken) {
            this.taken = taken;
        }

        @Override
        public AutoCloseable open(int port) {
            if (taken.contains(port)) {
                throw new DeviceUnreachable("the Petro suite's port " + port, "already held");
            }
            calls.add("open " + port);
            return () -> calls.add("close " + port);
        }
    }

    private RunContext context(LaunchConfig config) throws IOException {
        Path folder = dir.resolve("aaaaaaaaaaaa");
        return new RunContext(config, LaunchFixtures.paths(dir), "aaaaaaaaaaaa", folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
    }

    @Test
    void bothPortsAreHeldOnceAndATakenPortIsLeftToItsHolder() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG));
        Opener opener = new Opener(Set.of(1072));
        ctx.petroPortOpener(opener);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        PetroPorts.hold(ctx, step);
        PetroPorts.hold(ctx, step);

        assertEquals(List.of("open 9706"), opener.calls, "a held port is not opened again");
        assertEquals(Set.of(9706), ctx.petroSinks().keySet());
    }

    @Test
    void patsPortIsLentAndHeldAgainWhileTheUiPortStaysHeld() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG));
        Opener opener = new Opener(Set.of());
        ctx.petroPortOpener(opener);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.TESTS);
        PetroPorts.hold(ctx, step);

        assertTrue(PetroPorts.release(ctx, 1072), "held, so the caller holds it again after");
        assertFalse(PetroPorts.release(ctx, 1072), "not held twice");
        assertEquals(Set.of(9706), ctx.petroSinks().keySet());
        PetroPorts.hold(ctx, step);
        PetroPorts.releaseAll(ctx);

        assertEquals(List.of("open 9706", "open 1072", "close 1072", "open 1072", "close 9706", "close 1072"),
                sortedTail(opener.calls));
        assertTrue(ctx.petroSinks().isEmpty());
    }

    /** The calls, with the order of the last two (releaseAll's, over a map) made stable. */
    private static List<String> sortedTail(List<String> calls) {
        List<String> head = new ArrayList<>(calls.subList(0, calls.size() - 2));
        List<String> tail = new ArrayList<>(calls.subList(calls.size() - 2, calls.size()));
        tail.sort((a, b) -> b.compareTo(a));
        head.addAll(tail);
        return head;
    }

    @Test
    void stepThreeFailsOnlyWhenPatsPortIsTakenAndTheRunDrivesPumps() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG));
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SIMULATORS);

        assertTrue(PetroPorts.patsPortFreeForPumps(ctx, step, Optional.of(false)), "no pump case");
        assertTrue(PetroPorts.patsPortFreeForPumps(ctx, step, Optional.empty()), "unknown cases go on");
        assertFalse(PetroPorts.patsPortFreeForPumps(ctx, step, Optional.of(true)));
        assertEquals(StepResult.FAILED, step.state());

        ctx.petroSinks().put(1072, () -> { });
        assertTrue(PetroPorts.patsPortFreeForPumps(ctx, new StepEmitter(ctx, LaunchStepNames.SIMULATORS),
                Optional.of(true)), "the run's own hold passes");
    }

    @Test
    void theGuardNamesWhatHoldsAPortThatIsNotTheRunsOwn() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG));
        ctx.petroSinks().put(9706, () -> { });
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.SYSTEM_CHECKS);

        List<String> failures = SystemChecksStep.checkPetroPorts(ctx, step, port -> false, port -> "pid 42 (java)");

        assertEquals(List.of("Petro's pump-state ports free — 1072 is held by pid 42 (java) — the pumps cannot be "
                + "served, and every fuel case would fail"), failures);
        assertTrue(SystemChecksStep.checkPetroPorts(ctx, step, port -> true, port -> "nobody").isEmpty());
    }

    @Test
    void netstatsListenerIsReadAndACatIsNamedAsOne() {
        String netstat = """
                  Proto  Local Address          Foreign Address        State           PID
                  TCP    0.0.0.0:1072           0.0.0.0:0              LISTENING       32852
                  TCP    0.0.0.0:10720          0.0.0.0:0              LISTENING       7
                  TCP    [::]:1072              [::]:0                 LISTENING       32852
                """;
        assertEquals(Optional.of(32852L), PetroPorts.listeningPid(netstat, 1072));
        assertEquals(Optional.empty(), PetroPorts.listeningPid(netstat, 9706));
        assertEquals("a CAT window (pid 5): close it, or start this run from it",
                PetroPorts.describeHolder(5, "python -c from cat import main"));
        assertEquals("pid 9", PetroPorts.describeHolder(9, ""));
    }
}
