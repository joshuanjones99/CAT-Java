package com.verifone.cat.web;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.verifone.cat.sims.Events;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.SimStatus;
import com.verifone.cat.sims.Supervisor;
import com.verifone.cat.sims.ToolsRoot;

/**
 * The Launch Simulators page's state and actions: which rows are ticked, what the supervisor
 * last reported for each, a bounded tail of each simulator's output, and the one background
 * start or stop at a time.
 *
 * <p>Start runs {@link Supervisor#startAll} on {@link #executor}; Stop runs
 * {@link Supervisor#stop} on the simulators this page started, then
 * {@link Supervisor#stopRunning} over every launchable row, whoever started it (HANDOFF.md
 * §1.116, the operator's override of D9). The web thread never waits for either.
 *
 * <p>A row with no supervisor status is looked at with {@link Supervisor#probe}, a read-only TCP
 * connect to its ready port, the same check the supervisor makes before it adopts. The page
 * starts, stops and kills nothing on its own account.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Nothing here has started or stopped a real
 * simulator from this page.
 */
final class SimulatorsService {

    /** The start budget for one row, as the supervisor's end-to-end timeout per entry. */
    static final Duration ROW_START_BUDGET = Duration.ofSeconds(120);

    /** How long the Stop waits for each killed simulator to be gone. */
    static final Duration STOP_BUDGET = Duration.ofSeconds(15);

    /** How long a child CAT started gets to exit after it is asked to. */
    static final Duration CHILD_STOP_BUDGET = Duration.ofSeconds(5);

    /** Interval between checks while a stopped child's port closes. */
    static final Duration POLL = Duration.ofMillis(100);

    /** The lines of each simulator's output kept for the Log panel. */
    static final int LOG_LINES = 300;

    /** The longest line kept; a runaway line from a simulator is cut, not stored whole. */
    static final int LOG_LINE_CHARS = 400;

    /** What a start or stop is doing right now. */
    enum Busy {
        IDLE,
        STARTING,
        STOPPING
    }

    /**
     * A row as the page shows it.
     *
     * @param name the registry row's name
     * @param state the status label, as the Python CAT's
     * @param owner {@code CAT} or {@code external}, or empty when nothing is known
     * @param detail one line for the operator: the bind, and the supervisor's reason
     * @param up true when the simulator is serving
     * @param attention true when it needs the operator (failed, or needs attention)
     */
    record RowView(String name, String state, String owner, String detail, boolean up, boolean attention) {
    }

    private final Supervisor supervisor;
    private final SimContext ctx;
    private final List<SimEntry> rows;
    private final Executor executor;
    private final Map<String, SimStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> logs = new ConcurrentHashMap<>();
    private final Set<String> ticked = ConcurrentHashMap.newKeySet();
    private final List<SimProcess> owned = new CopyOnWriteArrayList<>();
    private final AtomicReference<Busy> busy = new AtomicReference<>(Busy.IDLE);
    private volatile String message = "";

    private final Events events = new Events() {
        @Override
        public void status(SimStatus status) {
            statuses.put(status.name(), status);
        }

        @Override
        public void log(String name, String line) {
            append(name, line);
        }
    };

