package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.connections.ConnectionProbe;
import com.verifone.cat.connections.ConnectionWatch;
import com.verifone.cat.connections.Endpoints;
import com.verifone.cat.connections.ProbeResult;
import com.verifone.cat.connections.ProbeState;
import com.verifone.cat.connections.Target;
import com.verifone.cat.provision.transport.SshLogin;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;

/**
 * Step 2, Connections: probe the run's devices, check the SSH login to each provisioning target, then
 * re-probe for the rest of the run. The Python CAT's {@code cat.launch.steps.connections}.
 *
 * <p>The probe targets are {@link Endpoints#targetsFor}: the Commander (CGILink, port 80), the primary
 * register (SSH, port 22) and the Host PC, all fixed, then the optional devices in {@link LaunchConfig#connections()}
 * (never fixed, so one that is down is a warning). A fixed row that is not up fails the step, and
 * then no SSH login is tried (a box known to be down is not signed in to; the Python tries, and reports
 * the failure). With the rig reachable, each provisioning target is signed in to, the Commander first,
 * all at once: a <em>refused</em> login fails the step and ends the run, because step 4 cannot provision
 * a box that will not take CAT's key; a box SSH cannot reach is only reported.
 *
 * <p>With the run reporting to Xray ({@link LaunchConfig#xrayReport()}, never under fake POS) the Xray row
 * is added after the fixed rows. It is never fixed, so a red Xray row is a warning, not a failed step.
 *
 * <p>One part is still unavailable, so a rig whose primary register declares a {@code printer_port}
 * fails the step after the checks: the Receipt Printer row is a serial listing, which needs the printer
 * slice. A fake-POS run needs none of the SSH or printer checks (the Python CAT skips them there), so it
 * completes and starts the connection watch.
 *
 * <p>The watch re-probes every {@link #WATCH_INTERVAL} and emits only the changes. It is stopped by
 * {@link RunContext#stopWatching}, which the launcher calls when the run ends.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the probes are TCP connects, an interface check and the Xray sign-in;
 * the SSH logins are JSch to the rig's addresses. The tests inject fake checks and use loopback and
 * {@code .invalid} names only.
 */
final class ConnectionsStep implements LaunchStep {

    /** The budget for one probe, as the Python CAT's {@code probe} default. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** How often the watch re-probes: the Python CAT's {@code CONNECTION_WATCH_INTERVAL}. */
    static final Duration WATCH_INTERVAL = Duration.ofSeconds(10);

    /** The Xray row's label, as the Python CAT's {@code XRAY_TARGET_NAME}. */
    static final String XRAY_ROW = "Xray";

    private static final String PRINTER_PYTHON_MODULE = "src/cat/launch/steps/connections.py";

    private final ConnectionChecks checks;

    /** The real checks: the JSch login and the Xray sign-in. */
    ConnectionsStep() {
        this(ConnectionChecks.real());
    }

    /** The checks to use; a test passes fakes. */
    ConnectionsStep(ConnectionChecks checks) {
        this.checks = checks;
    }

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
        LaunchConfig config = ctx.config();
        List<Target> targets = new ArrayList<>(Endpoints.targetsFor(rig, config.connections()));
        if (config.xrayReport() && !config.fakePos()) {
            targets.add(Target.xray(XRAY_ROW, AtlassianClient.XRAY_API.getHost()));
        }

        ExecutorService pool = ConnectionProbe.newProbePool();
        boolean watching = false;
        try {
            ConnectionProbe probe = new ConnectionProbe(pool, checks.xray());
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
            if (!config.fakePos()) {
                List<String> refused = sshLogins(rig, pool, step);
                if (!refused.isEmpty()) {
                    return step.fail("SSH login failed — " + String.join("; ", refused));
                }
                if (printerRowPresent(rig)) {
                    return step.unavailable(PRINTER_PYTHON_MODULE, printerMissing());
                }
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

    /**
     * The SSH login to each provisioning target, the Commander first, then the registers, all at once. Each
     * is a check row: a login that works passes, a refused one fails and is required, and one SSH cannot
     * reach is a warning. Returns the refused logins' words, which end the run.
     */
    List<String> sshLogins(Rig rig, ExecutorService pool, StepEmitter step) {
        Map<String, String> environ = System.getenv();
        List<SshTarget> specs = new ArrayList<>();
        specs.add(SshTarget.commander(rig, environ));
        for (Rig.Register register : rig.registers()) {
            specs.add(SshTarget.register(rig, register, environ));
        }
        List<CompletableFuture<Optional<SshLogin.Problem>>> outcomes = new ArrayList<>();
        for (SshTarget spec : specs) {
            outcomes.add(CompletableFuture.supplyAsync(() -> sshCheck(spec), pool));
        }
        List<String> refused = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            SshTarget spec = specs.get(i);
            Optional<SshLogin.Problem> problem = outcomes.get(i).join();
            boolean refusedLogin = problem.isPresent() && problem.get().refused();
            String text = problem.map(SshLogin.Problem::message).orElse("key login accepted");
            step.check(problem.isEmpty(), "SSH " + spec.name(), text, refusedLogin);
            if (refusedLogin) {
                refused.add(text);
            }
        }
        return refused;
    }

    /** One login, with an unexpected failure reported as that target's problem rather than thrown. */
    private Optional<SshLogin.Problem> sshCheck(SshTarget spec) {
        try {
            return checks.ssh().apply(spec);
        } catch (RuntimeException e) {
            return Optional.of(new SshLogin.Problem(
                    "SSH to " + spec.name() + " (" + spec.host() + ") failed: " + e.getMessage(), false));
        }
    }

    /** Whether the primary register declares a printer port, which this port cannot probe yet. */
    static boolean printerRowPresent(Rig rig) {
        String port = rig.primaryRegister().printerPort();
        return port != null && !port.isEmpty();
    }

    /** The part of step 2 this port does not have, for a rig run, named in words. */
    static String printerMissing() {
        return "the Receipt Printer row (a serial listing, the printer slice)";
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
