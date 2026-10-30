package com.verifone.cat.web;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.devices.commander.CommanderSuiteInfo;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.shell.RigBusy;
import com.verifone.cat.shell.Shell;
import com.verifone.cat.sims.Events;
import com.verifone.cat.sims.PowerShellProcessOps;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.SimStatus;
import com.verifone.cat.sims.Supervisor;
import com.verifone.cat.sims.ToolsRoot;
import com.verifone.cat.sims.base.CommanderBase;
import com.verifone.cat.sims.base.JavaSets;

/**
 * The Launch Simulators page's state and actions: the Python CAT's {@code cat/web/pages/simulators.py},
 * {@code CatService.launch_simulators}/{@code stop_simulators} and {@code cat/launch/sims_launch.py}.
 *
 * <p>Every launchable registry row and the rig's connectors (BTT's card server on a VATS rig, the cloud tunnel),
 * each with what is running now. Ticked by default: what a run would start (every row CAT starts, the card server,
 * and the tunnel when Commander Connect onboards; {@link #resetTicks}). Start launches the ticked ones and leaves them
 * running for later runs to adopt: the card server first, the simulators, the tunnel last, as in a run. Start becomes
 * a red Stop once every ticked simulator is up; Stop closes every simulator, whoever started it (HANDOFF.md §1.116,
 * the operator's override of D9). Never a connector: both are left running, as the Python leaves them.
 *
 * <p>One action at a time across the whole UI: the {@link RigBusy} flag is the one a run and Install Upgrade share,
 * so a launch is refused while a run, a preflight or an install holds the rig. Cancel stops a launch in flight: no
 * further row starts, and what this launch spawned is stopped again (the Python's {@code _undo}).
 *
 * <p>The launch is rig-aware ({@link Launcher}): the controller's address fills BUYPASS's {@code -ip=}, the
 * Commander's {@code Base_Level} picks the rows' form and the Java 17 set (v1.2 for base 57 and later, v1.1 before),
 * read over SSH as a run's System Checks reads it. Each row's state is also published to
 * {@link #onState} listeners (the Python's {@code SimulatorState} events).
 *
 * <p>The connectors start as a run starts them: BTT through {@code launch.VatsCardServerStart}, the tunnel through
 * {@code launch.TunnelStart}. Deviation: Petro's port holds are not taken outside a run ({@link PortHolds};
 * {@code launch.PetroPorts.hold} needs a run's context), so a later run takes them itself. The Python marks no registry row {@code fep_only} today; the
 * label "(FEP simulator)" is drawn for the names in {@code fepOnly}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Nothing here has started or stopped a real simulator from this page.
 */
final class SimulatorsService {

    /** The connector rows' names, as the Python's. */
    static final String CARD_SERVER = "BTT Card Server";
    static final String CLOUD_TUNNEL = "Cloud Tunnel";

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

    /** A connector row (the card server, the tunnel): its probe and its start, a run's own code once ported. */
    interface Connector {
        /** "" when it is not running, else what is running (the Python's {@code probe_running} detail). */
        String probe();

        /** Starts it, detached; whether it came up, and the row's detail. */
        Map.Entry<Boolean, String> start(Consumer<String> log);

        /** A connector not ported yet: never running, and its start says which Python module it waits on. */
        static Connector unported(String module) {
            return new Connector() {
                @Override
                public String probe() {
                    return "";
                }

                @Override
                public Map.Entry<Boolean, String> start(Consumer<String> log) {
                    String why = "not wired in CAT-Java yet (" + module + ")";
                    log.accept(why);
                    return Map.entry(false, why);
                }
            };
        }
    }

    /** Petro's pump-state port holds for a launch's Petro Simulator Suite, kept for a later run. */
    interface PortHolds {
        void hold(Consumer<String> log);

        /** No holds: a launch outside a run takes none in CAT-Java yet. */
        PortHolds NONE = log -> log.accept("Petro's port holds are taken by a run's step 3 only in CAT-Java: "
                + "a run that adopts this Petro holds them itself");
    }

    /** The rows and context a launch uses: the Commander's base picks the rows' form and the Java 17 set. */
    interface Launcher {
        Launch plan(List<SimEntry> chosen, Consumer<String> log);
    }

    /** What a launch starts, with what. */
    record Launch(List<SimEntry> rows, SimContext ctx) {}