    /**
     * @param rows the rows the page shows and launches, in order; every one starts ticked
     * @param executor where a start or stop runs; a test passes one that runs inline
     */
    SimulatorsService(Supervisor supervisor, SimContext ctx, List<SimEntry> rows, Executor executor) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.rows = List.copyOf(rows);
        this.executor = Objects.requireNonNull(executor, "executor");
        for (SimEntry row : this.rows) {
            ticked.add(row.name());
        }
    }

    /**
     * The page's real service: the launchable registry rows, PowerShell for the process queries,
     * and one daemon thread for starts and stops, made on first use.
     */
    static SimulatorsService standard() {
        Executor pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cat-web-sims");
            thread.setDaemon(true);
            return thread;
        });
        return new SimulatorsService(new Supervisor(new PowerShellProcessOps()),
                SimContext.of(ToolsRoot.resolve(null), ""), Registry.launchable(false), pool);
    }

    List<SimEntry> rows() {
        return rows;
    }

    /** The row with this name, or empty. Null-safe, so a missing query parameter is simply absent. */
    Optional<SimEntry> row(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return rows.stream().filter(row -> row.name().equals(name)).findFirst();
    }

    boolean isTicked(String name) {
        return ticked.contains(name);
    }

    Busy busy() {
        return busy.get();
    }

    String message() {
        return message;
    }

    /** The supervisor's process-probe notice (its own row, not a simulator), or empty. */
    String probeNotice() {
        SimStatus probe = statuses.get(Supervisor.PROBE_ROW);
        return probe == null ? "" : probe.detail();
    }

    /** Ticks or unticks one row. Unknown names are ignored. */
    void toggle(String name) {
        if (row(name).isEmpty()) {
            return;
        }
        if (!ticked.remove(name)) {
            ticked.add(name);
        }
    }

    /** Ticks every row, or unticks every row. */
    void selectAll(boolean on) {
        if (on) {
            rows.forEach(row -> ticked.add(row.name()));
        } else {
            ticked.clear();
        }
    }

    /**
     * What the Start / Stop control shows: the red Stop once every ticked row is up (the Python
     * page's rule), otherwise Start.
     */
    boolean allTickedUp() {
        List<RowView> views = rows.stream().filter(row -> ticked.contains(row.name()))
                .map(this::view).toList();
        return !views.isEmpty() && views.stream().allMatch(RowView::up);
    }

    /** The row as the page shows it right now. */
    RowView view(SimEntry entry) {
        SimStatus status = statuses.get(entry.name());
        if (status != null) {
            boolean up = status.state() == SimStatus.State.READY || status.state() == SimStatus.State.ADOPTED
                    || status.state() == SimStatus.State.STARTED;
            boolean attention = status.state() == SimStatus.State.FAILED
                    || status.state() == SimStatus.State.NEEDS_ATTENTION;
            if (up && entry.hasTcpReady() && !listening(entry)) {
                return new RowView(entry.name(), "stopped", "", "no longer listening on port "
                        + entry.readyTcp(), false, false);
            }
            return new RowView(entry.name(), status.state().label(), status.owner().label(),
                    join(status.bind(), status.detail()), up, attention);
        }
        if (listening(entry)) {
            return new RowView(entry.name(), "already running", "external",
                    "listening on localhost:" + entry.readyTcp() + " (not started from this page)", true, false);
        }
        return new RowView(entry.name(), "stopped", "",
                entry.hasTcpReady() ? "not listening on port " + entry.readyTcp() : "no ready port to probe",
                false, false);
    }

    /** The bounded tail of one simulator's output, oldest first. */
    List<String> logLines(String name) {
        Deque<String> lines = logs.get(name);
        if (lines == null) {
            return List.of();
        }
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    /**
     * Starts every ticked row on the executor. Returns at once; {@link #message} says what
     * happened, and the status and log reflect the launch as it goes.
     */
    void start() {
        List<SimEntry> chosen = rows.stream().filter(row -> ticked.contains(row.name())).toList();
        if (chosen.isEmpty()) {
            message = "Nothing to launch: tick a simulator.";
            return;
        }
        if (!busy.compareAndSet(Busy.IDLE, Busy.STARTING)) {
            message = busyMessage();
            return;
        }
        message = "Launching " + chosen.size() + " simulator(s); the Log has every line.";
        submit(() -> runStart(chosen));
    }

    /** The red Stop: every simulator, whoever started it. Returns at once, like {@link #start}. */
    void stop() {
        if (!busy.compareAndSet(Busy.IDLE, Busy.STOPPING)) {
            message = busyMessage();
            return;
        }
        message = "Stopping every simulator; the Log has every line.";
        submit(this::runStop);
    }

    private void submit(Runnable job) {
        try {
            executor.execute(job);
        } catch (RuntimeException e) {
            busy.set(Busy.IDLE);
            message = "Could not start the job: " + e.getMessage();
        }
    }

    private void runStart(List<SimEntry> chosen) {
        try {
            List<SimProcess> procs = supervisor.startAll(chosen, ctx, events,
                    ROW_START_BUDGET.multipliedBy(chosen.size()), null);
            procs.stream().filter(SimProcess::spawned).forEach(owned::add);
            List<String> notUp = chosen.stream().map(row -> view(row)).filter(v -> !v.up())
                    .map(RowView::name).toList();
            message = notUp.isEmpty() ? "Launch finished: all " + chosen.size() + " up."
                    : "Launch finished. Not up: " + String.join(", ", notUp) + ".";
        } catch (RuntimeException e) {
            message = "Launch failed: " + e.getMessage();
        } finally {
            busy.set(Busy.IDLE);
        }
    }

    private void runStop() {
        try {
            supervisor.stop(List.copyOf(owned), CHILD_STOP_BUDGET);
            for (SimProcess proc : owned) {
                if (proc.entry().hasTcpReady()) {
                    awaitPortClosed(proc.entry(), CHILD_STOP_BUDGET);
                }
            }
            owned.clear();
            Map<String, Supervisor.StopRow> outcome = supervisor.stopRunning(
                    rows, ctx, STOP_BUDGET);
            List<String> failed = new ArrayList<>();
            for (Map.Entry<String, Supervisor.StopRow> entry : outcome.entrySet()) {
                Supervisor.StopRow row = entry.getValue();
                if (row == null || row.outcome() == Supervisor.StopOutcome.STOPPED
                        || row.outcome() == Supervisor.StopOutcome.ABSENT) {
                    statuses.remove(entry.getKey());
                } else {
                    failed.add(entry.getKey());
                    statuses.put(entry.getKey(), new SimStatus(entry.getKey(), SimStatus.State.FAILED,
                            SimStatus.Owner.EXTERNAL, "", row.outcome().name().toLowerCase(Locale.ROOT)
                            + ": " + row.detail()));
                }
            }
            message = failed.isEmpty() ? "Stop finished: nothing left running."
                    : "Stop finished, but not all stopped: " + String.join(", ", failed) + ".";
        } catch (RuntimeException e) {
            message = "Stop failed: " + e.getMessage();
        } finally {
            busy.set(Busy.IDLE);
        }
    }

    private String busyMessage() {
        return busy.get() == Busy.STOPPING ? "A stop is still running; wait for it."
                : "A launch is still running; wait for it.";
    }

    private void append(String name, String line) {
        Deque<String> lines = logs.computeIfAbsent(name, key -> new ArrayDeque<>());
        String kept = line.length() > LOG_LINE_CHARS ? line.substring(0, LOG_LINE_CHARS) + "…" : line;
        synchronized (lines) {
            lines.addLast(kept);
            while (lines.size() > LOG_LINES) {
                lines.removeFirst();
            }
        }
    }

    /**
     * Waits, bounded, for a port a stopped child held to close. A process is gone a moment before
     * its socket is; without this the panel would show a just-stopped simulator as still up.
     */
    private void awaitPortClosed(SimEntry entry, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (listening(entry) && System.nanoTime() - deadline < 0) {
            try {
                new CountDownLatch(1).await(POLL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** True if the row's ready port is answering, by the supervisor's read-only probe. */
    private boolean listening(SimEntry entry) {
        return supervisor.probe(entry, ctx).listening();
    }

    private static String join(String bind, String detail) {
        if (bind.isEmpty()) {
            return detail;
        }
        return detail.isEmpty() ? bind : bind + " — " + detail;
    }
}
