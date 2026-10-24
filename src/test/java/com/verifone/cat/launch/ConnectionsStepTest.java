package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.connections.ConnectionProbe;
import com.verifone.cat.connections.ProbeResult;
import com.verifone.cat.connections.ProbeState;
import com.verifone.cat.connections.Target;
import com.verifone.cat.provision.transport.SshLogin;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 2 with a rig loaded: its probes, the failure a down fixed row causes, the SSH login checks, and
 * the Xray row behind its switch. Every SSH login and every Xray sign-in here is a fake, so no test
 * signs in to a box or to Atlassian. A fixed row that is up needs the Commander's CGILink port 80 on this
 * PC, which a test does not bind, so the full success path is not driven here.
 *
 * <p>The bench rig's names do not resolve, so every probe is DOWN at once and nothing leaves this PC.
 * UNVERIFIED AGAINST HARDWARE.
 */
class ConnectionsStepTest {

    @TempDir
    Path dir;

    /** Fails the test if an SSH login is attempted: a run whose fixed rows are down signs in to nothing. */
    private static final Function<SshTarget, Optional<SshLogin.Problem>> NO_SSH = spec -> {
        throw new AssertionError("no SSH login was expected, for " + spec.name());
    };

    /** A signed-in Xray row. */
    private static final Function<Target, ProbeResult> XRAY_UP = target -> new ProbeResult(
            target.name(), ProbeState.UP, "signed in as tester", Target.Kind.XRAY);

    private RunContext context(LaunchConfig config, String runId) throws IOException {
        Path folder = dir.resolve(runId);
        return new RunContext(config, LaunchFixtures.paths(dir), runId, folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
    }

    private RunContext rigContext(LaunchConfig config, String runId) throws IOException {
        RunContext ctx = context(config, runId);
        ctx.rig(RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir()));
        return ctx;
    }

    @Test
    void aRunWithNoRigSkipsTheStepAndProbesNothing() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder("").fakePos(true).build(), "aaaaaaaaaaaa");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.CONNECTIONS);

        assertTrue(new ConnectionsStep(new ConnectionChecks(NO_SSH, XRAY_UP)).run(ctx, step));

        assertEquals(StepResult.SKIPPED, step.state());
        assertEquals("no rig loaded — nothing to probe", step.detail());
    }

    @Test
    void aDownFixedRowFailsTheStepBeforeAnySshCheckIsReached() throws IOException {
        RunContext ctx = rigContext(LaunchConfig.forRig(LaunchFixtures.RIG), "bbbbbbbbbbbb");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.CONNECTIONS);

        assertFalse(new ConnectionsStep(new ConnectionChecks(NO_SSH, XRAY_UP)).run(ctx, step));

        assertEquals(StepResult.FAILED, step.state());
        // The Host PC is loopback, which this PC always holds, so it is UP and is not named.
        assertEquals("not reachable: Commander, Register topaz101", step.detail());
    }

    @Test
    void anXrayRowIsProbedBehindTheSwitchAndNeverFailsTheStep() throws IOException {
        List<Target> asked = new CopyOnWriteArrayList<>();
        Function<Target, ProbeResult> xray = target -> {
            asked.add(target);
            return new ProbeResult(target.name(), ProbeState.DOWN, "no credentials", Target.Kind.XRAY);
        };
        RunContext ctx = rigContext(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).build(),
                "cccccccccccc");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.CONNECTIONS);

        assertFalse(new ConnectionsStep(new ConnectionChecks(NO_SSH, xray)).run(ctx, step));

        // The fixed rows still decide the step; the Xray row is asked, and its red answer is not named.
        assertEquals("not reachable: Commander, Register topaz101", step.detail());
        assertEquals(1, asked.size());
        assertEquals("Xray", asked.get(0).name());
        assertEquals(Target.Kind.XRAY, asked.get(0).kind());
        assertEquals("xray.cloud.getxray.app", asked.get(0).host());
    }

    @Test
    void theXrayRowIsLeftOutOfAFakePosRun() throws IOException {
        List<Target> asked = new CopyOnWriteArrayList<>();
        Function<Target, ProbeResult> xray = target -> {
            asked.add(target);
            return XRAY_UP.apply(target);
        };
        RunContext ctx = rigContext(new LaunchConfig.Builder(LaunchFixtures.RIG).fakePos(true).xrayReport(true)
                .build(), "dddddddddddd");

        new ConnectionsStep(new ConnectionChecks(NO_SSH, xray)).run(ctx, new StepEmitter(ctx,
                LaunchStepNames.CONNECTIONS));

        assertTrue(asked.isEmpty(), "a fake-POS run never asks Xray");
    }

    @Test
    void aRefusedSshLoginIsNamedAndAnUnreachableOneIsNot() throws IOException {
        RunContext ctx = rigContext(LaunchConfig.forRig(LaunchFixtures.RIG), "eeeeeeeeeeee");
        Rig bench = ctx.rig().rig();
        List<String> asked = new CopyOnWriteArrayList<>();
        Function<SshTarget, Optional<SshLogin.Problem>> refuseRegister = spec -> {
            asked.add(spec.name());
            return spec.name().equals("topaz101")
                    ? Optional.of(new SshLogin.Problem("topaz101 (10.0.0.2) refused CAT's SSH key", true))
                    : Optional.empty();
        };
        ConnectionsStep step = new ConnectionsStep(new ConnectionChecks(refuseRegister, XRAY_UP));
        ExecutorService pool = ConnectionProbe.newProbePool();
        try {
            List<String> refused = step.sshLogins(bench, pool, new StepEmitter(ctx, LaunchStepNames.CONNECTIONS));

            assertEquals(List.of("topaz101 (10.0.0.2) refused CAT's SSH key"), refused);
            // Every provisioning target is signed in to. The logins run concurrently, so the order the fake
            // saw them start in is not fixed; the order of the refusals and check rows is (input order).
            List<String> seen = new ArrayList<>(asked);
            Collections.sort(seen);
            assertEquals(List.of("commander", "topaz101"), seen);
        } finally {
            pool.shutdownNow();
        }

        // An SSH that cannot be reached is reported as a warning row, and does not end the run.
        ConnectionsStep quiet = new ConnectionsStep(new ConnectionChecks(spec -> Optional.of(
                new SshLogin.Problem("SSH to " + spec.name() + " failed: timed out", false)), XRAY_UP));
        ExecutorService other = ConnectionProbe.newProbePool();
        try {
            assertTrue(quiet.sshLogins(bench, other, new StepEmitter(ctx, LaunchStepNames.CONNECTIONS)).isEmpty());
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void thePrinterRowIsWhatAnUnportedRigRunNames() throws IOException {
        Rig bench = RigLoader.loadResolved(LaunchFixtures.RIG, LaunchFixtures.paths(dir).rigsDir()).rig();
        assertFalse(ConnectionsStep.printerRowPresent(bench));

        Path printer = dir.resolve("printer.yaml");
        Files.writeString(printer, """
                name: printer
                commander:
                  host: commander.invalid
                  ssh_user: root
                  eps_port: 41107
                registers:
                  - id: topaz101
                    host: register.invalid
                    printer_port: COM3
                controller:
                  host: 127.0.0.1
                pinpad: viperpay
                build:
                  expected_version: "057.02"
                """);
        Rig withPrinter = RigLoader.loadFile(printer).rig();
        assertTrue(ConnectionsStep.printerRowPresent(withPrinter));
        assertTrue(ConnectionsStep.printerMissing().contains("the Receipt Printer row"));
    }
}
