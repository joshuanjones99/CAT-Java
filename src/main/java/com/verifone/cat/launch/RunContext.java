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
    private volatile boolean testsStarted;
    private volatile Long epsLogMark;
    private volatile CommanderOptions commanderOptions = CommanderOptions.NONE;

    /**
     * What step 4 reads that {@link LaunchConfig} does not carry yet: the Config tab's Secondary FEPs (the Python's
     * {@code secondary_feps}), its "capture a Commander snapshot" switch ({@code capture_snapshot}), and the chosen
     * Commander build's label ({@code commander_branch + commander_build}) the capture is keyed by. Until a caller sets
     * them, none of the three is asked for: {@code --fep none}, no capture beyond a fresh install's, no label.
     *
     * @param secondaryFeps   the FEPs to switch on; empty means {@code none}
     * @param captureSnapshot capture the Commander snapshot after step 4 even without a fresh install
     * @param buildLabel      e.g. {@code Solutions134}, or ""
     */
    public record CommanderOptions(List<String> secondaryFeps, boolean captureSnapshot, String buildLabel) {

        /** Nothing asked for. */
        public static final CommanderOptions NONE = new CommanderOptions(List.of(), false, "");

        public CommanderOptions {
            secondaryFeps = List.copyOf(secondaryFeps == null ? List.of() : secondaryFeps);
            buildLabel = buildLabel == null ? "" : buildLabel.strip();
        }
    }

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

    /** The run lock this run holds, or {@code null}. Released by the launcher whatever happened. */
    public RigLease runLock() {
        return runLock;
    }

    void runLock(RigLease lease) {
        runLock = lease;
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

    /**
     * Replaces the CAT-started processes {@code old} with {@code fresh}, as the Python's restart does
     * ({@code ctx.sim_procs = [p for p in ctx.sim_procs if p not in owned] + [proc]}).
     */
    void replaceSimulators(List<SimProcess> old, SimProcess fresh) {
        simulators.removeAll(old);
        if (fresh != null) {
            addSimulator(fresh);
        }
    }

    /** Forgets every process this run started: Cleanup has stopped them. */
    void clearSimulators() {
        simulators.clear();
    }

    /** Registers the stop for the connection watch step 2 started. Called once, by step 2. */
    void watching(Runnable stop) {
        stopWatching = stop;
    }

    /**
     * Ends the connection watch, if one is running. Cleanup calls it, and the launcher again when the run ends.
     *
     * @return whether a watch was running
     */
    boolean stopWatching() {
        Runnable stop = stopWatching;
        stopWatching = null;
        if (stop != null) {
            stop.run();
            return true;
        }
        return false;
    }

    /**
     * Whether the run reached step 4 (the Python's {@code commander_reached}): the day close is skipped when nothing
     * this run did reached the Commander. Set at the top of step 4.
     */
    public boolean commanderReached() {
        return commanderReached;
    }

    void commanderReached(boolean value) {
        commanderReached = value;
    }

    /**
     * Whether a {@code manager} login with the lab password is safe (the Python's {@code commander_login_ready}, true
     * by default): false from the start of a PSI install until {@code psi-init} has reset the password, so the day
     * close does not count towards a lockout.
     */
    public boolean commanderLoginReady() {
        return commanderLoginReady;
    }

    void commanderLoginReady(boolean value) {
        commanderLoginReady = value;
    }

    /** Whether step 5 began (the Python's {@code tests_started}). Set by the Tests Run step. */
    public boolean testsStarted() {
        return testsStarted;
    }

    void testsStarted(boolean value) {
        testsStarted = value;
    }

    /**
     * The Petro Simulator Suite log's size when step 3 saw it ready (the Python's {@code eps_log_mark}), or null when
     * step 3 did not mark it. Step 4's pinpad link reads the EPS's requests after it.
     */
    public Long epsLogMark() {
        return epsLogMark;
    }

    void epsLogMark(Long value) {
        epsLogMark = value;
    }

    /** What step 4 reads beyond the config; {@link CommanderOptions#NONE} until a caller sets it. */
    public CommanderOptions commanderOptions() {
        return commanderOptions;
    }

    void commanderOptions(CommanderOptions value) {
        commanderOptions = value == null ? CommanderOptions.NONE : value;
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
