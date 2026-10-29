package com.verifone.cat.launch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.verifone.cat.devices.fep.FepsimLegacy;
import com.verifone.cat.fep.SecondaryFep;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.Events;
import com.verifone.cat.sims.FepHosts;
import com.verifone.cat.sims.Fepsim;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.SimStatus;
import com.verifone.cat.sims.Supervisor;
import com.verifone.cat.sims.ToolsRoot;
import com.verifone.cat.sims.base.JavaSets;

/**
 * Step 3, Start Simulators: adopt each selected simulator that is up, start the rest, and report every row. The Python
 * CAT's {@code cat.launch.steps.simulators._step_simulators}, through {@code sims.Supervisor}, with its
 * {@code _supersede}, {@code _retire_instances}, {@code _fep_listeners}, {@code _await_superseding_listener},
 * {@code _mark_fep_captures} and {@code _stop_wrong_variants}.
 *
 * <p>What is started: the run's simulators plus each chosen Secondary FEP's host simulator, ticked or not
 * ({@link FepHosts#allSimulators}; an "ADD" line says so), as the registry rows for the Commander base step 1 read
 * ({@link RunContext#simsBase}), on that base's Java 17 set. Then, before anything starts:
 * <ul>
 *   <li>a row another row's FEP simulator can serve is not started ({@link Fepsim#supersede}: with Petro Simulator
 *       Suite's FEPSIM v2 staged, BUYPASS Host is not; rig owner, 2026-10-12): any running BUYPASS Host is closed (it
 *       would hold the port) and the suite's {@code BUYPASS-41107} instance is written so it opens at start-up. A
 *       run with {@code --buypass-host} ({@link LaunchConfig#buypassHost}) keeps BUYPASS Host and turns that instance
 *       off ({@link Fepsim#retireInstance}), closing a running suite that had it on;</li>
 *   <li>each chosen Secondary FEP with a {@code fepsim_instance:} (WEX-OTR, Comdata) gets its instance written, and
 *       the step fails when its host's FEPSIM is older than v2;</li>
 *   <li>Petro's pump-state ports are held ({@link PetroPorts#hold}) and the step fails when another process holds
 *       PATS's 1072 and the run drives pumps ({@link PetroPorts#patsPortFreeForPumps});</li>
 *   <li>once the base is known, a simulator left running in the other base's version is closed
 *       ({@link Supervisor#stopWrongVariants}).</li>
 * </ul>
 * After the start, each superseding listener and each FEP listener must accept, with every port it needs (its suite's
 * WireMock): one restart of a CAT-started host when the WireMock is down, a live registration through the suite's
 * admin API when the suite was adopted. Each listener's capture file is then marked, for {@link #fepExchangeLines}.
 * Every supervisor status is a {@code SimulatorState} event (the web board's rows), a checklist row and a log line.
 *
 * <p>The step's own rule on top of D9: a row the supervisor reports {@code failed} or {@code needs attention} fails
 * the step (HANDOFF §2: stricter than the Python, which lets {@code needs attention} through as a warning).
 *
 * <p>Deviations: the run's cases are collected in step 5 here, so the production step does not know whether they
 * drive pumps and goes on with a WARNING ({@link PetroPorts}); a FEPSIM example missing from the suite fails the step
 * naming it (the Python raises out of {@code _supersede}); the run's FEP state is kept here, keyed by the run
 * ({@link #hosts}), until {@link RunContext} carries it.
 *
 * <p>The BTT card server for a {@code pinpad: vats} rig belongs to the VATS port: {@link CardServer} is its hook,
 * run first as the Python's {@code _start_card_server} is; until it is bound such a rig run is unavailable here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the supervisor's tests use a fake process layer and fake simulators; the
 * FEPSIM v2 path has never run on a rig from either port.
 */
final class StartSimulatorsStep implements LaunchStep {

    /** The Petro Simulator Suite's registry name: the forecourt and ViperPay pinpad simulator. */
    static final String PETRO_SIMULATOR = "Petro Simulator Suite";

    /** The row whose packet file {@code emv-keys} reads for each download's answer (the Python's PAYMENT_HOST). */
    static final String PAYMENT_HOST = "BUYPASS Host";

