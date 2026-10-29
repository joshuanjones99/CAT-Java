package com.verifone.cat.launch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.Outcome;
import com.verifone.cat.report.events.RunEnded;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.sims.ProcessOps;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.Supervisor;

/**
 * Drives one run through the seven steps: the Python CAT's {@code cat.launch.run.launch_run} and its
 * {@code prepare_rig} and {@code release_rig}.
 *
 * <p>Order, as the Python's: {@code RunStarted}; run start ({@link PlanStart}: the plan's tests collected, the plan
 * resolved into {@code plan.resolved}, the run widened to the whole plan in plan order, narrowed to an opt-in partial
 * selection, then the plans' requirements applied, {@link RunRequirements}); {@code RunStarted} again, restated, when
 * run start changed the targets; steps 1 to 4 (a step that ends the run marks the rest of 1 to 5 skipped with
 * {@code not reached: <step> failed}); step 5 (unavailable unless wired, so the run ends with CAT's own
 * {@code RunFinished}, {@code error}, with the counts so far, unless step 5 wrote the session's own, see
 * {@link #wireTests}); steps 6 and 7 always. Then the run's locks are released and {@code run.ended} is written,
 * whatever happened.
 *
 * <p>Pause and Stop, as the Python CAT has them. Pause takes effect before the next step. Stop is
 * honoured before the next step or while paused: the run then writes a {@code RunFinished} that is
 * cancelled and skipped, and does not run steps 6 and 7 (the Python CAT does not write a report over
 * a run the operator stopped). The Python CAT cancels an asyncio task; here {@link #stop()} is a flag,
 * so a step that is already running finishes its own work first.
 *
 * <p>A partial run (tags, cases, from a case) is made from the whole plan at run start; one whose selection cannot be
 * made (a key not in the plan, a tag expression that does not parse, nothing selected) is refused before step 1, its
 * steps 1 to 5 skipped with {@code partial run refused: <why>}, as the Python's.
 *
 * <p>One launcher launches one run. Methods are thread-safe for pause and stop; {@link #launch} blocks
 * for the whole run and is called from one thread.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no step here touches a device. Fake-backed tests only.
 */
public final class RunLauncher {

    /** The skipped steps' reason when a partial selection cannot be made: {@code partial run refused: <why>}. */
    static final String PARTIAL_REFUSED = "partial run refused: ";

    private final LaunchPaths paths;
    private final Clock clock;
    private final ProcessOps processOps;
    private final ConnectionChecks checks;
    private final XraySidecarStart.Spawner spawner;
    private final PauseGate pause = new PauseGate();
    private final AtomicBoolean launched = new AtomicBoolean();
    private volatile boolean stopRequested;
    private volatile RunContext active;
    private volatile RegistryFactory registries;
    private volatile CaseSource cases;
    private volatile RunServices.Connector connector = RunServices.Connector.NONE;
    private volatile java.util.Map<Integer, AutoCloseable> callerSinks;

    /** A launcher on the system UTC clock, driving the simulators through PowerShell. */
    public RunLauncher(LaunchPaths paths) {
        this(paths, Clock.systemUTC());
    }

    /** A launcher on {@code clock}, so a test can fix the run's times. */
    public RunLauncher(LaunchPaths paths, Clock clock) {
        this(paths, clock, new PowerShellProcessOps());
    }

    /**
     * A launcher on {@code clock} whose simulator supervisor asks {@code processOps} about processes.
     * A test passes a fake, so no real process is listed or started.
     */
    RunLauncher(LaunchPaths paths, Clock clock, ProcessOps processOps) {
        this(paths, clock, processOps, ConnectionChecks.real(), XraySidecarStart.detached());
    }

    /**
     * A launcher whose outside checks (the SSH logins and the Xray sign-in) and whose sidecar spawn are
     * the caller's. A test passes fakes, so no run signs in to a box or to Atlassian, or starts a process.
     */
    RunLauncher(LaunchPaths paths, Clock clock, ProcessOps processOps, ConnectionChecks checks,
            XraySidecarStart.Spawner spawner) {
        this.paths = paths;
        this.clock = clock;
        this.processOps = processOps;
        this.checks = checks;
        this.spawner = spawner;
    }

