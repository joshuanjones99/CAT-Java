package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.verifone.cat.connections.ConnectionProbe;
import com.verifone.cat.connections.ConnectionWatch;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.connections.ProbeResult;
import com.verifone.cat.connections.ProbeState;
import com.verifone.cat.connections.Target;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;

/**
 * Step 2, Connections: probe the run's devices, then re-probe for the rest of the run. The Python
 * CAT's {@code cat.launch.steps.connections}.
 *
 * <p>The targets are {@link Endpoints#targetsFor}: the Commander (CGILink, port 80), the primary
 * register (SSH, port 22) and the Host PC, all fixed. A fixed row that is not up fails the step. A
 * run with no rig has nothing to probe, and the step is skipped.
 *
 * <p>Two parts are unavailable, and so the step fails after its probes on a rig run. The SSH login
 * check needs an SSH client, which CAT-Java does not have ({@code petrotest.provision.transport}).
 * The Receipt Printer row is a serial listing, which needs the printer slice; a rig whose primary
 * register declares a {@code printer_port} says so. The Xray row is absent because a run does not
 * carry the Xray switch yet. A fake-POS run needs none of the first two (the Python CAT skips the SSH
 * check there and adds no printer row), so it completes, and starts the connection watch.
 *
 * <p>The watch re-probes every {@link #WATCH_INTERVAL} and emits only the changes. It is stopped by
 * {@link RunContext#stopWatching}, which the launcher calls when the run ends.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the probes are TCP connects and an interface check to the rig's
 * addresses; the tests use loopback and {@code .invalid} names only.
 */
final class ConnectionsStep implements LaunchStep {

    /** The budget for one probe, as the Python CAT's {@code probe} default. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** How often the watch re-probes: the Python CAT's {@code CONNECTION_WATCH_INTERVAL}. */
    static final Duration WATCH_INTERVAL = Duration.ofSeconds(10);

    @Override
    public String name() {
        return LaunchStepNames.CONNECTIONS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        LoadedRig loaded = ctx.rig();
        if (loaded == null) {
            step.skip("connections", "no rig loaded for this run");
            return step.skipStep("no rig loaded — nothing to probe");
        }
        Rig rig = loaded.rig();
        List<Target> targets = Endpoints.targetsFor(rig, List.of());

        ExecutorService pool = ConnectionProbe.newProbePool();
        boolean watching = false;
        try {
            ConnectionProbe probe = new ConnectionProbe(pool);
            List<ProbeResult> results = probe.probeAll(targets, PROBE_TIMEOUT);
            List<String> down = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Target target = targets.get(i);
                ProbeResult result = results.get(i);
                boolean ok = result.state() == ProbeState.UP;
                ctx.emit(stateEvent(result));
                step.check(ok, result.target(), result.detail(), target.fixed());
                step.progress(result.target(), i + 1, targets.size());
                if (!ok && target.fixed()) {
                    down.add(result.target());
                }
            }
            if (!down.isEmpty()) {
                return step.fail("not reachable: " + String.join(", ", down));
            }
            if (!ctx.config().fakePos()) {
                return step.unavailable("src/cat/launch/steps/connections.py", missing(rig));
            }
            startWatch(ctx, probe, targets, pool);
            watching = true;
            return true;
        } finally {
            if (!watching) {
                pool.shutdownNow();
            }
        }
    }

    /** The parts of step 2 this port does not have, for a rig run, named in words. */
    static String missing(Rig rig) {
        String printer = rig.primaryRegister().printerPort() == null || rig.primaryRegister().printerPort().isEmpty()
                ? "" : ", the Receipt Printer row (a serial listing, the printer slice)";
        return "the SSH login check (petrotest.provision.transport: no SSH client in CAT-Java)" + printer;
    }

    /** Starts the re-probe, and registers its stop with the run. */
    private static void startWatch(RunContext ctx, ConnectionProbe probe, List<Target> targets, ExecutorService pool) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cat-connection-watch");
            thread.setDaemon(true);
            return thread;
        });
        ConnectionWatch watch = new ConnectionWatch(probe, targets, PROBE_TIMEOUT,
                result -> ctx.emit(stateEvent(result)),
                message -> ctx.emit(new CatEvent.LogLine("WARNING", "cat.connections", message,
                        LaunchStepNames.CONNECTIONS)));
        ScheduledFuture<?> rounds = watch.start(scheduler, WATCH_INTERVAL);
        ctx.watching(() -> {
            rounds.cancel(true);
            scheduler.shutdownNow();
            pool.shutdownNow();
        });
    }

    private static CatEvent.ConnectionState stateEvent(ProbeResult result) {
        return new CatEvent.ConnectionState(result.target(), result.state().name().toLowerCase(Locale.ROOT),
                result.detail(), null);
    }
}