    /** Per-simulator start budget: the Python CAT's {@code SIM_START_TIMEOUT}. */
    static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    /** How long a superseding listener gets to open once its simulator is ready: the Python's slack. */
    static final Duration SUPERSEDING_LISTENER_WAIT = Duration.ofSeconds(30);

    /** Starts the BTT card server on a {@code pinpad: vats} rig: the Python's {@code _start_card_server}. */
    @FunctionalInterface
    interface CardServer {
        /** True to go on; false when the step has failed (its detail set). */
        boolean start(RunContext ctx, StepEmitter step);
    }

    /** Hands a FEPSIM instance to a running suite's admin API ({@link Fepsim#registerInstance}). */
    @FunctionalInterface
    interface Registrar {
        String register(String admin, Path suite, String instance) throws IOException;
    }

    /**
     * What this run's step 3 decided about the FEP hosts, for the later steps.
     *
     * @param sims the simulators' context (tools root, controller, Java set, TLS port)
     * @param superseded the rows a FEP simulator serves this run
     * @param listeners the Secondary FEPs' FEPSIM listeners
     * @param marks each listener's capture file size after the start, by instance
     */
    record Hosts(SimContext sims, List<Fepsim.Superseded> superseded, List<Fepsim.FepListener> listeners,
            Map<String, Long> marks) {
        Hosts {
            superseded = List.copyOf(superseded);
            listeners = List.copyOf(listeners);
            marks = Map.copyOf(marks);
        }
    }

    private static final Map<RunContext, Hosts> HOSTS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Whether the run's cases drive a pump, or empty when that is not known yet. */
    private final Function<RunContext, Optional<Boolean>> drivesPumps;

    /** Where Petro Simulator Suite logs, for the EPS mark; null when it names no log. */
    private final Function<SimContext, Path> petroLog;

    /** The tools root; null resolves {@code CAT_TOOLS_ROOT}, else the default. A test's temp folder. */
    Path toolsRoot;

    /** The registry rows for a base (true: 56 and earlier). A test's own rows. */
    Function<Boolean, List<SimEntry>> registry = Registry::forBase;

    /** The VATS card server's hook. */
    CardServer cardServer = StartSimulatorsStep::cardServerNotPorted;

    /** Whether {@code host:port} accepts a connection. */
    BiPredicate<String, Integer> accepts = StartSimulatorsStep::acceptsConnection;

    /** The listener wait and its poll. */
    Duration listenerWait = SUPERSEDING_LISTENER_WAIT;
    Duration listenerPoll = Duration.ofSeconds(1);

    /** The admin API registration. */
    Registrar registrar = Fepsim::registerInstance;

    /** The chosen Secondary FEPs' table rows. */
    Function<List<String>, List<SecondaryFep>> fepRows = FepHosts::secondaryFepRows;

    /** The production step: the run's cases are collected in step 5, so whether they drive a pump is not known here. */
    StartSimulatorsStep() {
        this(ctx -> Optional.empty());
    }

    /** @param drivesPumps whether the run's cases drive a pump, or empty when unknown */
    StartSimulatorsStep(Function<RunContext, Optional<Boolean>> drivesPumps) {
        this(drivesPumps, StartSimulatorsStep::petroLog);
    }

    /** @param petroLog Petro's log for the EPS mark (a test passes a temp file) */
    StartSimulatorsStep(Function<RunContext, Optional<Boolean>> drivesPumps, Function<SimContext, Path> petroLog) {
        this.drivesPumps = drivesPumps;
        this.petroLog = petroLog;
    }

    @Override
    public String name() {
        return LaunchStepNames.SIMULATORS;
    }

