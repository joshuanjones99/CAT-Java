package com.verifone.cat.launch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.Outcome;
import com.verifone.cat.report.events.RunEnded;
import com.verifone.cat.rig.RigLease;

/**
 * Drives one run through the seven steps: the Python CAT's {@code cat.launch.run.launch_run} and its
 * {@code prepare_rig} and {@code release_rig}.
 *
 * <p>Order: {@code RunStarted}; steps 1 to 4 (a step that ends the run marks the rest of 1 to 5
 * skipped with {@code not reached: <step> failed}); step 5 (always unavailable here, so the run ends
 * with CAT's own {@code RunFinished}, {@code error}); steps 6 and 7 always. Then the run's locks are
 * released and {@code run.ended} is written, whatever happened.
 *
 * <p>Pause and Stop, as the Python CAT has them. Pause takes effect before the next step. Stop is
 * honoured before the next step or while paused: the run then writes a {@code RunFinished} that is
 * cancelled and skipped, and does not run steps 6 and 7 (the Python CAT does not write a report over
 * a run the operator stopped). The Python CAT cancels an asyncio task; here {@link #stop()} is a flag,
 * so a step that is already running finishes its own work first.
 *
 * <p>A partial run (tags, cases, from a case) is refused before step 1, as the Python CAT refuses a
 * selection it cannot make: the selection reads petroapps' case files, which are not read here yet.
 *
 * <p>One launcher launches one run. Methods are thread-safe for pause and stop; {@link #launch} blocks
 * for the whole run and is called from one thread.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no step here touches a device. Fake-backed tests only.
 */
public final class RunLauncher {

    /** The partial-run refusal, named once so a test and the log say the same thing. */
    static final String PARTIAL_REFUSED = "partial run refused: partial runs are not ported to CAT-Java yet "
            + "(the Python CAT reads petroapps' case files to make the selection)";

    private final LaunchPaths paths;
    private final Clock clock;
    private final PauseGate pause = new PauseGate();
    private final AtomicBoolean launched = new AtomicBoolean();
    private volatile boolean stopRequested;
    private volatile RunContext active;

    /** A launcher on the system UTC clock. */
    public RunLauncher(LaunchPaths paths) {
        this(paths, Clock.systemUTC());
    }

    /** A launcher on {@code clock}, so a test can fix the run's times. */
    public RunLauncher(LaunchPaths paths, Clock clock) {
        this.paths = paths;
        this.clock = clock;
    }

    /** Pauses the run before its next step (or, if it is not started yet, pauses it from the start). */
    public void pause() {
        pause.pause();
    }

    /** Continues a paused run. */
    public void resume() {
        pause.resume();
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
        active = ctx;
        pause.bind(folder.resolve(PauseGate.PAUSE_FILE));
        if (stopRequested) {
            ctx.cancel();
        }
        boolean cancelled = false;
        try {
            ctx.emit(runStarted(ctx));
            String stoppedAt = config.partial() ? refuse(ctx) : prepareRig(ctx, List.of(LaunchStepNames.TESTS));
            if (stoppedAt.isEmpty()) {
                // Step 5 is unavailable, so it ends the run; CAT-Java never starts pytest.
                StepRunner.runLocal(ctx, new TestsRunStep());
            }
            ctx.emit(runFinished(ctx, Outcome.ERROR, false));
            StepRunner.runLocal(ctx, new GenerateReportStep());
            StepRunner.runLocal(ctx, new CleanupStep());
        } catch (StepRunner.Cancelled e) {
            cancelled = true;
            ctx.emit(runFinished(ctx, Outcome.SKIPPED, true));
        } finally {
            releaseRig(ctx);
            events.close();
            RunEnded.mark(folder, clock.instant());
        }
        return new LaunchResult(runId, folder, ctx.stepResults(), cancelled);
    }

    /** Steps 1 to 4, in order. Returns the step that ended the run, or "" when all four let it go on. */
    static String prepareRig(RunContext ctx, List<String> notReached) {
        List<LaunchStep> stages = List.of(
                new SystemChecksStep(),
                new ConnectionsStep(),
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

    /** A partial run is refused before step 1; the steps it would have run are skipped with the reason. */
    private static String refuse(RunContext ctx) {
        for (String name : List.of(LaunchStepNames.SYSTEM_CHECKS, LaunchStepNames.CONNECTIONS,
                LaunchStepNames.SIMULATORS, LaunchStepNames.COMMANDER, LaunchStepNames.TESTS)) {
            StepRunner.skip(ctx, name, PARTIAL_REFUSED);
        }
        return LaunchStepNames.SYSTEM_CHECKS;
    }

    /** Frees what the steps took: the run lock, last. The rig is this run's until then. */
    static void releaseRig(RunContext ctx) {
        RigLease lease = ctx.runLock();
        if (lease != null) {
            lease.release();
            ctx.runLock(null);
        }
    }

    private static CatEvent.RunStarted runStarted(RunContext ctx) {
        LaunchConfig config = ctx.config();
        String rig = config.rig().isEmpty() ? "fake-pos" : config.rig();
        String suite = config.suiteSummary().isEmpty() ? "(no suites)" : config.suiteSummary();
        String cmd = String.join(" ", config.pytestArgv("python"));
        return new CatEvent.RunStarted(
                ctx.runId(),
                rig,
                suite,
                cmd,
                1,
                ctx.startedAt(),
                catVersion(),
                config.upgradeMode().wire(),
                config.testPlans(),
                List.of(),
                config.fakePos(),
                "",
                false);
    }

    /** CAT's own {@code RunFinished}, standing in for the pytest session's: the counts so far are zero. */
    private static CatEvent.RunFinished runFinished(RunContext ctx, Outcome outcome, boolean cancelled) {
        Duration elapsed = Duration.between(ctx.startedAt(), ctx.clock().instant());
        double seconds = Math.max(0.0, elapsed.toMillis() / 1000.0);
        return new CatEvent.RunFinished(ctx.runId(), outcome, 0, 0, 0, List.of(), cancelled, seconds, 0);
    }

    private static String catVersion() {
        Package pkg = RunLauncher.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "unversioned" : version;
    }

    /** Twelve lowercase hex characters, the Python CAT's {@code uuid4().hex[:12]}. */
    static String newRunId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
