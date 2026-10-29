package com.verifone.cat.sims;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Starts the PATS simulators that are not already running, and stops only what it started.
 *
 * <p>D9 is the whole contract: <b>adopt if up, start if not, stop only what we started.</b> A
 * simulator already listening is adopted and left alone: its GUI may hold state a tester set up
 * by hand. A row with a {@code readyTcp} port whose process runs but is not listening gets the
 * rest of its start budget to bind, and is a failed row if it does not; it is not stopped and
 * not spawned over, unless the row is {@code replaceStale} (Petro), in which case the stale
 * process is killed and CAT starts its own. {@link #stop} terminates only the {@link Process}
 * objects this class created. {@link #stopRunning} is the one path that kills a process CAT did
 * not start: the Simulators tab's Stop, the operator's explicit "close them all", reached by a
 * run only for a stale {@code replaceStale} process.
 *
 * <p>A row that needs its Swing buttons pressed ({@code click:}) is launched, its main window found by
 * PID within the start budget, its buttons pressed through the Java Access Bridge and the window
 * minimised ({@link Clicker}, the Python's {@code _do_clicks}); a {@code ready: clicked} row is then
 * {@code ready} with the clicked buttons as its detail. A click that fails leaves the process running
 * (CAT's own, so {@link #stop} still stops it) and the row {@code needs attention}
 * ({@link SimStatus#awaitingButtons}), never {@code ready}. When the controller cannot press buttons
 * at all ({@link Clicker#unavailable}: no bridge DLL, the bridge not switched on), the row is
 * {@code needs attention} with the fix ({@link SimStatus#cannotPressButtons}) and nothing is spawned;
 * the Python spawns first and finds out after. A {@code windowed:} row without buttons has its
 * windows minimised as they appear, best effort, on a thread of its own
 * ({@link Clicker#minimiseWhenShown}).
 *
 * <p>Before a spawn, a {@code capk_from} row's jar is rebuilt ({@link Capk}), a {@code tls:} row's tank level
 * simulator is set from the rig ({@link PetroTls}) and a row's {@code artifactory.after} step (Petro's SSLUtil) runs,
 * every launch, as the Python's {@code start} does. {@link #stopWrongVariants} closes a row's other-base form (LoySim
 * 9.1 against a 55.02 Commander), another override of D9 the rig owner made (2026-10-13). {@link #stopRunning} takes
 * both forms of one row at once, and stops every PID of either (the Python keeps only the second form's PIDs).
 *
 * <p>Every wait is bounded. A timeout is a {@code FAILED} row naming the timeout.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python CAT's {@code cat.sims.supervisor} is
 * the specification, and its own docstring records which parts ran against real simulators on
 * 2026-09-10 and 2026-09-14. None of that has been observed from this port.
 */
public final class Supervisor {

    /** The status row for a probe pass that could not be performed. Not a simulator. */
    public static final String PROBE_ROW = "process probe";

    /** How long a {@code replaceStale} row's running-but-not-listening process gets to bind. */
    public static final Duration STALE_BIND_WAIT = Duration.ofSeconds(20);

    /** How long {@link #stopRunning} gives killed processes to be gone. */
    public static final Duration STOP_WAIT = Duration.ofSeconds(10);

    /** The default bound on a process query. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    /** How long one connect attempt to a readiness port may take. */
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    /** How long {@link #stop} gives a terminated child before it is forced. */
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    /** The bound on {@link #stopRunning} in a run. */
    static final Duration RUN_STOP_TIMEOUT = Duration.ofSeconds(15);

    /** Interval between readiness checks. */
    static final Duration POLL = Duration.ofMillis(250);

    /** How one {@link #stopRunning} row ended. */
    public enum StopOutcome {
        /** Every PID found was killed and gone. */
        STOPPED,
        /** Nothing matching was running. */
        ABSENT,
        /** Still running, or the listing could not be taken: nothing was killed. */
        FAILED,
        /** Killed, but whether it went could not be checked. */
        UNKNOWN
    }

    /**
     * One row of {@link #stopRunning}'s answer.
     *
     * @param outcome how it ended
     * @param detail the PIDs, or the reason
     */
    public record StopRow(StopOutcome outcome, String detail) {
    }

    private enum Adoption {
        LISTENING,
        RUNNING,
        ABSENT
    }

    private final ProcessOps ops;
    private final Duration staleBindWait;
    private final Clicker clicker;

    /** Each row's {@code artifactory:} block, whose {@code after} step runs before every launch (a test's seam). */
    java.util.function.Function<SimEntry, Optional<SimSources.ArtifactorySource>> sources =
            entry -> SimSources.forRow(entry.name(), false);

    /** Where the RXTX agent jar is built and kept ({@link PetroTls#agentJar}); a test's temp folder. */
    Path agentCache = PetroTls.defaultAgentCache();

    /** The controller's serial ports for {@link PetroTls#configure}; null reads Windows' map. */
    Set<String> serialPorts;

    /** A supervisor that presses buttons through this controller's Access Bridge. */
    public Supervisor(ProcessOps ops) {
        this(ops, STALE_BIND_WAIT);
    }

    /** For tests, which cannot wait twenty seconds for a stale process. */
    public Supervisor(ProcessOps ops, Duration staleBindWait) {
        this(ops, staleBindWait, AccessBridgeClicker.forThisController());
    }

    /** For tests, which press buttons through fake bindings. */
    public Supervisor(ProcessOps ops, Duration staleBindWait, Clicker clicker) {
        this.ops = Objects.requireNonNull(ops, "ops");
        this.staleBindWait = Objects.requireNonNull(staleBindWait, "staleBindWait");
        this.clicker = Objects.requireNonNull(clicker, "clicker");
    }

    /**
     * One command-line probe pass, then one {@link #start} per entry, in order.
     *
     * <p><b>Blocks for a long time</b>: the probe is bounded by its own timeout, and the entries
     * start in sequence. Call it off any UI thread. {@code abandoned}, set by a caller that gave
     * up, stops the pass before the next entry.
     */
    public List<SimProcess> startAll(List<SimEntry> entries, SimContext ctx, Events events,
                                     Duration timeout, AtomicBoolean abandoned) {
        Events ev = events == null ? Events.NONE : events;
        List<SimEntry> list = List.copyOf(entries);
        String cmdlines;
        String probeError;
        try {
            ProcessOps.Snapshot snapshot = ops.commandLines(processNames(list, ctx), PROBE_TIMEOUT);
            cmdlines = snapshot.text();
            probeError = snapshot.error();
        } catch (IllegalArgumentException e) {
            cmdlines = "";
            probeError = e.getMessage();
        }
        if (!probeError.isEmpty()) {
            ev.status(SimStatus.processListUnavailable(probeError));
        }
        List<SimProcess> procs = new ArrayList<>();
        for (SimEntry entry : list) {
            if (abandoned != null && abandoned.get()) {
                break;
            }
            procs.add(start(entry, ctx, ev, cmdlines, probeError, timeout));
        }
        return procs;
    }

    /**
     * Adopts or spawns one simulator, bounded by {@code timeout} end to end.
     *
     * @param cmdlines the probe pass's command lines, or empty
     * @param probeError why that pass could not be performed, or empty
     */
    public SimProcess start(SimEntry entry, SimContext ctx, Events events, String cmdlines,
                            String probeError, Duration timeout) {
        Events ev = events == null ? Events.NONE : events;
        String lines = cmdlines == null ? "" : cmdlines;
        String error = probeError == null ? "" : probeError;
        long deadline = deadline(timeout);

        Adoption adoption;
        try {
            adoption = adoptionOf(entry, ctx, lines);
        } catch (IllegalArgumentException e) {
            return fail(entry, ev, SimStatus.Owner.CAT, "bad registry row: " + e.getMessage());
        }
        String why = adoption == Adoption.LISTENING ? "already listening" : "already running";
        if (adoption == Adoption.RUNNING && entry.hasTcpReady()) {
            // A command line proves a process exists, not that it serves. Such a process gets
            // the rest of its budget to bind; a process that never binds is a failed row, not
            // adopted, not stopped, and not spawned over.
            int port = entry.readyTcp();
            long bindBy = deadline;
            if (entry.replaceStale()) {
                bindBy = Math.min(deadline, System.nanoTime() + staleBindWait.toNanos());
            }
            long budget = secondsUntil(bindBy);
            ev.status(new SimStatus(entry.name(), SimStatus.State.STARTING, SimStatus.Owner.EXTERNAL, "",
                    "already running, not yet listening on " + port + " — waiting up to "
                            + budget + "s for it to bind"));
            if (awaitBind(hosts(ctx), port, bindBy)) {
                adoption = Adoption.LISTENING;
                why = "already running; listening on " + port;
            } else if (entry.replaceStale()) {
                SimProcess replaced = replaceStale(entry, ctx, port, budget, ev);
                if (replaced != null) {
                    return replaced;
                }
                // Killed: spawn CAT's own, with a start budget of its own.
                adoption = Adoption.ABSENT;
                deadline = deadline(timeout);
            } else {
                return fail(entry, ev, SimStatus.Owner.EXTERNAL,
                        "process present but not listening on " + port + " after " + budget
                                + "s — not adopted, and CAT did not start it, so it is left running "
                                + "(D9): stop it (Launch Simulators' Stop) and CAT starts its own");
            }
        }

        if (adoption != Adoption.ABSENT) {
            if (adoption == Adoption.LISTENING && entry.adoptOnlyMatch()) {
                int port = entry.readyTcp();
                String token = ctx.matchToken(entry);
                ProcessOps.Snapshot holder = ops.listenerCommandLine(port, PROBE_TIMEOUT);
                if (!holder.error().isEmpty()) {
                    why += " — could not confirm it is " + token + " (" + holder.error() + ")";
                } else if (!normalise(holder.text()).contains(normalise(token))) {
                    return fail(entry, ev, SimStatus.Owner.EXTERNAL, "port " + port
                            + " is held by another build, not " + token + ": "
                            + (holder.text().isEmpty() ? "?" : holder.text())
                            + " — stop it and CAT starts its own");
                }
            }
            SimStatus status = new SimStatus(entry.name(), SimStatus.State.ADOPTED,
                    SimStatus.Owner.EXTERNAL, "", why + " — left alone (D9)");
            ev.status(status);
            // An adopted simulator has no pipe: its file log is the only log there is.
            SimProcess adopted = new SimProcess(entry, status, null, startTail(entry, ctx, ev));
            ev.started(adopted);
            return adopted;
        }

        if (!entry.capkFrom().isEmpty()) {
            // CAT's jar, built from PATS's with CAPK expiries re-dated and the cut time moved
            // (Capk), before every launch; an up-to-date one is left as it is.
            try {
                Path target = ctx.jarFor(entry).orElseThrow(
                        () -> new IllegalArgumentException("capk_from: needs a -jar in argv"));
                Capk.build(ctx.cwdFor(entry).resolve(entry.capkFrom()), target);
            } catch (IOException | RuntimeException e) {
                String cause = e instanceof NoSuchFileException ? "no such file: " + e.getMessage()
                        : e.getMessage() == null ? e.toString() : e.getMessage();
                return fail(entry, ev, SimStatus.Owner.CAT, "cannot build its jar: " + cause);
            }
        }
        if (!entry.click().isEmpty()) {
            Optional<String> cannot;
            try {
                cannot = clicker.unavailable(ctx);
            } catch (RuntimeException e) {
                cannot = Optional.of("could not check the Access Bridge: " + e.getMessage());
            }
            if (cannot.isPresent()) {
                SimStatus status = SimStatus.cannotPressButtons(entry.name(), entry.click(), cannot.get());
                ev.status(status);
                return new SimProcess(entry, status, null, null);
            }
        }

        String caveat = error.isEmpty() ? ""
                : " — process probe unavailable (" + error
                + "); an already-running instance may be duplicated";
        SimStatus spawning = new SimStatus(entry.name(), SimStatus.State.STARTING, SimStatus.Owner.CAT, "",
                "spawning" + caveat);
        ev.status(spawning);
        entry = beforeLaunch(entry, ctx, ev);
        AtomicBoolean tripped = new AtomicBoolean();
        SimProcess proc = launch(entry, ctx, spawning, ev, deadline, tripped);
        if (tripped.get() && !entry.fallbackJdk().isEmpty()) {
            // An older build wants an older runtime: one relaunch, on the same deadline.
            stop(List.of(proc), STOP_TIMEOUT);
            try {
                Path jdk = Path.of(ctx.substitute(entry.fallbackJdk(), entry));
                Path jaxb = entry.fallbackJaxb().isEmpty()
                        ? null : Path.of(ctx.substitute(entry.fallbackJaxb(), entry));
                SimStatus retrying = new SimStatus(entry.name(), SimStatus.State.STARTING,
                        SimStatus.Owner.CAT, "", entry.fallbackWhen() + " in its output — retrying on " + jdk);
                ev.status(retrying);
                proc = launch(entry, ctx.withJdk(jdk, jaxb), retrying, ev, deadline, new AtomicBoolean());
            } catch (IllegalArgumentException e) {
                proc = new SimProcess(entry, new SimStatus(entry.name(), SimStatus.State.FAILED,
                        SimStatus.Owner.CAT, "", "bad registry row: " + e.getMessage()), null, null);
            }
        }
        ev.status(proc.status());
        return proc;
    }

    /**
     * The pre-launch steps of a row CAT is about to spawn, as the Python's {@code start} runs them: Petro's tank level
     * simulator set from the rig ({@link PetroTls#configure}; with a port, the launch gains {@code -javaagent:} for
     * the RXTX agent), then the vendor script's {@code artifactory.after} step (Petro's SSLUtil, which writes the
     * keystore its EPS listener on 5000 needs), on every launch as {@code startSimulator.bat} does. A failure of
     * either is a log line and the launch goes ahead, as in the Python. Returns the row to launch.
     */
    private SimEntry beforeLaunch(SimEntry entry, SimContext ctx, Events ev) {
        SimEntry launch = entry;
        if (entry.tls() != null) {
            try {
                ev.log(entry.name(), PetroTls.configure(ctx.cwdFor(entry), entry.tls(), ctx.tlsPort(), serialPorts));
                Optional<Path> exe = ctx.javawExe();
                boolean agented = entry.argv().stream().anyMatch(arg -> arg.startsWith("-javaagent:")
                        && arg.contains("cat-rxtx-agent"));
                if (!ctx.tlsPort().isEmpty() && exe.isPresent() && !agented) {
                    // Without it Petro sees no serial port at all (PetroTls). Once only: a restart is handed the
                    // row this launch ran, which carries it already (the Python would add it twice).
                    Path jar = PetroTls.agentJar(exe.get().resolveSibling("javac.exe"), agentCache);
                    launch = entry.withArgvFirst("-javaagent:" + jar);
                }
            } catch (IOException | RuntimeException e) {
                ev.log(entry.name(), "tank level simulator not configured: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        Optional<SimSources.ArtifactorySource> source;
        try {
            source = sources.apply(entry);
        } catch (RuntimeException e) {
            source = Optional.empty();
        }
        if (source.isPresent() && !source.get().after().isEmpty()) {
            SimFetch setup = new SimFetch(() -> {
                throw new IllegalStateException("a setup step downloads nothing");
            }, line -> ev.log(entry.name(), line));
            String problem = setup.setup(entry, source.get(), ctx, ctx.cwdFor(entry));
            if (!problem.isEmpty()) {
                ev.log(entry.name(), problem);
            }
        }
        return launch;
    }

    /**
     * Closes a running instance of a row's other-base form: the Python's {@code stop_wrong_variants}, an override of
     * D9 for this case only (rig owner, 2026-10-13). {@code others} is {@link Registry#otherVariants} for the run's
     * base: LoySim 9.1 left running from a base 57 run (or LoySim 7 from a 55.02 one) is the wrong version for this
     * Commander, and {@link #start} would start the right one beside it. Only a row whose two forms have different
     * match tokens can be told apart (not MPPA, whose 03 and 04 run the same main class). Matched by command line
     * alone, never by port, so the right version's listener is never touched. Rows with no wrong form running are
     * left out of the result; each detail starts with the wrong form's match token.
     */
    public Map<String, StopRow> stopWrongVariants(List<SimEntry> entries, Map<String, SimEntry> others,
            SimContext ctx, Duration timeout) {
        List<SimEntry> wrong = new ArrayList<>();
        for (SimEntry entry : entries) {
            SimEntry other = others.get(entry.name());
            if (other == null) {
                continue;
            }
            String mine;
            String theirs;
            try {
                mine = normalise(ctx.matchToken(entry));
                theirs = normalise(ctx.matchToken(other));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (mine.contains(theirs) || theirs.contains(mine)) {
                continue;
            }
            wrong.add(other.withoutReady());
        }
        Map<String, StopRow> out = new LinkedHashMap<>();
        if (wrong.isEmpty()) {
            return out;
        }
        ProcessOps.Snapshot listed;
        try {
            listed = ops.commandLines(processNames(wrong, ctx), timeout);
        } catch (IllegalArgumentException e) {
            listed = ProcessOps.Snapshot.failed(e.getMessage());
        }
        if (!listed.error().isEmpty()) {
            for (SimEntry other : wrong) {
                out.put(other.name(), new StopRow(StopOutcome.FAILED,
                        "could not list running processes: " + listed.error()));
            }
            return out;
        }
        String lines = normalise(listed.text());
        List<SimEntry> running = wrong.stream()
                .filter(other -> lines.contains(normalise(ctx.matchToken(other)))).toList();
        if (running.isEmpty()) {
            return out;
        }
        Map<String, StopRow> stopped = stopRunning(running, ctx, timeout);
        for (SimEntry other : running) {
            StopRow row = stopped.get(other.name());
            String detail = ctx.matchToken(other) + ": " + row.detail();
            while (detail.endsWith(":") || detail.endsWith(" ")) {
                detail = detail.substring(0, detail.length() - 1);
            }
            out.put(other.name(), new StopRow(row.outcome(), detail));
        }
        return out;
    }

    /**
     * Terminates only the processes {@link #start} spawned. An adopted one has no process and is
     * never touched (D9). Returns the names stopped.
     */
    public List<String> stop(List<SimProcess> procs, Duration timeout) {
        List<String> stopped = new ArrayList<>();
        for (SimProcess proc : procs) {
            if (proc.stopLog() != null) {
                proc.stopLog().run();
            }
            Process process = proc.process();
            if (process == null || !process.isAlive()) {
                continue;
            }
            process.destroy();
            if (!waitQuietly(process, timeout)) {
                process.destroyForcibly();
                waitQuietly(process, timeout);
            }
            stopped.add(proc.entry().name());
        }
        return stopped;
    }

    /**
     * Kills every running instance of {@code entries}, whoever started it. An operator's override
     * of D9, for the Simulators tab's Stop; a run reaches it only through {@link #start} on a
     * {@code replaceStale} row whose process never bound its port.
     *
     * <p>An instance is what {@link #probe} would adopt: the process listening on the entry's
     * {@code readyTcp}, and any process of the registry's executables whose command line carries
     * the match token. This process and PID 4 and below are never touched.
     */
    public Map<String, StopRow> stopRunning(List<SimEntry> entries, SimContext ctx, Duration timeout) {
        List<SimEntry> list = List.copyOf(entries);
        Map<String, StopRow> rows = new LinkedHashMap<>();
        ProcessOps.Snapshot listing;
        try {
            List<Integer> ports = list.stream().filter(SimEntry::hasTcpReady)
                    .map(SimEntry::readyTcp).distinct().sorted().toList();
            listing = ops.listing(processNames(list, ctx), ports, timeout);
        } catch (IllegalArgumentException e) {
            listing = ProcessOps.Snapshot.failed(e.getMessage());
        }
        if (!listing.error().isEmpty()) {
            for (SimEntry entry : list) {
                rows.put(entry.name(), new StopRow(StopOutcome.FAILED,
                        "could not list running processes: " + listing.error()));
            }
            return rows;
        }
        Listing parsed = Listing.parse(listing.text());
        Map<String, Set<Long>> targets = new LinkedHashMap<>();
        for (SimEntry entry : list) {
            try {
                Set<Long> pids = instances(entry, ctx, parsed);
                if (!pids.isEmpty()) {
                    // Two forms of one row (base 57's and 56's) are one row: every PID of either is stopped.
                    targets.computeIfAbsent(entry.name(), name -> new HashSet<>()).addAll(pids);
                    rows.remove(entry.name());
                } else if (!targets.containsKey(entry.name())) {
                    rows.putIfAbsent(entry.name(), new StopRow(StopOutcome.ABSENT, ""));
                }
            } catch (IllegalArgumentException e) {
                rows.put(entry.name(), new StopRow(StopOutcome.FAILED, "bad registry row: " + e.getMessage()));
            }
        }
        if (!targets.isEmpty()) {
            TreeSet<Long> every = new TreeSet<>();
            targets.values().forEach(every::addAll);
            List<Long> pids = List.copyOf(every);
            String said = ops.taskkill(pids, timeout);
            ProcessOps.Snapshot alive = ops.stillAlive(pids, timeout);
            Set<Long> left = pidsIn(alive.text());
            targets.forEach((name, found) -> rows.put(name, outcomeOf(found, alive, left, said)));
        }
        Map<String, StopRow> ordered = new LinkedHashMap<>();
        for (SimEntry entry : list) {
            ordered.put(entry.name(), rows.get(entry.name()));
        }
        return ordered;
    }

    // ----- adoption and probing ---------------------------------------------------------

    /**
     * What a read-only look at one registry row found.
     *
     * @param listening true if the row's ready port accepts a connection
     * @param host      the host that answered (the controller's, then loopback), or empty
     */
    public record Probe(boolean listening, String host) {
    }

    /**
     * Asks, read-only, whether a row's ready port is answering. It connects to nothing but that
     * port, and starts, adopts, stops and kills nothing. A row with no ready port is not listening,
     * and no connection is made.
     */
    public Probe probe(SimEntry entry, SimContext ctx) {
        if (!entry.hasTcpReady()) {
            return new Probe(false, "");
        }
        String host = firstListening(hosts(ctx), entry.readyTcp());
        return new Probe(!host.isEmpty(), host);
    }

    private static Adoption adoptionOf(SimEntry entry, SimContext ctx, String cmdlines) {
        if (entry.hasTcpReady() && !firstListening(hosts(ctx), entry.readyTcp()).isEmpty()) {
            return Adoption.LISTENING;
        }
        if (!cmdlines.isEmpty() && normalise(cmdlines).contains(normalise(ctx.matchToken(entry)))) {
            return Adoption.RUNNING;
        }
        return Adoption.ABSENT;
    }

    /** The addresses a listener might be on: the controller's, then loopback. */
    static List<String> hosts(SimContext ctx) {
        Set<String> hosts = new LinkedHashSet<>();
        if (!ctx.controllerHost().isEmpty()) {
            hosts.add(ctx.controllerHost());
        }
        hosts.add("localhost");
        return List.copyOf(hosts);
    }

    /** The first of {@code hosts} accepting on {@code port}, or empty. */
    static String firstListening(List<String> hosts, int port) {
        for (String host : hosts) {
            if (listening(host, port)) {
                return host;
            }
        }
        return "";
    }

    /** True if something accepts on {@code host:port}. An unresolvable host is false. */
    static boolean listening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean awaitBind(List<String> hosts, int port, long deadline) {
        while (true) {
            if (!firstListening(hosts, port).isEmpty()) {
                return true;
            }
            if (expired(deadline)) {
                return false;
            }
            pausePoll();
        }
    }

    /** Kills a stale {@code replaceStale} process. Null once it is gone; a failed row otherwise. */
    private SimProcess replaceStale(SimEntry entry, SimContext ctx, int port, long waited, Events ev) {
        StopRow row = stopRunning(List.of(entry), ctx, RUN_STOP_TIMEOUT).get(entry.name());
        if (row.outcome() == StopOutcome.STOPPED || row.outcome() == StopOutcome.ABSENT) {
            String killed = row.outcome() == StopOutcome.STOPPED
                    ? "killed " + row.detail() : "it exited by itself";
            ev.status(new SimStatus(entry.name(), SimStatus.State.STARTING, SimStatus.Owner.CAT, "",
                    "stale: running but not listening on " + port + " after " + waited + "s — "
                            + killed + "; starting CAT's own"));
            return null;
        }
        return fail(entry, ev, SimStatus.Owner.EXTERNAL, "stale: running but not listening on " + port
                + " after " + waited + "s, and could not be stopped ("
                + row.outcome().name().toLowerCase(Locale.ROOT) + ": " + row.detail()
                + ") — stop it by hand and CAT starts its own");
    }

    // ----- spawning and readiness -------------------------------------------------------

    /** Spawns one attempt and waits on its readiness. Emits nothing but the spawn's own handle. */
    private SimProcess launch(SimEntry entry, SimContext ctx, SimStatus spawning, Events ev,
                              long deadline, AtomicBoolean tripped) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(ctx.argv(entry))
                    .directory(ctx.cwdFor(entry).toFile())
                    .redirectErrorStream(true);
            builder.environment().putAll(ctx.envFor(entry));
            process = builder.start();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return new SimProcess(entry, new SimStatus(entry.name(), SimStatus.State.FAILED,
                    SimStatus.Owner.CAT, "", "could not launch: " + e.getMessage()), null, null);
        }
        SimLogs.pump(process, entry.name(), ev, entry.fallbackWhen(), tripped);
        Runnable stopLog = startTail(entry, ctx, ev);
        ev.started(new SimProcess(entry, spawning, process, stopLog));

        if (entry.windowed() && entry.click().isEmpty()) {
            try {
                clicker.minimiseWhenShown(process, deadline + AccessBridgeClicker.WINDOW_BUDGET.toNanos());
            } catch (RuntimeException ignored) {
                // Cosmetic: a window left up is never a row.
            }
        }
        Clicker.Clicked clicked = new Clicker.Clicked(true, "");
        if (!entry.click().isEmpty()) {
            ev.status(new SimStatus(entry.name(), SimStatus.State.STARTING, SimStatus.Owner.CAT, "",
                    "pressing " + String.join(", then ", entry.click()) + " through the Java Access Bridge"));
            try {
                clicked = clicker.click(entry, process, ctx, deadline);
            } catch (RuntimeException | LinkageError e) {
                // A click must degrade to a row, never end the pass.
                clicked = new Clicker.Clicked(false, "clicking raised " + e.getClass().getSimpleName() + ": "
                        + e.getMessage());
            }
        }
        SimStatus outcome = awaitReady(entry, ctx, process, deadline, tripped, clicked);
        return new SimProcess(entry, outcome, process, stopLog);
    }

    private static SimStatus awaitReady(SimEntry entry, SimContext ctx, Process process, long deadline,
                                        AtomicBoolean tripped, Clicker.Clicked clicked) {
        if (entry.hasTcpReady()) {
            int port = entry.readyTcp();
            List<String> hosts = hosts(ctx);
            String bind = hosts.get(0) + ":" + port;
            long budget = secondsUntil(deadline);
            while (!expired(deadline)) {
                if (tripped.get()) {
                    return new SimStatus(entry.name(), SimStatus.State.FAILED, SimStatus.Owner.CAT, bind,
                            entry.fallbackWhen() + " in its output");
                }
                String host = firstListening(hosts, port);
                if (!host.isEmpty()) {
                    return new SimStatus(entry.name(), SimStatus.State.READY, SimStatus.Owner.CAT,
                            host + ":" + port, "listening");
                }
                if (!process.isAlive()) {
                    return new SimStatus(entry.name(), SimStatus.State.FAILED, SimStatus.Owner.CAT, bind,
                            "exited with " + process.exitValue() + " before binding " + port);
                }
                try {
                    process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new SimStatus(entry.name(), SimStatus.State.FAILED, SimStatus.Owner.CAT, bind,
                            "interrupted while waiting for port " + port);
                }
            }
            return new SimStatus(entry.name(), SimStatus.State.FAILED, SimStatus.Owner.CAT, bind,
                    "no listener on port " + port + " within " + budget + "s");
        }
        if (entry.readyClicked()) {
            if (clicked.clicked()) {
                return new SimStatus(entry.name(), SimStatus.State.READY, SimStatus.Owner.CAT, "", clicked.detail());
            }
            // Never a row claiming ready, and never a silent skip.
            return SimStatus.awaitingButtons(entry.name(), entry.click(), clicked.detail());
        }
        return new SimStatus(entry.name(), SimStatus.State.STARTED, SimStatus.Owner.CAT, "",
                "readiness unknown — this entry declares no ready probe");
    }

    private static Runnable startTail(SimEntry entry, SimContext ctx, Events ev) {
        if (entry.log().isEmpty()) {
            return null;
        }
        return SimLogs.tail(ctx.cwdFor(entry).resolve(entry.log()), entry.name(), ev);
    }

    // ----- helpers ----------------------------------------------------------------------

    /** The executable names a probe must ask about: both java images for javaw, the exe for exe. */
    static List<String> processNames(List<SimEntry> entries, SimContext ctx) {
        TreeSet<String> names = new TreeSet<>();
        for (SimEntry entry : entries) {
            if (entry.runner() == Runner.JAVAW) {
                names.add("javaw.exe");
                names.add("java.exe");
            } else if (!entry.exe().isEmpty()) {
                names.add(Path.of(ctx.substitute(entry.exe(), entry)).getFileName().toString());
            }
        }
        names.removeIf(name -> name.isEmpty() || name.contains("'"));
        return List.copyOf(names);
    }

    /** Command lines compare case- and separator-insensitively: {@code /} and {@code \} are one. */
    static String normalise(String text) {
        return text.replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    /** The PIDs {@code probe} would call {@code entry} running by. Throws for a bad row. */
    private static Set<Long> instances(SimEntry entry, SimContext ctx, Listing listing) {
        Set<Long> pids = new HashSet<>();
        if (entry.hasTcpReady()) {
            pids.addAll(listing.listeners().getOrDefault(entry.readyTcp(), Set.of()));
        }
        String token = normalise(ctx.matchToken(entry));
        for (Listing.Proc proc : listing.processes()) {
            if (!token.isEmpty() && proc.commandLine().contains(token)) {
                pids.add(proc.pid());
            }
        }
        long self = ProcessHandle.current().pid();
        pids.removeIf(pid -> pid <= 4 || pid == self);
        return pids;
    }

    private static StopRow outcomeOf(Set<Long> pids, ProcessOps.Snapshot alive, Set<Long> left, String said) {
        String listed = pids.stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
        if (!alive.error().isEmpty()) {
            return new StopRow(StopOutcome.UNKNOWN, "killed pid " + listed + "; could not check: " + alive.error());
        }
        Set<Long> still = new TreeSet<>(pids);
        still.retainAll(left);
        if (!still.isEmpty()) {
            String stillListed = still.stream().map(String::valueOf).collect(Collectors.joining(", "));
            return new StopRow(StopOutcome.FAILED,
                    "pid " + stillListed + " still running" + (said.isEmpty() ? "" : ": " + said));
        }
        return new StopRow(StopOutcome.STOPPED, "pid " + listed);
    }

    private static Set<Long> pidsIn(String text) {
        Set<Long> pids = new HashSet<>();
        for (String token : text.split("\\s+")) {
            if (!token.isEmpty() && token.chars().allMatch(Character::isDigit)) {
                pids.add(Long.parseLong(token));
            }
        }
        return pids;
    }

    private static SimProcess fail(SimEntry entry, Events ev, SimStatus.Owner owner, String detail) {
        SimStatus status = new SimStatus(entry.name(), SimStatus.State.FAILED, owner, "", detail);
        ev.status(status);
        return new SimProcess(entry, status, null, null);
    }

    private static boolean waitQuietly(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static boolean expired(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static long secondsUntil(long deadline) {
        return Math.max(0, (deadline - System.nanoTime()) / TimeUnit.SECONDS.toNanos(1));
    }

    /**
     * A bounded poll interval, not {@code Thread.sleep}: a pass that finds nothing waits here, and
     * the callers re-check their own deadline on every pass.
     */
    private static void pausePoll() {
        try {
            new CountDownLatch(1).await(POLL.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A {@code netstat}-style listing, parsed: listeners by port, and processes by command line. */
    private record Listing(Map<Integer, Set<Long>> listeners, List<Proc> processes) {

        record Proc(long pid, String commandLine) {
        }

        static Listing parse(String text) {
            Map<Integer, Set<Long>> listeners = new LinkedHashMap<>();
            List<Proc> processes = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String[] parts = line.split("\t", 3);
                if (parts.length != 3) {
                    continue;
                }
                try {
                    if (parts[0].equals("L")) {
                        listeners.computeIfAbsent(Integer.parseInt(parts[1].strip()), k -> new HashSet<>())
                                .add(Long.parseLong(parts[2].strip()));
                    } else if (parts[0].equals("P")) {
                        processes.add(new Proc(Long.parseLong(parts[1].strip()), normalise(parts[2])));
                    }
                } catch (NumberFormatException ignored) {
                    // A line this parser cannot read is not a process it can kill.
                }
            }
            return new Listing(listeners, processes);
        }
    }
}
