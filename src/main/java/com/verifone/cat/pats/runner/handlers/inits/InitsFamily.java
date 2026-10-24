package com.verifone.cat.pats.runner.handlers.inits;

import com.verifone.cat.devices.commander.PdkShim;
import com.verifone.cat.pats.runner.ActionRegistry;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.FixtureName;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;

import java.util.Locale;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The init family: {@code CONTROL_FUNCTION} (PATS's {@code InitilizationManager.performInitialization}, sent
 * as the Python's {@code _control_function} sends it), and the before-action mark that pays PATS's 20 s after
 * an init's reply before the next action that is not a SLEEP waiting on it.
 *
 * <p>Registered by {@link #register(ActionRegistry, InitsContext)}. The SLEEP handler is not this family's:
 * the POS family's {@code SLEEP} calls {@link InitsState#afterInit} and {@link InitsState#beforeInit} on the
 * same {@link InitsState}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only. The Commander's init is driven through
 * {@link PdkShim}, which is itself unverified on a rig.
 */
public final class InitsFamily {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    private InitsFamily() {
    }

    /**
     * Registers {@code CONTROL_FUNCTION} and the reply-pause before-action hook.
     *
     * @throws IllegalStateException when a name is already registered (see {@link ActionRegistry#register})
     */
    public static void register(ActionRegistry registry, InitsContext context) {
        Objects.requireNonNull(registry, "registry");
        InitsState state = context.state();
        registry.register("CONTROL_FUNCTION", (run, action) -> controlFunction(state, run, action));
        registry.beforeAction((run, action) -> {
            if (!"SLEEP".equals(action.name().toUpperCase(Locale.ROOT))) {
                state.replyPause(run.kase().key());
            }
        });
    }

    /**
     * {@code CONTROL_FUNCTION}: {@code performInitialization}, attempt for attempt. The 20 s after the reply is
     * left out when the init's signal can end it instead ({@code pauseAfterReply = false}); the state then
     * waits on Petro's log in the actions after, as {@link InitsState#replyPause} does.
     */
    static void controlFunction(InitsState state, CaseRun run, RunnableAction action) throws Exception {
        String init = require(action, "INIT_TYPE");
        long mark = state.petroMark();
        boolean watched = mark >= 0 && !run.keepSleeps() && InitsState.knows(init);
        PdkShim shim = run.fixture(FixtureName.COMMANDER_CONFIG, PdkShim.class);
        shim.performInitialization(init, null, !watched);
        state.sent(init, watched ? mark : -1L);
        LOG.debug("{}: {} sent, watched={}", run.kase().key(), init, watched);
    }

    private static String require(RunnableAction action, String name) {
        String value = action.raw(name);
        if (value == null) {
            throw new PatsStepError(action.describe() + " has no " + name + " field (PATS would throw here)");
        }
        return value;
    }
}
