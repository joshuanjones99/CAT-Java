package com.verifone.cat.runservice;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.launch.LastConfig;
import com.verifone.cat.launch.LaunchConfig;
import com.verifone.cat.launch.LaunchDefaults;
import com.verifone.cat.launch.LaunchPaths;
import com.verifone.cat.launch.LaunchResult;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.setup.SetupAudit;
import com.verifone.cat.setup.SetupEnv;
import com.verifone.cat.setup.SetupItems;

/**
 * The one current run, for the web UI: the Python CAT's {@code CatService} for the Run tab.
 *
 * <p>One thing at a time: a run or a preflight. Start is refused while either is going, and so is Preflight. Both
 * go on a daemon thread, so a request returns at once; the Overview and the Log read the run's folder on each poll
 * (a {@link RunProgress} folds its events into a {@link RunState}). Pause, Stop and New run act on the current run;
 * Stop also stops a preflight. A refused action leaves its reason in {@link View#notice()}, which the page shows.
 *
 * <p>Before a run starts, as the Python's {@code _startable_config} and {@code _signed_in_launch}: a recording in
 * progress refuses it, so does a sign-in missing on the Setup page ({@code credential_gaps}), and so does a download
 * of Java 17 or the simulators still staging. A Start from the Config tab whose ticked plan has no case list yet
 * fetches the plan's cases from Jira first and starts when they arrive; the production service always refreshes the
 * plans' cases and requirements first (Xray first, writing the cache), as {@code launch} does.
 *
 * <p>The schedule and the continuous run ({@link RunSchedule}) sit on top of this: a scheduled run is started by
 * {@link #tick}, which {@link #standard} calls once a second on a daemon {@link ScheduledExecutorService}. A
 * continuous pass starts the next pass on the run's own thread when the last one ends. Nothing is persisted.
 *
 * <p>Preflight is check only here. Preflight with repair (it drives the POS) waits for the rig owner's go-ahead.
 *
 * <p>Holds no HTML and no form: {@link RunStart} makes the config, and the routes draw the page.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the production engine drives the rig a run is started on.
 */
public final class RunService {

    /** The reason an action on no run is refused. */
    public static final String NOT_RUNNING = "No run in progress.";

    /** The reason Start, and New run, are refused while a run is going. */
    public static final String BUSY = "A run is in progress: stop it first.";

    /** The reason Start is refused while a preflight is going. */
    public static final String PREFLIGHTING = "Preflight is running: wait for it to finish, or Stop it.";

    /** The reason Start and Preflight are refused while a recording is going. */
    public static final String RECORDING = "A recording is in progress: stop it on the Authoring page.";

    /** The reason Start is refused while Java 17 or the simulators are still downloading. */
    public static final String STAGING = "Run not started: Java 17 / the simulators are still downloading. "
            + "Start it again when CAT says they are staged.";

    private final String rig;
    private final Supplier<RunEngine> engines;
    private final Supplier<String> preflight;
    private final Clock clock;
    private final RunSchedule schedule;

    private Supplier<Map<String, String>> credentialGaps = Map::of;
    private BooleanSupplier staging = () -> false;
    private BooleanSupplier recording = () -> false;
    private Consumer<List<String>> caseFetch;
    private RunStart.Context startContext;