    @Override
    public boolean run(RunContext ctx, StepEmitter step) {
        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
        boolean fakePos = ctx.config().fakePos();
        if (!cardServer.start(ctx, step)) {
            return false;
        }

        List<String> feps = ctx.commanderOptions().secondaryFeps();
        List<SecondaryFep> chosenFeps = fakePos || feps.isEmpty() ? List.of() : fepRows.apply(feps);
        Map<String, String> fepHosts = FepHosts.fepSimulators(chosenFeps);
        Set<String> selected = new LinkedHashSet<>(ctx.config().simulators());
        fepHosts.forEach((simulator, names) -> {
            if (!selected.contains(simulator)) {
                step.log("ADD   " + simulator + " — the host of Secondary FEP " + names, "INFO");
            }
        });
        selected.addAll(fepHosts.keySet());
        if (selected.isEmpty()) {
            step.skip("simulators", "the run selected no simulators");
            return step.skipStep("no simulators selected — nothing started");
        }

        boolean older = JavaSets.olderBase(ctx.simsBase());
        List<SimEntry> entries = new ArrayList<>();
        for (SimEntry entry : registry.apply(older)) {
            if (selected.contains(entry.name()) && !entry.stagedOnly()) {
                entries.add(entry);
            }
        }
        for (String unknown : new TreeSet<>(selected)) {
            if (entries.stream().noneMatch(entry -> entry.name().equals(unknown))) {
                step.check(false, unknown, "not in the simulator registry", false);
            }
        }
        if (entries.isEmpty()) {
            return step.fail("none of the selected simulators are in the registry");
        }

        // The Commander base step 1 read picks the Java 17 set (rig owner, 2026-10-09): v1.1 below base 57.
        JavaSets.JavaSet java = JavaSets.javaForRun(ctx.simsBase());
        SimContext sims = SimContext.forRig(toolsRoot == null ? ToolsRoot.resolve(null) : toolsRoot, rig)
                .withJavaDir(java.target());
        HOSTS.put(ctx, new Hosts(sims, List.of(), List.of(), Map.of()));

        Optional<List<SimEntry>> kept = supersede(ctx, step, entries, sims);
        if (kept.isEmpty()) {
            return false;
        }
        entries = kept.get();
        List<Fepsim.FepListener> listeners = fepListeners(ctx, step, entries, sims, chosenFeps, fakePos);
        if (listeners == null) {
            return false;
        }

        if (entries.stream().anyMatch(entry -> entry.runner() == com.verifone.cat.sims.Runner.JAVAW)) {
            step.log("JAVA  " + javaWhy(java, ctx.simsBase()), "INFO");
        }
        if (selected.contains(PETRO_SIMULATOR)) {
            // Before the spawn: on 2026-09-14 the EPS reached a freshly started Petro 2 s after it listened, before
            // step 4 could start watching (the Python's eps_log_mark). Step 4's pinpad link reads after this mark.
            Path log = petroLog.apply(sims);
            if (log != null) {
                ctx.epsLogMark(sizeOf(log));
            }
        }
        if (!fakePos && selected.contains(PETRO_SIMULATOR)) {
            // Before Petro starts: every pump state it pushes must be heard (PetroPorts).
            PetroPorts.hold(ctx, step);
            if (!PetroPorts.patsPortFreeForPumps(ctx, step, drivesPumps.apply(ctx))) {
                return false;
            }
        }
        stopWrongVariants(ctx, step, entries, sims, older);

        List<SimEntry> starting = List.copyOf(entries);
        Map<String, SimStatus> latest = new LinkedHashMap<>();
        List<String> reached = new ArrayList<>();
        Events events = new Events() {
            @Override
            public void status(SimStatus status) {
                latest.put(status.name(), status);
                // The Simulators board's row (the Python's SimulatorState from _on_status).
                ctx.emit(new CatEvent.SimulatorState(status.name(), status.state().label(), "supervised",
                        status.owner().label(), status.bind(), status.detail()));
                // A needs-attention row's detail is already the operator's line, which names it.
                boolean attention = status.state() == SimStatus.State.NEEDS_ATTENTION;
                String text = attention ? status.detail()
                        : status.state().label() + (status.detail().isEmpty() ? "" : " — " + status.detail());
                step.substep(status.name(), outcome(status.state()), text, 0.0);
                if (attention) {
                    step.log(text, "WARNING");
                } else {
                    String level = status.state() == SimStatus.State.FAILED ? "ERROR" : "INFO";
                    step.log(status.name() + ": " + text, level);
                }
                if (starting.stream().anyMatch(entry -> entry.name().equals(status.name()))
                        && !reached.contains(status.name())) {
                    reached.add(status.name());
                }
                step.progress(status.name(), reached.size(), starting.size());
            }

            @Override
            public void log(String name, String line) {
                step.log(line, "INFO");
            }

            @Override
            public void started(SimProcess process) {
                ctx.addSimulator(process);
            }
        };

        // Each spawned process is recorded as the supervisor reports it (Events.started), so a timed-out
        // pass still leaves its children owned by the run.
        List<SimProcess> procs = ctx.supervisor().startAll(starting, sims, events, START_TIMEOUT,
                new AtomicBoolean());
        // The final handle replaces the one Events.started recorded for the same process (the Python's
        // `ctx.sim_procs = procs`): recorded twice, a restart would stop one and leave the other.
        for (SimProcess proc : procs) {
            ctx.replaceSimulators(ctx.simulators().stream()
                    .filter(p -> p.process() != null && p.process() == proc.process()).toList(), proc);
        }

        List<String> failed = new ArrayList<>();
        List<String> attention = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (Map.Entry<String, SimStatus> row : latest.entrySet()) {
            switch (row.getValue().state()) {
                case FAILED -> failed.add(row.getKey());
                // One operator line per row, each naming its simulator (SimStatus.awaitingButtons).
                case NEEDS_ATTENTION -> attention.add(row.getValue().detail());
                default -> counts.merge(row.getValue().state().label(), 1, Integer::sum);
            }
        }
        if (!failed.isEmpty()) {
            return step.fail("did not start: " + String.join(", ", failed));
        }
        if (!attention.isEmpty()) {
            return step.fail(String.join("\n", attention));
        }
        Hosts hosts = hosts(ctx);
        for (Fepsim.Superseded gone : hosts.superseded()) {
            if (!awaitListener(ctx, step, sims, gone.label(), gone.describe(), gone.instance(), gone.port(),
                    gone.needs(), gone.host())) {
                return false;
            }
        }
        for (Fepsim.FepListener listener : hosts.listeners()) {
            if (!awaitListener(ctx, step, sims, listener.label(), listener.describe(), listener.instance(),
                    listener.port(), listener.needs(), listener.host())) {
                return false;
            }
        }
        markFepCaptures(ctx);
        StringBuilder detail = new StringBuilder();
        counts.forEach((label, count) -> detail.append(detail.length() == 0 ? "" : ", ")
                .append(count).append(' ').append(label));
        step.setDetail(detail.toString());
        return true;
    }

