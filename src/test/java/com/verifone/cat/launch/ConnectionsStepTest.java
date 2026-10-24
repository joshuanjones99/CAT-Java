package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 2 with a rig loaded: its probes, the failure a down fixed row causes, and the SSH check that
 * stays unavailable. A fixed row that is up needs the Commander's CGILink port 80 on this PC, which a
 * test does not bind, so the success path is not driven here (the probes are covered in
 * {@code connections}).
 *
 * <p>The bench rig's Commander and register names do not resolve, so every probe is DOWN at once and
 * nothing leaves this PC. UNVERIFIED AGAINST HARDWARE.
 */
class ConnectionsStepTest {

    @TempDir
    Path dir;

    private RunContext context(LaunchConfig config, String runId) throws IOException {
        Path folder = dir.resolve(runId);
        return new RunContext(config, LaunchFixtures.paths(dir), runId, folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
    }

    @Test
    void aRunWithNoRigSkipsTheStepAndProbesNothing() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder("").fakePos(true).build(), "aaaaaaaaaaaa");
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.CONNECTIONS);

        assertTrue(new ConnectionsStep().run(ctx, step));

        assertEquals(StepResult.SKIPPED, step.state());
        assertEquals("no rig loaded — nothing to probe", step.detail());
    }

    @Test
    void aDownFixedRowFailsTheStepBeforeAnySshCheckIsReached() throws IOException {
        RunContext ctx = context(LaunchConfig.forRig(LaunchFixtures.RIG), "bbbbbbbbbbbb");
        LoadedRig loaded = RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir());
        ctx.rig(loaded);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.CONNECTIONS);

        assertFalse(new ConnectionsStep().run(ctx, step));

        assertEquals(StepResult.FAILED, step.state());
        // The Host PC is loopback, which this PC always holds, so it is UP and is not named.
        assertEquals("not reachable: Commander, Register topaz101", step.detail());
    }

    @Test
    void theSshCheckAndThePrinterRowAreWhatAUnportedRigRunNames() throws IOException {
        Rig bench = RigLoader.loadResolved(LaunchFixtures.RIG, LaunchFixtures.paths(dir).rigsDir()).rig();
        String benchMissing = ConnectionsStep.missing(bench);
        assertTrue(benchMissing.startsWith("the SSH login check (petrotest.provision.transport"), benchMissing);
        assertFalse(benchMissing.contains("Receipt Printer"), benchMissing);

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
        String printerMissing = ConnectionsStep.missing(RigLoader.loadFile(printer).rig());
        assertTrue(printerMissing.contains("the Receipt Printer row"), printerMissing);
    }
}