    private RunEngine engine;
    private Path folder;
    private RunProgress progress;
    private boolean busy;
    private boolean preflighting;
    private Thread preflightThread;
    private String fetching = "";
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
        this.startContext = RunStart.Context.of(this.rig);
    }

    /**
     * The service the production app uses: a launcher per run with step 5 wired and a catalogue that reads Xray and
     * Confluence ({@link LaunchDefaults}), the sign-in gate over the Setup page's credential items, the rig's
     * check-only preflight, and the schedule ticked once a second on a daemon thread.
     */
    public static RunService standard(String rig, Map<String, String> env) {
        String home = System.getProperty("user.home");
        LaunchPaths paths = LaunchDefaults.withNetwork(LaunchPaths.fromEnv(env), Path.of(home));
        String name = rig == null ? "" : rig;
        RunService service = new RunService(name,
                () -> new LaunchEngine(LaunchDefaults.wiredLauncher(paths)),
                () -> RigPreflight.checkOnly(name, env),
                Clock.system(ZoneId.systemDefault()));
        service.gates(RunService::setupCredentialGaps, () -> false, () -> false);
        service.caseFetch(plans -> {
            for (String plan : plans) {
                paths.catalogue().cases(plan, PlanCatalogue.Mode.REFRESH);
            }
            paths.catalogue().requirements(PlanCatalogue.Mode.REFRESH);
        });
        service.startContext(new RunStart.Context(name, () -> LastConfig.fromHome(env, home), env,
                paths.catalogue(), paths.rigsDir(), null));
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cat-schedule");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(service::tickSafely, 1, 1, TimeUnit.SECONDS);
        return service;
    }

    /** The Setup page's sign-ins that are not in place, by name; local reads only, fresh each call. */
    static Map<String, String> setupCredentialGaps() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        SetupAudit.credentialGaps(SetupItems.standard(), SetupEnv.system())
                .forEach((item, result) -> out.put(item, result.detail()));
        return out;
    }

    /**
     * The start refusals' inputs.
     *
     * @param credentialGaps the missing sign-ins by name (empty when all are in place)
     * @param staging        whether Java 17 or the simulators are still downloading
     * @param recording      whether a recording is in progress
     */
    public synchronized RunService gates(Supplier<Map<String, String>> credentialGaps, BooleanSupplier staging,
            BooleanSupplier recording) {
        this.credentialGaps = credentialGaps == null ? Map::of : credentialGaps;
        this.staging = staging == null ? () -> false : staging;
        this.recording = recording == null ? () -> false : recording;
        return this;
    }

    /** What fetches the plans' cases (and requirements) from Jira before a Start from the Config tab; null for none. */
    public synchronized RunService caseFetch(Consumer<List<String>> fetch) {
        this.caseFetch = fetch;
        return this;
    }

    /** Where Start run's defaults come from ({@link RunStart.Context}). */
    public synchronized RunService startContext(RunStart.Context context) {
        this.startContext = context == null ? RunStart.Context.of(rig) : context;
        return this;
    }

    /** The context {@link RunStart} reads Start run's defaults from. */
    public synchronized RunStart.Context startContext() {
        return startContext;
    }

    /** The rig Start run launches on. */
    public String rig() {
        return rig;
    }

    // ------------------------------------------------------------------ starting

    /** Starts {@code config} on a background thread, unless something refuses it. */
    public synchronized void start(LaunchConfig config) {
        startPass(config, null);
    }

    /**
     * Start run from the Config tab: refuses as the Python does, else starts on a background thread that first
     * fetches the plans' cases (when a fetch is wired) and then re-reads the choices. {@code plans} are the ticked
     * plans, fetched even when the cached choices cannot start a run yet.
     */
    public void startFromForm(Supplier<RunStart.Plan> planner, List<String> plans) {
        RunStart.Plan plan = planner.get();
        synchronized (this) {
            String refusal = refusal();
            if (!refusal.isEmpty()) {
                notice = refusal;
                return;
            }
            boolean fetch = caseFetch != null && !plans.isEmpty();
            if (!plan.startable() && !(fetch && plan.needsFetch())) {
                notice = plan.refusal();
                return;
            }
            String gate = signInGate();
            if (!gate.isEmpty()) {
                notice = gate;
                return;
            }
            Consumer<List<String>> fetcher = fetch ? caseFetch : null;
            List<String> keys = List.copyOf(plans);
            begin(() -> {
                if (fetcher != null) {
                    synchronized (this) {
                        fetching = "Fetching the case list for " + String.join(", ", keys)
                                + " from Jira; the run starts when it arrives.";
                    }
                    try {
                        fetcher.accept(keys);
                    } finally {
                        synchronized (this) {
                            fetching = "";
                        }
                    }
                }
                RunStart.Plan fresh = planner.get();
                if (!fresh.startable()) {
                    synchronized (this) {
                        notice = fresh.refusal();
                    }
                    return null;
                }
                return fresh.config();
            }, null);
        }
    }

    /** Why nothing may start now (a run, a preflight or a recording), or "". Called under the lock. */
    private String refusal() {
        if (busy) {
            return BUSY;
        }
        if (preflighting) {
            return PREFLIGHTING;
        }
        if (recording.getAsBoolean()) {
            return RECORDING;
        }
        return "";
    }

    /** The sign-in and staging gate ({@code _signed_in_launch}), or "". Called under the lock. */
    private String signInGate() {
        Map<String, String> gaps;
        try {
            gaps = credentialGaps.get();
        } catch (RuntimeException e) {
            gaps = Map.of("the credential check (" + e.getMessage() + ")", "");
        }
        if (!gaps.isEmpty()) {
            return "Run not started: sign in first on the Setup page. Missing: " + String.join(", ", gaps.keySet());
        }
        if (staging.getAsBoolean()) {
            return STAGING;
        }
        return "";
    }

    /**
     * Starts {@code config} unless something refuses it. {@code done} runs on the run's thread once the run has
     * ended, still holding this service's lock and with {@code busy} already clear.
     *
     * @return whether the run was started
     */
    private synchronized boolean startPass(LaunchConfig config, Consumer<RunSchedule.PassEnd> done) {
        String refusal = refusal();
        if (refusal.isEmpty()) {
            refusal = signInGate();
        }
        if (!refusal.isEmpty()) {
            notice = refusal;
            return false;
        }
        begin(() -> config, done);
        return true;
    }

    /** Makes the engine and starts the run thread. Called under the lock, with nothing refusing. */
    private void begin(Supplier<LaunchConfig> config, Consumer<RunSchedule.PassEnd> done) {
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
    }

    private void launch(RunEngine next, Supplier<LaunchConfig> config, Consumer<RunSchedule.PassEnd> done) {
        RunSchedule.PassEnd end = RunSchedule.PassEnd.NONE;
        boolean launched = false;
        try {
            LaunchConfig chosen = config.get();
            if (chosen != null) {
                launched = true;
                LaunchResult result = next.launch(chosen);
                if (!result.cancelled()) {
                    end = new RunSchedule.PassEnd(result.runId(), testsRun());
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                launched = true;
            }
        } finally {
            synchronized (this) {
                busy = false;
                if (!launched) {
                    engine = null; // never started: back to the pre-run page, the reason in the notice
                }
                if (done != null) {
                    done.accept(end);
                }
            }
        }
    }

    /** The verdicts the run's folder holds so far: a pass with none ran no test cases. */
    private synchronized int testsRun() {
        RunState state = refreshed();
        if (state == null) {
            return 0;
        }
        Map<String, Integer> counts = RunView.verdictCounts(state);
        return counts.get("passed") + counts.get("failed") + counts.get("warned");
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
     * Continuous run: on, or, pressed again, off after the pass in flight. Refused while a run goes on its own, as
     * the Python CAT refuses it.
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
     * One scheduler tick: while nothing holds the rig, start the continuous pass whose hold has passed, or the
     * earliest due scheduled run. Public for tests; {@link #standard} calls it once a second.
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

    // ------------------------------------------------------------------ pause, stop, new run

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

    /** Stops the run before its next step, or at once if it is paused; or stops the preflight in flight. */
    public synchronized void stop() {
        if (preflighting && preflightThread != null) {
            preflightThread.interrupt();
            preflighting = false;
            preflightThread = null;
            preflightText = "Preflight stopped before it finished.";
            notice = "Stopping preflight.";
            return;
        }
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

    // ------------------------------------------------------------------ preflight

    /** Runs the check-only preflight on a background thread; Stop abandons it. */
    public synchronized void preflight() {
        if (busy) {
            notice = BUSY;
            return;
        }
        if (preflighting) {
            notice = "Preflight is already running.";
            return;
        }
        if (recording.getAsBoolean()) {
            notice = RECORDING;
            return;
        }
        preflighting = true;
        notice = "";
        Thread worker = new Thread(this::runPreflight, "cat-preflight");
        worker.setDaemon(true);
        preflightThread = worker;
        worker.start();
    }

    private void runPreflight() {
        String text;
        try {
            text = preflight.get();
        } catch (RuntimeException e) {
            text = "Preflight could not run: " + e.getMessage();
        }
        synchronized (this) {
            if (preflightThread != Thread.currentThread()) {
                return; // stopped: its report is not shown
            }
            preflighting = false;
            preflightThread = null;
            preflightText = text == null ? "" : text;
        }
    }

    // ------------------------------------------------------------------ reading

    /** The current run's log lines, the last {@link RunLogTail#LINES}; empty when there is no run folder yet. */
    public List<String> logLines() throws IOException {
        Path where;
        synchronized (this) {
            where = folderNow();
        }
        return where == null ? List.of() : RunLogTail.tail(where, RunLogTail.LINES);
    }

    /** The current run's state as its events say it now; the empty state before a run has made its folder. */
    public synchronized RunState state() {
        RunState state = refreshed();
        return state == null ? RunState.empty() : state;
    }

    /** Folds what the run has written since the last read; null with no folder yet. Called under the lock. */
    private RunState refreshed() {
        Path where = folderNow();
        if (where == null) {
            return null;
        }
        try {
            if (progress == null) {
                progress = new RunProgress(where);
            }
            progress.refresh();
            readProblem = "";
        } catch (IOException e) {
            readProblem = "The run's events could not be read yet: " + e.getMessage();
        }
        return progress == null ? null : progress.state();
    }

    /** The current run's read of its folder, with the Overview's extras. */
    public synchronized View view() {
        RunState state = refreshed();
        RunProgress.Snapshot snapshot = state == null ? null : new RunProgress.Snapshot(state);
        boolean started = engine != null;
        boolean paused = started && busy && engine.paused();
        Instant now = clock.instant();
        List<String> notices = new ArrayList<>();
        if (!fetching.isEmpty()) {
            notices.add(fetching);
        }
        return new View(
                started,
                busy,
                paused,
                started && !busy,
                statusWord(started, snapshot, paused),
                snapshot,
                snapshot == null ? 0 : snapshot.seconds(now),
                error,
                notice.isEmpty() && !notices.isEmpty() ? notices.get(0) : notice,
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

        /** The run's state, or the empty state before it has one. */
        public RunState state() {
            return snapshot == null ? RunState.empty() : snapshot.state();
        }
    }
}