    private final Supervisor supervisor;
    private final SimContext ctx;
    private final List<SimEntry> rows;
    private final Executor executor;
    private final Map<String, SimStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> logs = new ConcurrentHashMap<>();
    private final Set<String> ticked = ConcurrentHashMap.newKeySet();
    private final List<SimProcess> owned = new CopyOnWriteArrayList<>();
    private final AtomicReference<Busy> busy = new AtomicReference<>(Busy.IDLE);
    private final Map<String, Connector> connectors = new LinkedHashMap<>();
    private final Map<String, Map.Entry<String, String>> connectorRows = new ConcurrentHashMap<>();
    /** What the page-open probe found per row, before anything starts ({@code SimRunning.probeRunning}). */
    private final Map<String, com.verifone.cat.sims.SimRunning.Running> probed = new ConcurrentHashMap<>();
    private java.util.function.Function<List<SimEntry>, Map<String, com.verifone.cat.sims.SimRunning.Running>> prober;
    private java.util.function.Function<List<String>, List<SimEntry>> stopRows;
    private final List<Consumer<SimStatus>> stateListeners = new CopyOnWriteArrayList<>();
    private volatile String message = "";
    private volatile AtomicBoolean abandoned = new AtomicBoolean();

    private RigBusy rigBusy = new RigBusy();
    private Shell shell;
    private Set<String> fepOnly = Set.of();
    private PortHolds holds = PortHolds.NONE;
    private Launcher launcher;
    private BooleanSupplier ccOnboard = () -> false;