    // ----- the FEP hosts -----------------------------------------------------------------------------------------

    /**
     * Drops each row another row's FEP simulator serves, and readies that simulator for it (the Python's
     * {@code _supersede}). Before the host starts, any running superseded row is closed, matched by command line only
     * (by port it could be a previous run's Petro), and the instance is written so the host opens it at start-up.
     * Empty when the step failed.
     */
    private Optional<List<SimEntry>> supersede(RunContext ctx, StepEmitter step, List<SimEntry> entries,
            SimContext sims) {
        boolean keepLegacy = ctx.config().buypassHost();
        Fepsim.Split split = Fepsim.supersede(entries, sims, keepLegacy);
        for (String note : split.notes()) {
            step.log("HOST  " + note, "INFO");
        }
        if (keepLegacy) {
            retireInstances(ctx, step, split.keep(), sims);
        }
        for (Fepsim.Superseded gone : split.superseded()) {
            step.log("HOST  " + gone.describe() + "; " + gone.entry().name() + " is not started", "INFO");
            Map<String, Supervisor.StopRow> stopped = new TreeMap<>(ctx.supervisor().stopRunning(
                    List.of(gone.entry().withoutReady()), sims, Supervisor.STOP_WAIT));
            stopped.forEach((name, row) -> {
                if (row.outcome() != Supervisor.StopOutcome.ABSENT) {
                    step.log("STOP  " + name + " — " + label(row) + " " + row.detail() + " (it would hold "
                            + gone.port() + ")", "INFO");
                }
            });
            Path path;
            try {
                path = Fepsim.installInstance(sims.cwdFor(gone.host()), gone.instance(), gone.legacy());
            } catch (IOException | RuntimeException e) {
                step.fail(gone.instance() + ": " + describe(e));
                return Optional.empty();
            }
            step.log("HOST  " + gone.instance() + " written to " + path + ", auto-started", "INFO");
        }
        update(ctx, hosts -> new Hosts(hosts.sims(), split.superseded(), hosts.listeners(), hosts.marks()));
        return Optional.of(split.keep());
    }

