package com.verifone.cat.launch;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.Supervisor;

/**
 * What the seven steps of one run share: the config, the paths, the run's event sink, the pause
 * gate, the stop flag, and the rig and lock the steps take.
 *
 * <p>The Python CAT's {@code cat.launch.context._RunContext}, for the fields this port has.
 * Thread-safe where a step may run on another thread than the launcher: {@link #cancelled()} and
 * the step results are read and written concurrently with a run.
 */
public final class RunContext {

    private final LaunchConfig config;
    private final LaunchPaths paths;
    private final String runId;
    private final Path runFolder;
    private final RunEvents events;
    private final PauseGate pause;
    private final Clock clock;
    private final Instant startedAt;
    private final Map<String, StepResult> results = new ConcurrentHashMap<>();
    private volatile boolean cancelled;
    private volatile boolean sessionFinished;

    private final List<SimProcess> simulators = new CopyOnWriteArrayList<>();
    private volatile Supervisor supervisor;
    private volatile Runnable stopWatching;

    private LoadedRig rig;
    private RigLease runLock;

    private volatile boolean commanderReached;
    private volatile boolean commanderLoginReady = true;
    private volatile String installedBuild = "";
    private volatile String simsBase = "";
    /** Petro's held pump-state ports, port to sink (the Python's {@code petro_sinks}); see {@link PetroPorts}. */
    private final Map<Integer, AutoCloseable> petroSinks = new ConcurrentHashMap<>();
    private volatile PetroPorts.Opener petroPortOpener = PetroPorts.Opener.PORT_SINK;
    private volatile boolean testsStarted;

    RunContext(LaunchConfig config, LaunchPaths paths, String runId, Path runFolder, RunEvents events,
            PauseGate pause, Clock clock) {
        this.config = config;
        this.paths = paths;
        this.runId = runId;
        this.runFolder = runFolder;
        this.events = events;
        this.pause = pause;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    public LaunchConfig config() {
        return config;
    }

    public LaunchPaths paths() {
        return paths;
    }

    /** The run's id: 12 lowercase hex characters, as the Python CAT mints. */
    public String runId() {
        return runId;
    }

    /** The run's folder, {@code <results root>/<run-id>/}. */
    public Path runFolder() {
        return runFolder;
    }

    public PauseGate pause() {
        return pause;
    }

    public Clock clock() {
        return clock;
    }

    /** When the run started, by this run's clock. */
    public Instant startedAt() {
        return startedAt;
    }

    /** Whether the operator stopped the run. Read by the bracket before each step. */
    public boolean cancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
    }

    /**
     * Whether the Tests Run step wrote the run's own {@code RunFinished}, with the counts of the cases it
     * ran. When it did not, the launcher writes CAT's stand-in ({@code error}) after step 5.
     */
    public boolean sessionFinished() {
        return sessionFinished;
    }

    void sessionFinished(boolean finished) {
        sessionFinished = finished;
    }

    /** The rig file a run loaded in System Checks, or {@code null}. */
    public LoadedRig rig() {
        return rig;
    }

    void rig(LoadedRig loaded) {
        rig = loaded;
    }

    /**
     * Whether step 4 (Commander Configuration) started: until it does, this run has not touched the Commander's
     * period, so Cleanup has no day of its own to close. The Python's {@code commander_reached}.
     */
    public boolean commanderReached() {
        return commanderReached;
    }

    void commanderReached(boolean value) {
        commanderReached = value;
    }

    /**
     * Whether the Commander's {@code manager} account is known to hold the lab password: false from the moment a PSI
     * install starts until psi-init has reset it. Cleanup's day close must not log in before then, or each attempt
     * counts towards a lockout. The Python's {@code commander_login_ready}, true by default.
     */
    public boolean commanderLoginReady() {
        return commanderLoginReady;
    }

    void commanderLoginReady(boolean value) {
        commanderLoginReady = value;
    }

    /** The build step 4 identified on the Commander ({@code Solutions1082}), or "". The Python's {@code installed_build}. */
    public String installedBuild() {
        return installedBuild;
    }