    /**
     * Wires step 5, Tests Run: the action registry, the run's cases and the device connector. Until this is
     * called the step is unavailable, which fails the run's step 5 rather than passing it. Call before
     * {@link #launch}.
     *
     * @param registries the registry factory; {@code null} leaves the step unavailable
     * @param cases      the run's cases, in plan order; {@code null} leaves the step unavailable
     * @param connector  adds the devices the run connects; {@code null} connects none
     */
    public void wireTests(RegistryFactory registries, CaseSource cases, RunServices.Connector connector) {
        this.registries = registries;
        this.cases = cases;
        this.connector = connector == null ? RunServices.Connector.NONE : connector;
    }

    /**
     * Petro's held pump-state ports are the caller's (the Python's {@code launch_run(petro_sinks=...)}): shared across a
     * continuous run's passes, or held by Launch Simulators for a Petro it left running. The run then releases none of
     * them, the ones it takes included, and the caller closes them. Call before {@link #launch}.
     */
    public void usePetroSinks(java.util.Map<Integer, AutoCloseable> sinks) {
        this.callerSinks = sinks;
    }

    /** Pauses the run before its next step (or, if it is not started yet, pauses it from the start). */
    public void pause() {
        pause.pause();
    }

    /** Continues a paused run. */
    public void resume() {
        pause.resume();
    }

    /**
     * The run this launcher is in, once {@link #launch} has made its folder; empty before that.
     * The web UI reads the run id and folder from it while the run is still going.
     */
    public Optional<RunContext> activeRun() {
        return Optional.ofNullable(active);
    }

    /** Whether the run is paused now. */
    public boolean paused() {
        return pause.paused();
    }

    /** Stops the run: before its next step, or at once if paused. Safe from any thread. */
    public void stop() {
        stopRequested = true;
        RunContext run = active;
        if (run != null) {
            run.cancel();
        }
        pause.wake();
    }

    /**
     * Runs the whole launch and returns what it came to. Never throws for a step's failure: a failed
     * step is a result. It throws only when the run folder cannot be opened at all.
     *
     * @throws UncheckedIOException when {@code events.ndjson} cannot be created
     * @throws IllegalStateException when this launcher has already launched a run
     */
    public LaunchResult launch(LaunchConfig config) {
        if (!launched.compareAndSet(false, true)) {
            throw new IllegalStateException("this launcher has already launched a run");
        }
        String runId = newRunId();
        Path folder = ReportPaths.runFolder(paths.resultsRoot(), runId);
        RunEvents events;
        try {
            events = RunEvents.open(folder, clock);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open the run folder " + folder + ": " + e.getMessage(), e);
        }
        RunContext ctx = new RunContext(config, paths, runId, folder, events, pause, clock);
        ctx.supervisor(new Supervisor(processOps));
        if (callerSinks != null) {
            ctx.useCallerSinks(callerSinks);
        }
        active = ctx;
        pause.bind(folder.resolve(PauseGate.PAUSE_FILE));
        if (stopRequested) {
            ctx.cancel();
        }
        boolean cancelled = false;
        try {
            ctx.emit(PlanStart.runStarted(ctx, false));
            PlanStart.resolve(ctx, cases == null ? PlanCases.forRun(paths) : cases);
            PlanStart.runWholePlan(ctx);
            // An opt-in partial run narrows the whole plan; a selection that cannot be made stops the run before step
            // 1 touches anything. No-op unless one was asked for.
            String refused = PlanStart.applyPartialSelection(ctx);
            if (refused.isEmpty()) {
                // The plan dictates the rig: its simulators, devices and FEPs (2026-10-21).
                RunRequirements.apply(ctx);
            }
            if (!ctx.config().suites().equals(config.suites())) {
                ctx.emit(PlanStart.runStarted(ctx, true));
            }
            String stoppedAt = refused.isEmpty() ? prepareRig(ctx, List.of(LaunchStepNames.TESTS)) : refuse(ctx, refused);
            if (stoppedAt.isEmpty()) {
                // After step 4 and only once the rig is up, as the Python: the sidecar creates the Jira execution.
                XraySidecarStart.start(ctx, spawner);
                // Step 5 runs the cases itself when it is wired; unwired, it is unavailable (a failure).
                StepRunner.runLocal(ctx, new TestsRunStep(registries, cases, connector));
            } else {
                XraySidecarStart.notReported(ctx, stoppedAt);
            }
            if (!ctx.sessionFinished()) {
                ctx.emit(runFinished(ctx, Outcome.ERROR, false));
            }
            StepRunner.runLocal(ctx, new GenerateReportStep());
            StepRunner.runLocal(ctx, new CleanupStep());
        } catch (StepRunner.Cancelled e) {
            cancelled = true;
            ctx.emit(runFinished(ctx, Outcome.SKIPPED, true));
        } finally {
            // The Python's release_rig: the printer CAT held during setup first, if a run ended before step 5 took it.
            PrinterHold.release(ctx, false);
            ctx.stopWatching();
            stopSimulators(ctx);
            if (!ctx.keepsPetroPorts()) {
                // After the simulators, so Petro never pushes to a closed port while it runs. A continuous run's
                // sinks, or Launch Simulators', are the caller's: they outlive this run.
                PetroPorts.releaseAll(ctx);
            }
            releaseRig(ctx);
            events.close();
            RunEnded.mark(folder, clock.instant());
        }
        return new LaunchResult(runId, folder, ctx.stepResults(), cancelled);
    }