    /**
     * {@code --buypass-host}: turns off the FEPSIM instance each kept row's suite would open on the row's own port. A
     * suite that had it on and is running holds that port, so it is closed here, and started again without the
     * listener (the Python's {@code _retire_instances}).
     */
    private static void retireInstances(RunContext ctx, StepEmitter step, List<SimEntry> entries, SimContext sims) {
        Map<String, SimEntry> byName = new LinkedHashMap<>();
        entries.forEach(entry -> byName.put(entry.name(), entry));
        for (SimEntry entry : entries) {
            SimEntry.SupersededBy spec = entry.supersededBy();
            SimEntry host = spec == null ? null : byName.get(spec.simulator());
            if (host == null) {
                continue;
            }
            Path path = Fepsim.retireInstance(sims.cwdFor(host), spec.instance());
            if (path == null) {
                continue;
            }
            step.log("HOST  " + spec.instance() + " turned off in " + path + ": " + entry.name() + " serves it",
                    "INFO");
            Map<String, Supervisor.StopRow> stopped = new TreeMap<>(ctx.supervisor().stopRunning(
                    List.of(host.withoutReady()), sims, Supervisor.STOP_WAIT));
            stopped.forEach((name, row) -> {
                if (row.outcome() != Supervisor.StopOutcome.ABSENT) {
                    step.log("STOP  " + name + " — " + label(row) + " " + row.detail() + " (it would hold the port)",
                            "INFO");
                }
            });
        }
    }

    /**
     * Writes the FEPSIM instance each chosen Secondary FEP names, so its suite opens it at start-up (the Python's
     * {@code _fep_listeners}). WEX-OTR and Comdata are answered by FEPSIM v2's own listeners, which exist only from v2:
     * an older suite fails the step saying which build to pick. Null when the step failed.
     */
    private static List<Fepsim.FepListener> fepListeners(RunContext ctx, StepEmitter step, List<SimEntry> entries,
            SimContext sims, List<SecondaryFep> chosen, boolean fakePos) {
        List<SecondaryFep> rows = chosen.stream().filter(row -> !row.fepsimInstance().isEmpty()).toList();
        if (rows.isEmpty() || fakePos) {
            return List.of();
        }
        Map<String, SimEntry> byName = new LinkedHashMap<>();
        entries.forEach(entry -> byName.put(entry.name(), entry));
        List<Fepsim.FepListener> listeners = new ArrayList<>();
        for (SecondaryFep row : rows) {
            SimEntry host = byName.get(row.simulator());
            if (host == null) {
                step.fail(row.name() + ": its host " + row.simulator() + " is not in this run");
                return null;
            }
            int found;
            try {
                found = Fepsim.generation(sims.jarFor(host).orElse(null));
            } catch (IllegalArgumentException e) {
                found = 0;
            }
            if (found < Fepsim.V2) {
                step.fail(row.name() + ": " + host.name() + "'s FEP simulator is "
                        + (found > 0 ? "v" + found : "absent") + "; " + row.fepsimInstance() + " needs v2 "
                        + "(Simulator Build: branch PETROCORE-142214, build 461 or later)");
                return null;
            }
            Path suite = sims.cwdFor(host);
            int port;
            Path path;
            try {
                port = Fepsim.instancePort(suite, row.fepsimInstance());
                path = Fepsim.installInstance(suite, row.fepsimInstance());
            } catch (IOException | RuntimeException e) {
                step.fail(row.name() + ": " + row.fepsimInstance() + ": " + describe(e));
                return null;
            }
            Fepsim.FepListener listener = new Fepsim.FepListener(row.name(), host, row.fepsimInstance(), port, found,
                    List.of(Fepsim.wiremockPort(suite)));
            listeners.add(listener);
            step.log("HOST  " + listener.describe() + "; " + row.fepsimInstance() + " written to " + path, "INFO");
        }
        update(ctx, hosts -> new Hosts(hosts.sims(), hosts.superseded(), listeners, hosts.marks()));
        return listeners;
    }