    private final Events events = new Events() {
        @Override
        public void status(SimStatus status) {
            statuses.put(status.name(), status);
            for (Consumer<SimStatus> listener : stateListeners) {
                try {
                    listener.accept(status);
                } catch (RuntimeException e) {
                    // A listener that has gone must not end the launch.
                }
            }
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
        this.launcher = (chosen, log) -> new Launch(chosen, ctx);
        for (SimEntry row : this.rows) {
            ticked.add(row.name());
        }
    }

    /** No rig: localhost's simulators, no connectors but the tunnel's row. */
    static SimulatorsService standard() {
        return standard("", new Shell(), () -> false);
    }

    /**
     * The page's real service for {@code rigName} (as {@code --rig} named it): the launchable registry rows,
     * PowerShell for the process queries, one daemon thread for starts and stops, the rig's controller address and
     * connectors, the Commander's base read over SSH at launch, and the shell's busy flag and toasts. A rig that will
     * not load is probed on localhost alone, as the Python's.
     */
    static SimulatorsService standard(String rigName, Shell shell, BooleanSupplier ccOnboard) {
        Executor pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cat-web-sims");
            thread.setDaemon(true);
            return thread;
        });
        Rig rig = loadRig(rigName);
        Path root = ToolsRoot.resolve(System.getenv(ToolsRoot.ENV), null);
        // The rig's controller address (BUYPASS's -ip=) and its TLS port (Petro's), as the Python's for_rig.
        SimContext rigContext = SimContext.forRig(root, rig);
        PowerShellProcessOps ops = new PowerShellProcessOps();
        SimulatorsService service = new SimulatorsService(new Supervisor(ops), rigContext,
                Registry.launchable(false), pool);
        service.prober = rows -> com.verifone.cat.sims.SimRunning.probeRunning(rows, rigContext, ops);
        service.stopRows = Registry::bothForms;
        service.shell = shell;
        service.rigBusy = shell.busy();
        service.ccOnboard = ccOnboard;
        if (rig != null && rig.pinpad() == Rig.Pinpad.VATS && rig.vats() != null) {
            service.connectors.put(CARD_SERVER, cardServerRow(rig.vats(), com.verifone.cat.launch.VatsCardServerStart.REAL));
        }
        service.connectors.put(CLOUD_TUNNEL, tunnelRow(rigName, new PowerShellProcessOps()));
        if (rig != null) {
            service.launcher = (chosen, log) -> {
                CommanderBase.Base base = CommanderBase.of("", () -> {
                    CommanderAccess access = new CommanderAccess(rig);
                    return CommanderSuiteInfo.read(access::connectedTransport, access.sshTimeout());
                });
                JavaSets.JavaSet java = JavaSets.javaForRun(base.base());
                log.accept("BASE  " + base.why());
                log.accept("JAVA  Java 17 " + java.version() + " (" + java.target() + ") for " + java.forBases());
                boolean older = JavaSets.olderBase(base.base());
                List<SimEntry> forBase = Registry.launchable(older);
                List<SimEntry> picked = new ArrayList<>();
                for (SimEntry entry : chosen) {
                    picked.add(forBase.stream().filter(e -> e.name().equals(entry.name())).findFirst().orElse(entry));
                }
                return new Launch(picked, rigContext.withJavaDir(java.target()));
            };
        }
        service.resetTicks();
        return service;
    }

    /**
     * The cloud tunnel's row: running when a process's command line is a {@code cloud-tunnel --rig <rig>} serve (the
     * Python's {@code _is_tunnel_for}, {@code CloudTunnelProcess.isTunnelFor}); started as step 4 starts it
     * ({@code launch.TunnelStart}, the Python's {@code _start_cloud_tunnel}), detached. CAT never stops it.
     */
    static Connector tunnelRow(String rigName, com.verifone.cat.sims.ProcessOps ops) {
        Rig loaded = loadRig(rigName);
        Connector start = loaded == null ? Connector.unported("a rig that loads: rig '" + rigName + "' did not")
                : new Connector() {
                    @Override
                    public String probe() {
                        return "";
                    }

                    @Override
                    public Map.Entry<Boolean, String> start(Consumer<String> log) {
                        String[] last = {""};
                        com.verifone.cat.commander.Say say = new com.verifone.cat.commander.Say() {
                            @Override
                            public void out(String line) {
                                last[0] = line;
                                log.accept(line);
                            }

                            @Override
                            public void err(String line) {
                                last[0] = line;
                                log.accept(line);
                            }
                        };
                        Map<String, String> env = System.getenv();
                        boolean up = com.verifone.cat.launch.TunnelStart.start(rigName, loaded, env,
                                Path.of("").toAbsolutePath(), com.verifone.cat.launch.TunnelStart.system(env), say);
                        return Map.entry(up, last[0]);
                    }
                };
        return new Connector() {
            @Override
            public String probe() {
                if (rigName == null || rigName.isBlank()) {
                    return "";
                }
                com.verifone.cat.sims.ProcessOps.Snapshot snapshot = ops.commandLines(
                        com.verifone.cat.cloudtunnel.CloudTunnelProcess.EXES, Duration.ofSeconds(15));
                for (String line : snapshot.text().split("\\R")) {
                    if (com.verifone.cat.cloudtunnel.CloudTunnelProcess.isTunnelFor(line, rigName)) {
                        return "cloud-tunnel --rig " + rigName;
                    }
                }
                return "";
            }

            @Override
            public Map.Entry<Boolean, String> start(Consumer<String> log) {
                String running = probe();
                if (!running.isEmpty()) {
                    log.accept("Cloud Tunnel: already running (" + running + ")");
                    return Map.entry(true, "already running: " + running);
                }
                return start.start(log);
            }
        };
    }

    /**
     * BTT's card server row on a VATS rig: up when its port answers (BTT's own first test), started by the vats
     * slice's {@code VatsCardServerStart} as step 3 starts it. CAT never stops BTT.
     */
    static Connector cardServerRow(Rig.Vats vats, com.verifone.cat.launch.VatsCardServerStart.Starter starter) {
        return new Connector() {
            @Override
            public String probe() {
                Integer port = vats.cardServerPort();
                return port != null && com.verifone.cat.devices.vats.Ports.listening(port) ? "answers on " + port : "";
            }

            @Override
            public Map.Entry<Boolean, String> start(Consumer<String> log) {
                var result = starter.start(vats, com.verifone.cat.launch.VatsCardServerStart.BTT_START_TIMEOUT, log);
                return Map.entry(result.ok(), result.detail());
            }
        };
    }

    private static Rig loadRig(String rigName) {
        if (rigName == null || rigName.isBlank()) {
            return null;
        }
        try {
            return RigLoader.load(rigName, RigLoader.rigsDir(System.getenv()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ wiring (the shell, and hooks for later slices)

    /** The busy flag shared with the run and Install Upgrade, and the toasts. */
    SimulatorsService withShell(Shell shared) {
        this.shell = shared;
        this.rigBusy = shared.busy();
        return this;
    }

    /** A connector row and its start (the VATS and tunnel ports hand theirs in here). */
    SimulatorsService withConnector(String name, Connector connector) {
        connectors.put(name, connector);
        return this;
    }

    SimulatorsService withFepOnly(Set<String> names) {
        fepOnly = Set.copyOf(names);
        return this;
    }

    SimulatorsService withPortHolds(PortHolds portHolds) {
        holds = portHolds;
        return this;
    }

    SimulatorsService withLauncher(Launcher plan) {
        launcher = plan;
        return this;
    }

    SimulatorsService withCcOnboard(BooleanSupplier onboarding) {
        ccOnboard = onboarding;
        return this;
    }

    /** Hears every row's state as it changes (the Python's {@code SimulatorState} events). */
    void onState(Consumer<SimStatus> listener) {
        stateListeners.add(listener);
    }

    // ------------------------------------------------------------ rows

    List<SimEntry> rows() {
        return rows;
    }

    /** The connector rows, in launch order. */
    List<String> connectors() {
        return List.copyOf(connectors.keySet());
    }

    boolean isFepOnly(String name) {
        return fepOnly.contains(name);
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

    /** What else holds the rig ("a run"), or "" when only this page's action, or nothing, does. */
    String otherBusy() {
        String held = rigBusy.current();
        return held.isEmpty() || RigBusy.SIMS_LAUNCH.equals(held) || RigBusy.SIMS_STOP.equals(held) ? ""
                : RigBusy.words(held);
    }

    String message() {
        return message;
    }

    /** The supervisor's process-probe notice (its own row, not a simulator), or empty. */
    String probeNotice() {
        SimStatus probe = statuses.get(Supervisor.PROBE_ROW);
        return probe == null ? "" : probe.detail();
    }

    /**
     * The ticks a run would start, as the page opens: every launchable row that is not a FEP's host, the card
     * server, and the cloud tunnel when Commander Connect onboards (the Python's {@code _default_ticks}).
     */
    void resetTicks() {
        if (busy.get() != Busy.IDLE) {
            return;
        }
        ticked.clear();
        for (SimEntry row : rows) {
            if (!fepOnly.contains(row.name())) {
                ticked.add(row.name());
            }
        }
        if (connectors.containsKey(CARD_SERVER)) {
            ticked.add(CARD_SERVER);
        }
        if (connectors.containsKey(CLOUD_TUNNEL) && ccOnboard.getAsBoolean()) {
            ticked.add(CLOUD_TUNNEL);
        }
    }

    /**
     * What is running before anything starts, asked once as the page opens: one process listing for every row, then
     * each row's port and match token (the Python's {@code probe_running}). On its own daemon thread, since the
     * listing may take 15 s; the rows show it once it lands. A test's service has no prober and probes by port only.
     */
    void probe() {
        if (prober == null || busy.get() != Busy.IDLE) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                probed.clear();
                probed.putAll(prober.apply(rows));
            } catch (RuntimeException e) {
                notify("Could not see which simulators are running: " + e.getMessage(), "warning", 5);
            }
        }, "cat-web-sims-probe");
        thread.setDaemon(true);
        thread.start();
    }

    /** Ticks or unticks one row or connector. Unknown names are ignored. */
    void toggle(String name) {
        if (row(name).isEmpty() && (name == null || !connectors.containsKey(name))) {
            return;
        }
        if (!ticked.remove(name)) {
            ticked.add(name);
        }
    }

    /** Ticks every row and connector, or unticks every one. */
    void selectAll(boolean on) {
        if (on) {
            rows.forEach(row -> ticked.add(row.name()));
            ticked.addAll(connectors.keySet());
        } else {
            ticked.clear();
        }
    }

    /**
     * What the Start / Stop control shows: the red Stop once every ticked simulator is up (the Python page's rule,
     * which looks at the simulators, not the connectors), otherwise Start.
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
            String detail = join(status.bind(), status.detail());
            if (owned.stream().anyMatch(p -> p.entry().name().equals(entry.name()))
                    && status.state() == SimStatus.State.ADOPTED) {
                detail = "started by CAT from this page";
            }
            return new RowView(entry.name(), status.state().label(), status.owner().label(), detail, up, attention);
        }
        com.verifone.cat.sims.SimRunning.Running found = probed.get(entry.name());
        if (found != null && !com.verifone.cat.sims.SimRunning.Running.NOT_RUNNING.equals(found.status())) {
            boolean up = com.verifone.cat.sims.SimRunning.Running.ALREADY_RUNNING.equals(found.status());
            return new RowView(entry.name(), found.status(), up ? "external" : "", found.detail(), up,
                    !up && com.verifone.cat.sims.SimRunning.Running.FAILED.equals(found.status()));
        }
        if (listening(entry)) {
            return new RowView(entry.name(), "already running", "external",
                    "listening on " + (ctx.controllerHost().isEmpty() ? "localhost" : ctx.controllerHost()) + ":"
                            + entry.readyTcp() + " (not started from this page)", true, false);
        }
        return new RowView(entry.name(), "stopped", "",
                entry.hasTcpReady() ? "not listening on port " + entry.readyTcp() : "no ready port to probe",
                false, false);
    }

    /** A connector row as the page shows it: what it last did here, else what its probe sees. */
    RowView connectorView(String name) {
        Map.Entry<String, String> done = connectorRows.get(name);
        if (done != null) {
            return new RowView(name, done.getKey(), "", done.getValue(), "ready".equals(done.getKey()),
                    "failed".equals(done.getKey()));
        }
        Connector connector = connectors.get(name);
        String probe;
        try {
            probe = connector == null ? "" : connector.probe();
        } catch (RuntimeException e) {
            probe = "";
        }
        return probe.isEmpty() ? new RowView(name, "not running", "", "", false, false)
                : new RowView(name, "already running", "", probe, true, false);
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

    // ------------------------------------------------------------ the actions

    /**
     * Starts every ticked row and connector on the executor. Returns at once; {@link #message} says what happened,
     * and the status and log reflect the launch as it goes.
     */
    void start() {
        List<SimEntry> chosen = rows.stream().filter(row -> ticked.contains(row.name())).toList();
        List<String> chosenConnectors = connectors.keySet().stream().filter(ticked::contains).toList();
        if (chosen.isEmpty() && chosenConnectors.isEmpty()) {
            message = "Nothing to launch: tick a simulator.";
            return;
        }
        if (busy.get() != Busy.IDLE) {
            message = busyMessage();
            return;
        }
        String refusal = rigBusy.begin(RigBusy.SIMS_LAUNCH);
        if (!refusal.isEmpty()) {
            message = refusal;
            notify(refusal, "warning", 5);
            return;
        }
        if (!busy.compareAndSet(Busy.IDLE, Busy.STARTING)) {
            rigBusy.end(RigBusy.SIMS_LAUNCH);
            message = busyMessage();
            return;
        }
        List<String> what = new ArrayList<>();
        if (!chosen.isEmpty()) {
            what.add(chosen.size() + " simulator(s)");
        }
        what.addAll(chosenConnectors);
        message = "Launching " + String.join(", ", what) + "; the Log has every line.";
        notify(message, "info", 5);
        AtomicBoolean cancel = new AtomicBoolean();
        abandoned = cancel;
        submit(() -> runStart(chosen, chosenConnectors, cancel), RigBusy.SIMS_LAUNCH);
    }

    /** Cancel a launch in flight: no further row starts, and what it spawned is stopped again. */
    void cancel() {
        if (busy.get() != Busy.STARTING) {
            message = "No launch in progress.";
            return;
        }
        abandoned.set(true);
        message = "Stopping the launch…";
        notify("Stopping a simulator launch…", "info", 5);
    }

    /** The red Stop: every simulator, whoever started it. Returns at once, like {@link #start}. */
    void stop() {
        if (busy.get() != Busy.IDLE) {
            message = busyMessage();
            return;
        }
        String refusal = rigBusy.begin(RigBusy.SIMS_STOP);
        if (!refusal.isEmpty()) {
            message = refusal;
            notify(refusal, "warning", 5);
            return;
        }
        if (!busy.compareAndSet(Busy.IDLE, Busy.STOPPING)) {
            rigBusy.end(RigBusy.SIMS_STOP);
            message = busyMessage();
            return;
        }
        message = "Stopping every simulator; the Log has every line.";
        notify(message, "info", 5);
        submit(this::runStop, RigBusy.SIMS_STOP);
    }

    private void submit(Runnable job, String kind) {
        try {
            executor.execute(job);
        } catch (RuntimeException e) {
            busy.set(Busy.IDLE);
            rigBusy.end(kind);
            message = "Could not start the job: " + e.getMessage();
        }
    }

    private void runStart(List<SimEntry> chosen, List<String> chosenConnectors, AtomicBoolean cancel) {
        List<SimProcess> spawned = new ArrayList<>();
        try {
            List<String> failed = new ArrayList<>();
            Consumer<String> log = line -> append(LAUNCH_LOG, line);
            log.accept("Launch Simulators: " + String.join(", ", names(chosen, chosenConnectors)));
            if (chosenConnectors.contains(CARD_SERVER) && !cancel.get()) {
                if (!startConnector(CARD_SERVER, log)) {
                    failed.add(CARD_SERVER);
                }
            }
            if (!chosen.isEmpty() && !cancel.get()) {
                Launch launch = launcher.plan(chosen, log);
                if (launch.rows().stream().anyMatch(r -> "Petro Simulator Suite".equals(r.name()))) {
                    holds.hold(log);
                }
                List<SimProcess> procs = supervisor.startAll(launch.rows(), launch.ctx(), events,
                        ROW_START_BUDGET.multipliedBy(launch.rows().size()), cancel);
                procs.stream().filter(SimProcess::spawned).forEach(spawned::add);
                chosen.stream().map(this::view).filter(v -> !v.up()).map(RowView::name).forEach(failed::add);
            }
            if (chosenConnectors.contains(CLOUD_TUNNEL) && !cancel.get()) {
                if (!startConnector(CLOUD_TUNNEL, log)) {
                    failed.add(CLOUD_TUNNEL);
                }
            }
            if (cancel.get()) {
                undo(spawned);
                message = "Launch stopped: what it started was stopped again.";
                notify(message, "warning", 10);
                return;
            }
            owned.addAll(spawned);
            message = failed.isEmpty() ? "Launch finished: all " + (chosen.size() + chosenConnectors.size()) + " up."
                    : "Launch finished. Not up: " + String.join(", ", failed) + ".";
            if (failed.isEmpty()) {
                notify("Simulators launched, and left running for the next run.", "positive", 5);
            } else {
                notify("Launch Simulators: " + String.join(", ", failed) + " did not start. See the Log.", "error", 15);
            }
        } catch (RuntimeException e) {
            undo(spawned);
            message = "Launch failed: " + e.getMessage();
            notify("Launch Simulators failed: " + e.getMessage(), "error", 15);
        } finally {
            busy.set(Busy.IDLE);
            rigBusy.end(RigBusy.SIMS_LAUNCH);
        }
    }

    /** The Python's {@code _undo}: stop what this launch spawned (only that, D9). */
    private void undo(List<SimProcess> spawned) {
        try {
            supervisor.stop(List.copyOf(spawned), CHILD_STOP_BUDGET);
        } catch (RuntimeException e) {
            // Best effort: the row's state says what is still up.
        }
    }

    private boolean startConnector(String name, Consumer<String> log) {
        connectorRows.put(name, Map.entry("starting", ""));
        Map.Entry<Boolean, String> up;
        try {
            up = connectors.get(name).start(log);
        } catch (RuntimeException e) {
            up = Map.entry(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        connectorRows.put(name, Map.entry(up.getKey() ? "ready" : "failed", up.getValue()));
        return up.getKey();
    }

    private static List<String> names(List<SimEntry> chosen, List<String> chosenConnectors) {
        Set<String> out = new LinkedHashSet<>();
        if (chosenConnectors.contains(CARD_SERVER)) {
            out.add(CARD_SERVER);
        }
        chosen.forEach(e -> out.add(e.name()));
        if (chosenConnectors.contains(CLOUD_TUNNEL)) {
            out.add(CLOUD_TUNNEL);
        }
        return List.copyOf(out);
    }

    /** The log every launch line (not a simulator's own output) is kept under. */
    static final String LAUNCH_LOG = "Launch Simulators";

    private void runStop() {
        try {
            supervisor.stop(List.copyOf(owned), CHILD_STOP_BUDGET);
            for (SimProcess proc : owned) {
                if (proc.entry().hasTcpReady()) {
                    awaitPortClosed(proc.entry(), CHILD_STOP_BUDGET);
                }
            }
            owned.clear();
            probed.clear();
            // Either form of a row may be the one running (base 57's, or 56 and earlier's), so both are stopped.
            List<SimEntry> targets = stopRows == null ? rows
                    : stopRows.apply(rows.stream().map(SimEntry::name).toList());
            Map<String, Supervisor.StopRow> outcome = supervisor.stopRunning(targets, ctx,
                    stopRows == null ? STOP_BUDGET : Supervisor.STOP_WAIT);
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
            if (failed.isEmpty()) {
                notify("Simulators stopped.", "positive", 5);
            } else {
                notify("Stop Simulators: " + String.join(", ", failed) + " may still be running. See the Log.",
                        "error", 15);
            }
        } catch (RuntimeException e) {
            message = "Stop failed: " + e.getMessage();
            notify("Stop Simulators failed: " + e.getMessage(), "error", 15);
        } finally {
            busy.set(Busy.IDLE);
            rigBusy.end(RigBusy.SIMS_STOP);
        }
    }

    private void notify(String text, String kind, double seconds) {
        if (shell != null) {
            shell.notify(text, kind, seconds);
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
