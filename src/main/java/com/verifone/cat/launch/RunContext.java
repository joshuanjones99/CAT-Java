package com.verifone.cat.launch;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.RigLease;

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

    private LoadedRig rig;
    private RigLease runLock;

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