    /**
     * Waits, bounded, for a listener and every port it needs; fails the step if not (the Python's
     * {@code _await_superseding_listener}). A listener that accepts but whose answers come from a dead WireMock is not
     * ready: on 2026-10-12 v2's BUYPASS listener opened 41107 while the suite's HTTPSIM never bound 80 (a keystore race
     * at start-up), so one restart of a CAT-started host is the remedy. A suite this run adopted read its instances
     * folder before this run wrote the instance, so it is handed the instance over its admin API.
     */
    private boolean awaitListener(RunContext ctx, StepEmitter step, SimContext sims, String label, String describe,
            String instance, int port, List<Integer> needs, SimEntry host) {
        String address = sims.controllerHost().isEmpty() ? "localhost" : sims.controllerHost();
        List<Integer> ports = new ArrayList<>();
        ports.add(port);
        ports.addAll(needs);
        List<Integer> down = waitFor(address, ports);
        if (!down.isEmpty() && !down.contains(port)) {
            step.log("WARN  " + instance + " listens on " + port + " but " + address + ":" + down
                    + " does not accept — " + host.name() + "'s answers would fail; restarting it once", "WARNING");
            if (restartSimulator(ctx, step, host).isEmpty()) {
                down = waitFor(address, ports);
            }
        }
        boolean adopted = ctx.simulators().stream()
                .noneMatch(p -> p.entry().name().equals(host.name()) && p.spawned());
        if (!down.isEmpty() && adopted && down.contains(port)) {
            Path suite = sims.cwdFor(host);
            try {
                String how = registrar.register("http://" + address + ":" + Fepsim.adminPort(suite), suite, instance);
                step.log("HOST  " + instance + " " + how + " (" + host.name() + " was already running)", "INFO");
                down = waitFor(address, ports);
            } catch (IOException | RuntimeException e) {
                step.log("WARN  " + instance + ": not registered live: " + e.getMessage(), "WARNING");
            }
        }
        if (!down.isEmpty()) {
            return step.fail(instance + ": " + address + ":" + down + " not accepting"
                    + (adopted && down.contains(port)
                    ? " — " + host.name() + " was already running, so it never read the instance; stop it "
                            + "(Launch Simulators) and run again"
                    : " " + listenerWait.toSeconds() + "s after " + host.name() + " was ready"));
        }
        step.check(true, label, describe + ": listening, " + tuple(ports) + " accept", true);
        return true;
    }

    /** The ports of {@code ports} not accepting on {@code host} once {@link #listenerWait} is spent or all accept. */
    private List<Integer> waitFor(String host, List<Integer> ports) {
        long deadline = System.nanoTime() + listenerWait.toNanos();
        while (true) {
            List<Integer> down = new ArrayList<>();
            for (int port : ports) {
                if (!accepts.test(host, port)) {
                    down.add(port);
                }
            }
            if (down.isEmpty() || System.nanoTime() - deadline >= 0) {
                return down;
            }
            pause(listenerPoll);
        }
    }

    /** Each listener's capture file size now: the Tests step counts what follows ({@link #fepExchangeLines}). */
    private static void markFepCaptures(RunContext ctx) {
        Hosts hosts = hosts(ctx);
        Map<String, Long> marks = new LinkedHashMap<>();
        for (Fepsim.FepListener listener : hosts.listeners()) {
            marks.put(listener.instance(), Fepsim.captureSize(hosts.sims().cwdFor(listener.host()),
                    listener.instance()));
        }
        update(ctx, h -> new Hosts(h.sims(), h.superseded(), h.listeners(), marks));
    }

    /**
     * Closes a simulator left running in the other base's version, before adopt-or-start (the Python's
     * {@code _stop_wrong_variants}): LoySim 9.1 against a base 55.02 Commander (or 7 against 57) is never adopted, so
     * CAT would start the right one beside it. Only when the base was read: unread, the rows are base 57's by default,
     * and closing a LoySim 7 for that guess could close the right one.
     */
    private static void stopWrongVariants(RunContext ctx, StepEmitter step, List<SimEntry> entries, SimContext sims,
            boolean older) {
        String base = ctx.simsBase();
        if (base == null || base.isEmpty()) {
            return;
        }
        Map<String, Supervisor.StopRow> closed = ctx.supervisor().stopWrongVariants(entries,
                Registry.otherVariants(older), sims, Supervisor.STOP_WAIT);
        closed.forEach((name, row) -> {
            boolean failed = row.outcome() != Supervisor.StopOutcome.STOPPED;
            step.log(name + ": the other base's version was running, not " + base + "'s; "
                    + (failed ? "NOT closed" : "closed") + " (" + label(row) + ": " + row.detail() + ")",
                    failed ? "ERROR" : "WARNING");
        });
    }

