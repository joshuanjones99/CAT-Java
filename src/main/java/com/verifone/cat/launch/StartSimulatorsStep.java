package com.verifone.cat.launch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>Not carried, so a rig run that needs one of them is unavailable before anything is started:
 * the BTT card server for a {@code pinpad: vats} rig, and for Petro Simulator Suite the port holds,
 * the wrong-variant stop and the pump-port check. A fake-POS run needs none of them. The Java 17 set
 * is the default {@code Java17} (base 57 and later), since the Python's base-picking is not carried.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the supervisor has never run against the real simulators from this
 * port (HANDOFF.md §5). Its tests use a fake process layer and fake simulators.
 */
final class StartSimulatorsStep implements LaunchStep {

    /** The Petro Simulator Suite's registry name: the forecourt and ViperPay pinpad simulator. */
    static final String PETRO_SIMULATOR = "Petro Simulator Suite";

    /** Per-simulator start budget: the Python CAT's {@code SIM_START_TIMEOUT}. */
    static final Duration START_TIMEOUT = Duration.ofSeconds(60);

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
        if (!fakePos && selected.contains(PETRO_SIMULATOR)) {
            return step.unavailable("src/cat/launch/steps/simulators.py",
                    "the Petro port holds, the wrong-variant stop and the pump-port check "
                            + "(_hold_petro_ports, _stop_wrong_variants, _pats_port_free_for_pumps), which "
                            + "Petro Simulator Suite needs on a rig before it starts");
        }

        List<SimEntry> entries = new ArrayList<>();
        for (SimEntry entry : Registry.launchable(false)) {
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

        SimContext sims = SimContext.of(ToolsRoot.resolve(null), rig == null ? "" : rig.controller().host());
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
