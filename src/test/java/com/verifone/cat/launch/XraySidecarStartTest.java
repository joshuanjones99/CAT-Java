package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.connections.ProbeResult;
import com.verifone.cat.connections.ProbeState;
import com.verifone.cat.connections.Target;
import com.verifone.cat.provision.transport.SshLogin;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.xray.XraySidecar;

/**
 * The Xray sidecar's start: whether it is spawned, what the spawn is given, and what the run's log says
 * about it. The spawn is a fake, so no test starts a process; the argv is parsed by the sidecar's own
 * command line, so it is checked to be one the sidecar accepts.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
class XraySidecarStartTest {

    private static final String PLAN = "PETROCORE-1";

    @TempDir
    Path dir;

    private final List<List<String>> spawned = new CopyOnWriteArrayList<>();
    private final List<Path> logs = new CopyOnWriteArrayList<>();

    private XraySidecarStart.Spawner recording(long pid) {
        return (argv, log, cwd) -> {
            spawned.add(argv);
            logs.add(log);
            return pid;
        };
    }

    private RunContext context(LaunchConfig config, String runId) throws IOException {
        Path folder = dir.resolve(runId);
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), runId, folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        ctx.rig(RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir()));
        return ctx;
    }

    /** The run's events as text, after the stream is closed (the launcher closes it at the run's end). */
    private static String eventsText(RunContext ctx) throws IOException {
        ctx.closeEvents();
        return Files.readString(ctx.runFolder().resolve("events.ndjson"));
    }

    @Test
    void aRunThatDoesNotReportToXrayStartsNothing() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder(LaunchFixtures.RIG).testPlan(PLAN).build(), "aaaaaaaaaaaa");

        XraySidecarStart.start(ctx, recording(4242));

        assertTrue(spawned.isEmpty());
        assertFalse(eventsText(ctx).contains("Xray:"));
    }

    @Test
    void aFakePosRunSaysWhyItReportsNothing() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder(LaunchFixtures.RIG).fakePos(true).xrayReport(true)
                .testPlan(PLAN).build(), "bbbbbbbbbbbb");

        XraySidecarStart.start(ctx, recording(4242));

        assertTrue(spawned.isEmpty());
        assertTrue(eventsText(ctx).contains("a fake POS run is evidence about this repository"));
    }

    @Test
    void aRunWithNoTestPlanReportsNothing() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).build(),
                "cccccccccccc");

        XraySidecarStart.start(ctx, recording(4242));

        assertTrue(spawned.isEmpty());
        assertTrue(eventsText(ctx).contains("no test plan was chosen"));
    }

    @Test
    void theSidecarIsSpawnedForTheRunAndItsArgvIsTheSidecarsOwnCommandLine() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).testPlan(PLAN)
                .build(), "dddddddddddd");

        XraySidecarStart.start(ctx, recording(4242));

        assertEquals(1, spawned.size());
        List<String> argv = spawned.get(0);
        assertEquals("-cp", argv.get(1));
        assertEquals("com.verifone.cat.Main", argv.get(3));
        assertEquals("xray", argv.get(4));
        assertEquals(ctx.runFolder().resolve("xray.log"), logs.get(0));

        // The sidecar's parser takes everything after "xray": the run, the root and the upgrade mode.
        XraySidecar.Options options = XraySidecar.parse(Arrays.copyOfRange(argv.toArray(String[]::new), 5,
                argv.size()), Map.of());
        assertEquals("dddddddddddd", options.runId());
        assertEquals(LaunchFixtures.paths(dir).resultsRoot(), options.resultsRoot());
        assertEquals(argv.get(argv.indexOf("--upgrade") + 1), options.upgrade());

        String text = eventsText(ctx);
        assertTrue(text.contains("Xray: reporting sidecar started (pid 4242) for " + PLAN), text);
        // The path is JSON-escaped in the event, so the file name is what is checked.
        assertTrue(text.contains("its log is ") && text.contains("xray.log"), text);
    }

    @Test
    void aSidecarThatWillNotStartIsOneWarningAndTheRunGoesOn() throws IOException {
        RunContext ctx = context(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).testPlan(PLAN)
                .build(), "eeeeeeeeeeee");
        XraySidecarStart.Spawner broken = (argv, log, cwd) -> {
            throw new IOException("no such program");
        };

        XraySidecarStart.start(ctx, broken);

        String text = eventsText(ctx);
        assertTrue(text.contains("Xray reporting sidecar failed to start"), text);
        assertTrue(text.contains("the run continues without it"), text);
    }

    @Test
    void aRunThatStoppedBeforeTestsSaysNoExecutionWasMade() throws IOException {
        RunContext on = context(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).testPlan(PLAN)
                .build(), "ffffffffffff");
        XraySidecarStart.notReported(on, "System Checks");
        assertTrue(eventsText(on).contains("the run stopped at System Checks before any test ran"));

        RunContext off = context(new LaunchConfig.Builder(LaunchFixtures.RIG).testPlan(PLAN).build(),
                "111111111111");
        XraySidecarStart.notReported(off, "System Checks");
        assertFalse(eventsText(off).contains("not reported"));
    }

    @Test
    void aRigRunThatStopsInStepOneNeverStartsTheSidecar() throws IOException {
        // Steps 1 to 4 cannot pass on the bench rig (its Commander does not resolve), so the run stops in
        // System Checks: the Xray row is never reached, and the sidecar is not spawned.
        Function<SshTarget, Optional<SshLogin.Problem>> noSsh = spec -> {
            throw new AssertionError("no SSH login was expected");
        };
        Function<Target, ProbeResult> xray = target -> new ProbeResult(target.name(), ProbeState.UP,
                "signed in as tester", Target.Kind.XRAY);
        LaunchPaths paths = LaunchFixtures.paths(dir);
        LaunchResult result = new RunLauncher(paths, Clock.systemUTC(),
                new PowerShellProcessOps(), new ConnectionChecks(noSsh, xray), recording(4242))
                .launch(new LaunchConfig.Builder(LaunchFixtures.RIG).xrayReport(true).testPlan(PLAN).build());

        assertTrue(spawned.isEmpty());
        String text = Files.readString(result.runFolder().resolve("events.ndjson"));
        assertTrue(text.contains("not reported — the run stopped at System Checks before any test ran"), text);
    }
}