    /**
     * Stops the CAT-started instance of {@code entry} and starts it again (the Python's {@code _restart_simulator}):
     * "" when it is ready again, else why not (the step's detail is set to it). An adopted one is never stopped (D9).
     */
    static String restartSimulator(RunContext ctx, StepEmitter step, SimEntry entry) {
        List<SimProcess> owned = ctx.simulators().stream()
                .filter(p -> p.entry().name().equals(entry.name()) && p.spawned()).toList();
        if (owned.isEmpty()) {
            String why = entry.name() + " was not started by CAT (adopted, or not running); restart it by hand and "
                    + "re-run — CAT stops only what it started (D9)";
            step.setDetail(why);
            return why;
        }
        step.log("restarting " + entry.name(), "INFO");
        Supervisor supervisor = ctx.supervisor();
        supervisor.stop(owned, Supervisor.STOP_WAIT);
        SimProcess fresh = supervisor.start(owned.get(0).entry(), runSims(ctx), Events.NONE, "", "", START_TIMEOUT);
        ctx.replaceSimulators(owned, fresh);
        if (fresh.status().state() != SimStatus.State.READY) {
            String why = entry.name() + " did not come back: " + fresh.status().state().label() + " — "
                    + fresh.status().detail();
            step.setDetail(why);
            return why;
        }
        step.log(entry.name() + ": ready again", "INFO");
        return "";
    }

    // ----- what the later steps read -----------------------------------------------------------------------------

    /** This run's FEP host decisions; none before step 3 ran. */
    static Hosts hosts(RunContext ctx) {
        Hosts hosts = HOSTS.get(ctx);
        if (hosts != null) {
            return hosts;
        }
        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
        return new Hosts(SimContext.forRig(rig).withJavaDir(JavaSets.javaForRun(ctx.simsBase()).target()),
                List.of(), List.of(), Map.of());
    }

    private static void update(RunContext ctx, java.util.function.UnaryOperator<Hosts> change) {
        synchronized (HOSTS) {
            HOSTS.put(ctx, change.apply(hosts(ctx)));
        }
    }

    /** The simulators' context step 3 used (tools root, controller, Java set, TLS port), for a later restart. */
    static SimContext runSims(RunContext ctx) {
        return hosts(ctx).sims();
    }

    /** True when a FEP simulator serves {@link #PAYMENT_HOST} this run: step 4's drift restart then has nothing to do. */
    static boolean paymentHostSuperseded(RunContext ctx) {
        return hosts(ctx).superseded().stream().anyMatch(gone -> gone.entry().name().equals(PAYMENT_HOST));
    }

    /**
     * The capture file {@code emv-keys --host-packets} reads when a FEP simulator serves {@link #PAYMENT_HOST} this
     * run; empty when BUYPASS Host's own {@code packetFile.txt} is the host log (the Python's {@code host_packet_args}).
     */
    static Optional<Path> hostPackets(RunContext ctx) {
        Hosts hosts = hosts(ctx);
        for (Fepsim.Superseded gone : hosts.superseded()) {
            if (gone.entry().name().equals(PAYMENT_HOST)) {
                return Optional.of(Fepsim.captureFile(hosts.sims().cwdFor(gone.host()), gone.instance()));
            }
        }
        return Optional.empty();
    }

    /** {@code ["--host-packets", <file>]} or none: {@link #hostPackets} as the Python's command-line words. */
    static List<String> hostPacketArgs(RunContext ctx) {
        return hostPackets(ctx).map(path -> List.of("--host-packets", path.toString())).orElse(List.of());
    }

    /**
     * FEPSIM v2's Buypass listener as PATS's BUYPASS packet and config actions see it, when it serves
     * {@link #PAYMENT_HOST} this run; else empty (the Python's {@code fepsim_buypass_env}, as an object).
     */
    static Optional<FepsimLegacy> fepsimBuypass(RunContext ctx) {
        Hosts hosts = hosts(ctx);
        for (Fepsim.Superseded gone : hosts.superseded()) {
            if (gone.entry().name().equals(PAYMENT_HOST)) {
                Path suite = hosts.sims().cwdFor(gone.host());
                return Optional.of(new FepsimLegacy("http://localhost:" + Fepsim.adminPort(suite), "BUYPASS",
                        gone.port(), Fepsim.legacyPacketsDir(suite, gone.instance())));
            }
        }
        return Optional.empty();
    }

