package com.verifone.cat.launch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import com.verifone.cat.devices.vats.Btt;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.sims.ToolsRoot;

/**
 * Step 3's first act on a {@code pinpad: vats} rig: BTT's card server up, or the run stops. A port of the Python CAT's
 * {@code cat.launch.steps.simulators._start_card_server}.
 *
 * <p>Not a simulator registry row: BTT is a Fime install, not a staged simulator, and which version runs is the newest
 * installed (or the rig's {@code vats.btt_version}). {@link Btt#startCardServer} has the order it tries. Independent of
 * the simulator picker, because a physical pinpad's cards need it whatever else runs, so it runs before the selection
 * is read. Nothing on any other rig, and nothing under {@code --fake-pos} (no rig hardware in play).
 *
 * <p>The BTT lines go to the step's log under the logger {@code cat.btt}, as the Python's. One checklist row,
 * {@code BTT card server}, PASS or FAIL with {@code <route>: <detail>}; a failure fails the step with
 * {@code BTT card server: <detail>}.
 *
 * <p>Wired as {@code StartSimulatorsStep.cardServer}'s default, which step 3 runs before the selection is read.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class VatsCardServerStart {

    /** The whole of BTT's start: launch, settle, card server up. 18.5 s on 2026-10-06; the Python's 120 s. */
    public static final Duration BTT_START_TIMEOUT = Btt.DEFAULT_START_TIMEOUT;

    /** The checklist row's label. */
    public static final String LABEL = "BTT card server";

    /** Starts BTT's card server for a rig's {@code vats:} block. */
    @FunctionalInterface
    public interface Starter {
        Btt.CardServerStart start(Rig.Vats config, Duration timeout, Consumer<String> log);
    }

    /** This controller's BTT: the real install folders, the menu agent under {@code ~/.cat/java/btt-agent}. */
    public static final Starter REAL = (config, timeout, log) -> Btt
            .forThisMachine(Path.of(System.getProperty("user.home")), ToolsRoot.resolve(null))
            .startCardServer(config.cardServerPort(), config.interfaceKind(), config.automationPort(),
                    config.bttVersion(), timeout, log);

    private VatsCardServerStart() {}

    /** {@link #start(RunContext, StepEmitter, Starter)} with this controller's BTT. */
    public static boolean start(RunContext ctx, StepEmitter step) {
        return start(ctx, step, REAL);
    }

    /** True when the card server is up, or when this run needs none; false when the step has failed on it. */
    public static boolean start(RunContext ctx, StepEmitter step, Starter starter) {
        Rig rig = ctx.rig() == null ? null : ctx.rig().rig();
        if (rig == null || rig.pinpad() != Rig.Pinpad.VATS || rig.vats() == null) {
            return true;
        }
        if (ctx.config().fakePos()) { // no rig hardware in play: nothing for a card to reach
            return true;
        }
        Consumer<String> log = line -> ctx.emit(new CatEvent.LogLine("INFO", "cat.btt", line, step.step()));
        Btt.CardServerStart started = starter.start(rig.vats(), BTT_START_TIMEOUT, log);
        step.check(started.ok(), LABEL, started.route() + ": " + started.detail(), true);
        if (!started.ok()) {
            return step.fail(LABEL + ": " + started.detail());
        }
        return true;
    }
}
