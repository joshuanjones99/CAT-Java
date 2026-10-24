package com.verifone.cat.runservice;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchPaths;
import com.verifone.cat.launch.LaunchResult;
import com.verifone.cat.launch.RunLauncher;

/**
 * The one current run, for the web UI: the Python CAT's {@code CatService} for the Run tab.
 *
 * <p>One run at a time. Start is refused while a run is in progress, and Preflight while a run is.
 * The run itself is launched on a daemon thread, so a request returns at once; the Overview and the
 * Log read its folder on each poll. Pause, Stop and New run act on the current run. A refused action
 * leaves its reason in {@link View#notice()}, which the page shows.
 *
 * <p>The schedule and the continuous run ({@link RunSchedule}) sit on top of this: a scheduled run is
 * started by {@link #tick}, which {@link #standard} calls once a second on a daemon
 * {@link ScheduledExecutorService}. A continuous pass starts the next pass on the run's own thread
 * when the last one ends. Nothing is persisted: a restart clears the schedule, as the Python CAT's does.
 *
 * <p>Holds no HTML and no form: {@link RunStart} makes the config, and the routes draw the page.
 */
public final class RunService {

    /** The reason an action on no run is refused. */
    public static final String NOT_RUNNING = "No run in progress.";

    /** The reason Start, and New run, are refused while a run is going. */
    public static final String BUSY = "A run is in progress: stop it first.";

    private final String rig;
    private final Supplier<RunEngine> engines;
    private final Supplier<String> preflight;
    private final Clock clock;
    private final RunSchedule schedule;

    private RunEngine engine;
    private Path folder;
    private RunProgress progress;
    private boolean busy;
    private boolean preflighting;
    private String error = "";
    private String notice = "";
    private String preflightText = "";
    private String readProblem = "";

    /**
     * @param rig       the rig the run is for, as the web UI was started with it; may be empty
     * @param engines   makes one engine per Start run
     * @param preflight runs the check-only preflight and returns its rendered report
     * @param clock     the Overview's Run Time clock
     */
    public RunService(String rig, Supplier<RunEngine> engines, Supplier<String> preflight, Clock clock) {
        this.rig = rig == null ? "" : rig.strip();
        this.engines = engines;
        this.preflight = preflight;
        this.clock = clock;
        this.schedule = new RunSchedule(() -> LocalDateTime.now(this.clock), this::startPass);
    }

    /**
     * The service the production app uses: a {@link RunLauncher} per run, the rig's check-only preflight,
     * and the schedule ticked once a second on a daemon thread.
     */
    public static RunService standard(String rig, Map<String, String> env) {
        LaunchPaths paths = LaunchPaths.fromEnv(env);
        String name = rig == null ? "" : rig;
        RunService service = new RunService(name,
                () -> new LaunchEngine(new RunLauncher(paths)),
                () -> RigPreflight.checkOnly(name, env),
                Clock.system(ZoneId.systemDefault()));
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cat-schedule");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(service::tickSafely, 1, 1, TimeUnit.SECONDS);
        return service;
    }

    /** The rig Start run launches on. */
    public String rig() {
        return rig;
    }

    /** Starts {@code config} on a background thread, unless a run is already going. */
    public synchronized void start(LaunchConfig config) {
        startPass(config, null);
    }

    /**
     * Starts {@code config} unless a run is going or preflight is. {@code done} runs on the run's thread
     * once the run has ended, still holding this service's lock and with {@code busy} already clear.
     *
     * @return whether the run was started
     */
    private synchronized boolean startPass(LaunchConfig config, Consumer<RunSchedule.PassEnd> done) {
        if (busy) {
            notice = BUSY;
            return false;
        }
        if (preflighting) {
            notice = "Preflight is running: wait for it to finish.";
            return false;
        }
        RunEngine next = engines.get();
        engine = next;
        folder = null;
        progress = null;
        error = "";
        readProblem = "";
        notice = "";
        busy = true;
        Thread worker = new Thread(() -> launch(next, config, done), "cat-run");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void launch(RunEngine next, LaunchConfig config, Consumer<RunSchedule.PassEnd> done) {
        RunSchedule.PassEnd end = RunSchedule.PassEnd.NONE;
        try {
            LaunchResult result = next.launch(config);
            if (!result.cancelled()) {
                end = new RunSchedule.PassEnd(result.runId(), testsRun());
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        } finally {
            synchronized (this) {
                busy = false;
                if (done != null) {
                    done.accept(end);
                }
            }
        }
    }

    /** The verdicts the run's folder holds so far: a pass with none ran no test cases. */
    private synchronized int testsRun() {
        Path where = folderNow();
        if (where == null) {
            return 0;
        }
        try {
            if (progress == null) {
                progress = new RunProgress(where);
            }
            progress.refresh();
        } catch (IOException e) {
            return 0;
        }
        RunProgress.Snapshot snapshot = progress.snapshot();
        return snapshot.passed() + snapshot.failed() + snapshot.warned();
    }

    // ------------------------------------------------------------------ schedule and continuous

    /** Schedule run: one run per time in {@code raw}, against the Config tab's choices as {@code plan}. */
    public synchronized void schedule(String raw, RunStart.Plan plan) {
        if (!plan.startable()) {
            schedule.reject(plan.refusal());
            return;
        }
        schedule.schedule(raw, plan.config());
    }

    /** Clear schedule: drops every pending run. */
    public synchronized void clearSchedule() {
        schedule.clear();
    }

    /** Drops one pending run; an unknown id is ignored. */
    public synchronized void cancelScheduled(int id) {
        schedule.cancel(id);
    }

    /**
     * Continuous run: on, or, pressed again, off after the pass in flight. Refused while a run goes
     * on its own, as the Python CAT refuses it.
     */
    public synchronized void toggleContinuous(RunStart.Plan plan) {
        if (schedule.continuousOn()) {
            String message = schedule.finishContinuous(busy);
            if (!message.isEmpty()) {
                notice = message;
            }
            return;
        }
        if (busy || preflighting) {
            notice = BUSY;
            return;
        }
        if (!plan.startable()) {
            notice = plan.refusal();
            return;
        }
        schedule.startContinuous(plan.config());
    }

    /** The schedule block of the Overview, at the current time. */
    public synchronized RunSchedule.View scheduleView() {
        return schedule.view();
    }

    /**
     * One scheduler tick: while nothing holds the rig, start the continuous pass whose hold has passed,
     * or the earliest due scheduled run. Public for tests; {@link #standard} calls it once a second.
     */
    public synchronized void tick() {
        if (busy || preflighting) {
            return;
        }
        schedule.tick();
    }

    /** {@link #tick}, but an exception is shown rather than cancelling the executor's later ticks. */
    void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            synchronized (this) {
                notice = "Schedule: " + e.getMessage();
            }
        }
    }