    void installedBuild(String value) {
        installedBuild = value == null ? "" : value;
    }

    /**
     * The Commander base this run's simulators face (the Python's {@code sims_base}): the build the run installs when
     * its branch names one ({@code master055.02}), else the Commander's {@code Base_Level} ({@code 055.02.09}); ""
     * when unknown or on a fake-POS run. Set by System Checks; the registry rows and the Java 17 set follow it
     * ({@link com.verifone.cat.sims.base.JavaSets#olderBase}, {@link com.verifone.cat.sims.base.JavaSets#javaForRun}).
     */
    public String simsBase() {
        return simsBase;
    }

    void simsBase(String value) {
        simsBase = value == null ? "" : value;
    }

    /** The run lock this run holds, or {@code null}. Released by the launcher whatever happened. */
    public RigLease runLock() {
        return runLock;
    }

    void runLock(RigLease lease) {
        runLock = lease;
    }

    /**
     * Petro Simulator Suite's pump-state ports this run holds, port to sink, from before step 3 starts Petro until
     * the run ends (the Python's {@code _RunContext.petro_sinks}). PATS's 1072 is lent to step 5's pump listener and
     * to step 4's {@code commander-init}, and held again after; 9706 stays held. Read and changed through
     * {@link PetroPorts}.
     */
    public Map<Integer, AutoCloseable> petroSinks() {
        return petroSinks;
    }

    /** What {@link PetroPorts#hold} opens a port with: a {@code PortSink}, unless a test set a fake. */
    PetroPorts.Opener petroPortOpener() {
        return petroPortOpener;
    }

    /** Test seam: holds Petro's ports with {@code opener} instead of real listening sockets. */
    void petroPortOpener(PetroPorts.Opener opener) {
        petroPortOpener = opener;
    }

    /**
     * Whether step 5 started its first case (the Python's {@code tests_started}): Cleanup leaves the register's day
     * alone when no test ran.
     */
    public boolean testsStarted() {
        return testsStarted;
    }

    void testsStarted(boolean started) {
        testsStarted = started;
    }

    /** The supervisor step 3 drives. Set by the launcher; a PowerShell-backed one if nothing set it. */
    public Supervisor supervisor() {
        Supervisor current = supervisor;
        if (current == null) {
            current = new Supervisor(new PowerShellProcessOps());
            supervisor = current;
        }
        return current;
    }

    void supervisor(Supervisor value) {
        supervisor = value;
    }

    /** The simulator processes this run spawned (not adopted ones), which the run stops at its end. */
    public List<SimProcess> simulators() {
        return List.copyOf(simulators);
    }

    /** Records a process step 3 spawned, so the run stops it at its end. Adopted rows are not recorded. */
    void addSimulator(SimProcess process) {
        if (process.spawned() && !simulators.contains(process)) {
            simulators.add(process);
        }
    }

    /** Registers the stop for the connection watch step 2 started. Called once, by step 2. */
    void watching(Runnable stop) {
        stopWatching = stop;
    }

    /** Ends the connection watch, if one is running. The launcher calls it when the run ends. */
    void stopWatching() {
        Runnable stop = stopWatching;
        stopWatching = null;
        if (stop != null) {
            stop.run();
        }
    }

    /** Writes one event to {@code events.ndjson} and the run log. */
    public void emit(CatEvent event) {
        events.emit(event);
    }

    /** Closes the run's files. The launcher does this itself at the end of a run. */
    void closeEvents() {
        events.close();
    }

    /** Records how a step ended, for the result. The last record of a step name is kept. */
    void recordStep(String name, String state, String detail) {
        results.put(name, new StepResult(name, state, detail));
    }

    /** Every step of the run, in order; a step nothing settled is {@code pending}. */
    List<StepResult> stepResults() {
        List<StepResult> out = new ArrayList<>();
        for (String name : LaunchStepNames.ALL) {
            out.add(results.getOrDefault(name, new StepResult(name, StepResult.PENDING, "")));
        }
        return List.copyOf(out);
    }
}
