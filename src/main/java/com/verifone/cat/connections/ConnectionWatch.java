package com.verifone.cat.connections;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Re-probes a fixed set of targets and reports only what changed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python CAT's {@code cat.connections.watch}
 * is the specification. A run is not aborted by a lost connection; it is reported, with a
 * warning when a target that was up stops answering. Aborting is a later decision.
 *
 * <p>Each round is {@link #round()}, which is synchronous and bounded by the probe timeout.
 * {@link #start} schedules rounds on a caller's scheduler, so nothing in this class sleeps or
 * polls. A round that throws (a failing listener, say) is reported to {@code warn} and the
 * schedule goes on: a scheduled task that throws is never run again, and a dead watch would
 * look like a healthy rig.
 */
public final class ConnectionWatch {

    private final ConnectionProbe probe;
    private final List<Target> targets;
    private final Duration timeout;
    private final Consumer<ProbeResult> emit;
    private final Consumer<String> warn;
    private final Map<Target, ProbeState> seen = new HashMap<>();

    /**
     * @param targets the targets to watch, keyed by the record, so two targets that share a
     *                name cannot flap against each other's state
     * @param emit    receives each result whose state changed (and the first result for each target)
     * @param warn    receives a line when a target that was up stops being up
     */
    public ConnectionWatch(ConnectionProbe probe, List<Target> targets, Duration timeout,
            Consumer<ProbeResult> emit, Consumer<String> warn) {
        this.probe = Objects.requireNonNull(probe, "probe");
        this.targets = List.copyOf(targets);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.emit = Objects.requireNonNull(emit, "emit");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** One sweep: probe every target, then report the ones whose state moved. */
    public synchronized void round() {
        List<ProbeResult> results = probe.probeAll(targets, timeout);
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            ProbeResult result = results.get(i);
            ProbeState before = seen.get(target);
            if (before == result.state()) {
                continue;
            }
            seen.put(target, result.state());
            emit.accept(result);
            if (before == ProbeState.UP && result.state() != ProbeState.UP) {
                warn.accept(result.target() + " was up and is now " + result.state().name().toLowerCase()
                        + ": " + result.detail());
            }
        }
    }

    /**
     * Runs a round now and then every {@code interval} on {@code scheduler}, counted from the end
     * of one round to the start of the next. Cancel the returned future to stop the watch.
     */
    public ScheduledFuture<?> start(ScheduledExecutorService scheduler, Duration interval) {
        return scheduler.scheduleWithFixedDelay(this::roundSafely, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void roundSafely() {
        try {
            round();
        } catch (RuntimeException e) {
            warn.accept("connection watch round failed: " + e);
        }
    }
}