    /** Steps 1 to 4, in order. Returns the step that ended the run, or "" when all four let it go on. */
    String prepareRig(RunContext ctx, List<String> notReached) {
        List<LaunchStep> stages = List.of(
                new SystemChecksStep(SystemCheckStages.real(processOps, System.getenv())),
                new ConnectionsStep(checks),
                new StartSimulatorsStep(),
                new CommanderConfigurationStep());
        for (int i = 0; i < stages.size(); i++) {
            LaunchStep stage = stages.get(i);
            if (!StepRunner.runLocal(ctx, stage)) {
                for (LaunchStep later : stages.subList(i + 1, stages.size())) {
                    StepRunner.skip(ctx, later.name(), "not reached: " + stage.name() + " failed");
                }
                for (String name : notReached) {
                    StepRunner.skip(ctx, name, "not reached: " + stage.name() + " failed");
                }
                return stage.name();
            }
        }
        return "";
    }

    /** A partial run that cannot be made is refused before step 1; the steps it would have run are skipped with why. */
    private static String refuse(RunContext ctx, String why) {
        for (String name : List.of(LaunchStepNames.SYSTEM_CHECKS, LaunchStepNames.CONNECTIONS,
                LaunchStepNames.SIMULATORS, LaunchStepNames.COMMANDER, LaunchStepNames.TESTS)) {
            StepRunner.skip(ctx, name, PARTIAL_REFUSED + why);
        }
        return LaunchStepNames.SYSTEM_CHECKS;
    }

    /**
     * Stops the simulator processes this run spawned and Cleanup did not stop or keep, and no others (D9): the
     * Python's {@code release_rig}, whatever step ended the run.
     */
    static void stopSimulators(RunContext ctx) {
        List<SimProcess> procs = ctx.simulators();
        if (procs.isEmpty()) {
            return;
        }
        try {
            List<String> stopped = ctx.supervisor().stop(procs, Supervisor.STOP_WAIT);
            ctx.emit(new CatEvent.LogLine("INFO", "cat.sims",
                    "stopped the simulators CAT started: " + String.join(", ", stopped), ""));
        } catch (RuntimeException e) {
            ctx.emit(new CatEvent.LogLine("ERROR", "cat.sims",
                    "stopping the simulators CAT started failed: " + e, ""));
        }
    }

    /** Frees what the steps took: the run lock, last. The rig is this run's until then. */
    static void releaseRig(RunContext ctx) {
        RigLease lease = ctx.runLock();
        if (lease != null) {
            lease.release();
            ctx.runLock(null);
        }
    }

    /**
     * CAT's own {@code RunFinished}, standing in for the session's when step 5 never sent one: the counts so far, as
     * the Python's {@code _synthetic_run_finished} reads them from the orchestrator. Written before step 6, so the
     * summary's tiles show the real failures of a run that ended abnormally.
     */
    private static CatEvent.RunFinished runFinished(RunContext ctx, Outcome outcome, boolean cancelled) {
        Duration elapsed = Duration.between(ctx.startedAt(), ctx.clock().instant());
        double seconds = Math.max(0.0, elapsed.toMillis() / 1000.0);
        return new CatEvent.RunFinished(ctx.runId(), outcome, ctx.testsPassed(), ctx.testsFailed(),
                ctx.testsWarned(), List.of(), cancelled, seconds, 0);
    }

    /** Twelve lowercase hex characters, the Python CAT's {@code uuid4().hex[:12]}. */
    static String newRunId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