    /** {@code {FepsimLegacy.ENV: json}} for the case runner's environment, or empty: the Python's env update. */
    static Map<String, String> fepsimBuypassEnv(RunContext ctx) {
        return fepsimBuypass(ctx).map(listener -> Map.of(FepsimLegacy.ENV, listener.toJson())).orElse(Map.of());
    }

    /**
     * One line per Secondary FEP listener: the requests the EPS sent it during the run (the Python's
     * {@code fep_exchange_lines}). The evidence that a FEP was exercised, not just that a sale passed: a sale on a card
     * outside the FEP's BIN range goes to Buypass and passes all the same. A line with a count of 0 starts
     * {@code WARN} and is the caller's to log at WARNING.
     */
    static List<String> fepExchangeLines(RunContext ctx) {
        Hosts hosts = hosts(ctx);
        List<String> lines = new ArrayList<>();
        for (Fepsim.FepListener listener : hosts.listeners()) {
            Map<String, Integer> counted = Fepsim.exchanges(hosts.sims().cwdFor(listener.host()), listener.instance(),
                    hosts.marks().getOrDefault(listener.instance(), 0L));
            int total = counted.values().stream().mapToInt(Integer::intValue).sum();
            List<String> kinds = new ArrayList<>();
            counted.forEach((kind, n) -> kinds.add(kind + " " + n));
            String prefix = total > 0 ? "FEP  " : "WARN ";
            lines.add(prefix + " " + listener.fep() + ": " + total + " request(s) to " + listener.instance()
                    + (kinds.isEmpty() ? " — no sale reached this FEP" : " (" + String.join(", ", kinds) + ")"));
        }
        return lines;
    }

    // ----- helpers ---------------------------------------------------------------------------------------------

    /** The default card server hook: a {@code pinpad: vats} rig run is unavailable until the VATS port binds one. */
    static boolean cardServerNotPorted(RunContext ctx, StepEmitter step) {
        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
        if (!ctx.config().fakePos() && rig != null && rig.pinpad() == Rig.Pinpad.VATS) {
            return step.unavailable("src/cat/launch/steps/simulators.py",
                    "the BTT card server for a pinpad: vats rig (petrotest.devices.vats.btt), which starts "
                            + "before the selection is read");
        }
        return true;
    }

    /** The Python's {@code java_for_rig} line, from the base step 1 read. */
    static String javaWhy(JavaSets.JavaSet java, String base) {
        boolean known = base != null && JavaSets.baseMajor(base).isPresent();
        return "Java 17 " + java.version() + " (" + java.target() + "): "
                + (known ? "the Commander base is " + base + " (v1.2 is for base " + JavaSets.V12_FROM_BASE
                        + " and later)" : "the Commander's base was not read");
    }

    private static boolean acceptsConnection(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** A bounded pause (no {@code Thread.sleep} in main code). */
    private static void pause(Duration duration) {
        try {
            new CountDownLatch(1).await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A Python tuple's text: {@code (41107, 80)}, {@code (8030,)}. */
    static String tuple(List<Integer> values) {
        List<String> text = values.stream().map(String::valueOf).toList();
        return "(" + String.join(", ", text) + (text.size() == 1 ? ",)" : ")");
    }

    private static String label(Supervisor.StopRow row) {
        return row.outcome().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String describe(Throwable e) {
        if (e instanceof java.nio.file.NoSuchFileException) {
            return "no such file: " + e.getMessage();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** Petro Simulator Suite's log from its registry row (the Python's {@code _pinpad_log}), or null without one. */
    static Path petroLog(SimContext sims) {
        Optional<SimEntry> entry = Registry.byName(PETRO_SIMULATOR);
        if (entry.isEmpty() || entry.get().log() == null || entry.get().log().isBlank()) {
            return null;
        }
        return sims.cwdFor(entry.get()).resolve(entry.get().log());
    }

    /** A file's size, 0 when it cannot be read (the Python's {@code _size_of}). */
    static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    /** The checklist outcome for a row, as the Python CAT's {@code SIM_ROW_OUTCOMES}. */
    static String outcome(SimStatus.State state) {
        return switch (state) {
            case STARTING -> SubstepOutcome.RUNNING;
            case READY, ADOPTED -> SubstepOutcome.PASSED;
            case FAILED -> SubstepOutcome.FAILED;
            case STARTED, NEEDS_ATTENTION -> SubstepOutcome.WARNED;
        };
    }
}