    /** Records why a Start was refused, so the page can say so. */
    public synchronized void refuse(String why) {
        notice = why;
    }

    /** Pause if running, Continue if paused. */
    public synchronized void togglePause() {
        if (!busy || engine == null) {
            notice = NOT_RUNNING;
            return;
        }
        if (engine.paused()) {
            engine.resume();
        } else {
            engine.pause();
        }
        notice = "";
    }

    /** Stops the run before its next step, or at once if it is paused. */
    public synchronized void stop() {
        if (!busy || engine == null) {
            notice = NOT_RUNNING;
            return;
        }
        engine.stop();
        notice = "Stopping: the run ends before its next step.";
    }

    /** Clears the finished run from the page; the run stays on disk and in Results. */
    public synchronized void newRun() {
        if (busy) {
            notice = BUSY;
            return;
        }
        engine = null;
        folder = null;
        progress = null;
        error = "";
        readProblem = "";
        notice = "";
    }

    /** Runs the check-only preflight. Not held under the lock: it can take a while. */
    public void preflight() {
        synchronized (this) {
            if (busy) {
                notice = BUSY;
                return;
            }
            if (preflighting) {
                notice = "Preflight is already running.";
                return;
            }
            preflighting = true;
            notice = "";
        }
        String text;
        try {
            text = preflight.get();
        } catch (RuntimeException e) {
            text = "Preflight could not run: " + e.getMessage();
        }
        synchronized (this) {
            preflighting = false;
            preflightText = text == null ? "" : text;
        }
    }

    /** The current run's log lines, the last {@link RunLogTail#LINES}; empty when there is no run folder yet. */
    public List<String> logLines() throws IOException {
        Path where;
        synchronized (this) {
            where = folderNow();
        }
        return where == null ? List.of() : RunLogTail.tail(where, RunLogTail.LINES);
    }

    /** The current run's read of its folder, with the Overview's extras. */
    public synchronized View view() {
        Path where = folderNow();
        RunProgress.Snapshot snapshot = null;
        if (where != null) {
            try {
                if (progress == null) {
                    progress = new RunProgress(where);
                }
                progress.refresh();
                readProblem = "";
            } catch (IOException e) {
                readProblem = "The run's events could not be read yet: " + e.getMessage();
            }
            snapshot = progress == null ? null : progress.snapshot();
        }
        boolean started = engine != null;
        boolean paused = started && busy && engine.paused();
        Instant now = clock.instant();
        return new View(
                started,
                busy,
                paused,
                started && !busy,
                statusWord(started, snapshot, paused),
                snapshot,
                snapshot == null ? 0 : snapshot.seconds(now),
                error,
                notice,
                preflightText,
                preflighting,
                readProblem);
    }

    /** The folder, once the engine has made it. Called under the lock. */
    private Path folderNow() {
        if (folder == null && engine != null) {
            folder = engine.runFolder().orElse(null);
        }
        return folder;
    }

    private String statusWord(boolean started, RunProgress.Snapshot snapshot, boolean paused) {
        if (!started) {
            return "not started";
        }
        if (busy) {
            if (snapshot == null || !snapshot.started()) {
                return "starting";
            }
            return paused ? "Paused" : "running";
        }
        if (snapshot != null && snapshot.finished()) {
            if (snapshot.cancelled()) {
                return "cancelled";
            }
            return "passed".equals(snapshot.outcome()) ? "complete" : "failed";
        }
        return error.isEmpty() ? "ended" : "failed";
    }

    /**
     * Everything the Overview draws, from one read.
     *
     * @param started      whether a run has been started from this page and not cleared
     * @param busy         whether the run thread is still going
     * @param paused       whether the run is paused
     * @param ended        whether a started run has finished
     * @param status       the word for the Status counter
     * @param snapshot     the folded events; {@code null} until the run has made its folder
     * @param seconds      the Run Time, in seconds
     * @param error        why the launch threw, if it did; empty otherwise
     * @param notice       the reason the last action was refused, or a note; empty when none
     * @param preflight    the last preflight report, rendered; empty before one ran
     * @param preflighting whether a preflight is running now
     * @param readProblem  why the events could not be read this time; empty when they could
     */
    public record View(
            boolean started,
            boolean busy,
            boolean paused,
            boolean ended,
            String status,
            RunProgress.Snapshot snapshot,
            double seconds,
            String error,
            String notice,
            String preflight,
            boolean preflighting,
            String readProblem) {
    }
}
