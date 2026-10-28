package com.verifone.cat.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.Events;
import com.verifone.cat.sims.Registry;
import com.verifone.cat.sims.SimContext;
import com.verifone.cat.sims.SimEntry;
import com.verifone.cat.sims.SimProcess;
import com.verifone.cat.sims.SimStatus;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Step 3, Start Simulators: adopt each selected simulator that is up, start the rest, and report
 * every row. The Python CAT's {@code cat.launch.steps.simulators}, through {@code sims.Supervisor}.
 *
 * <p>D9 is the supervisor's contract: adopt if up, start if not, stop only what this run started. The
 * step's own rule on top of it: a row the supervisor reports {@code failed} or {@code needs attention}
 * fails the step, and a row that is not ready is never a pass. The Python CAT lets a row in
 * {@code needs attention} through as a warning; CAT-Java does not, because a run would then go on
 * against a simulator nobody has confirmed is up. The rows that need their Swing buttons pressed are
 * {@code ready} once the supervisor has pressed them through the Java Access Bridge, and
 * {@code needs attention} (so the run stops here) when it could not.
 *
 * <p>Petro Simulator Suite on a rig: before anything starts, Petro's pump-state ports are held for the
 * run ({@link PetroPorts#hold}) and the step fails when another process holds PATS's 1072 and the run
 * drives pumps ({@link PetroPorts#patsPortFreeForPumps}). Deviation: the run's cases are collected in
 * step 5 here, not before step 1 as the Python's suites are, so the production step does not know
 * whether they drive pumps and goes on with a WARNING, as the Python does for unknown suites; step 1's
 * port guard is what stops such a run. The Python's wrong-variant stop ({@code _stop_wrong_variants})
 * acts only once the Commander's base is read, which CAT-Java does not do, so it is a no-op there too
 * and is not carried.
 *
 * <p>Not carried, so a rig run that needs it is unavailable before anything is started: the BTT card
 * server for a {@code pinpad: vats} rig. A fake-POS run needs none of the above. The rows and the Java 17
 * set follow the Commander base step 1 read ({@link RunContext#simsBase}): v1.1 and the older rows below 57.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the supervisor has never run against the real simulators from this
 * port (HANDOFF.md §5). Its tests use a fake process layer and fake simulators.
 */
final class StartSimulatorsStep implements LaunchStep {

    /** The Petro Simulator Suite's registry name: the forecourt and ViperPay pinpad simulator. */
    static final String PETRO_SIMULATOR = "Petro Simulator Suite";

    /** Per-simulator start budget: the Python CAT's {@code SIM_START_TIMEOUT}. */
    static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    /** Whether the run's cases drive a pump, or empty when that is not known yet (the Python's {@code _suites_drive_pumps}). */
    private final Function<RunContext, Optional<Boolean>> drivesPumps;

    /** Where Petro Simulator Suite logs, for the EPS mark; null when it names no log. */
    private final Function<SimContext, Path> petroLog;

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
        if (!fakePos && rig != null && rig.pinpad() == Rig.Pinpad.VATS) {
            return step.unavailable("src/cat/launch/steps/simulators.py",
                    "the BTT card server for a pinpad: vats rig (petrotest.devices.vats.btt), which starts "
                            + "before the selection is read");
        }

        List<String> selected = ctx.config().simulators();
        if (selected.isEmpty()) {
            step.skip("simulators", "the run selected no simulators");
            return step.skipStep("no simulators selected — nothing started");
        }
        // The Commander base step 1 read picks the Java 17 set (rig owner, 2026-10-09): v1.1 below base 57.
        SimContext sims = new SimContext(ToolsRoot.resolve(null), rig == null ? "" : rig.controller().host(),
                com.verifone.cat.sims.base.JavaSets.javaForRun(ctx.simsBase()).target(), null, null);
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

        List<SimEntry> entries = new ArrayList<>();
        for (SimEntry entry : Registry.launchable(com.verifone.cat.sims.base.JavaSets.olderBase(ctx.simsBase()))) {
            if (selected.contains(entry.name())) {
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

        Map<String, SimStatus> latest = new LinkedHashMap<>();
        List<String> reached = new ArrayList<>();
        Events events = new Events() {
            @Override
            public void status(SimStatus status) {
                latest.put(status.name(), status);
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
                if (entries.stream().anyMatch(entry -> entry.name().equals(status.name()))
                        && !reached.contains(status.name())) {
                    reached.add(status.name());
                }
                step.progress(status.name(), reached.size(), entries.size());
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
        List<SimProcess> procs = ctx.supervisor().startAll(entries, sims, events, START_TIMEOUT,
                new AtomicBoolean());
        procs.forEach(ctx::addSimulator);

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
        StringBuilder detail = new StringBuilder();
        counts.forEach((label, count) -> detail.append(detail.length() == 0 ? "" : ", ")
                .append(count).append(' ').append(label));
        step.setDetail(detail.toString());
        return true;
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
