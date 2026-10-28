package com.verifone.cat.recovery;

import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.RigContext;
import com.verifone.cat.preflight.RigContextFactory;
import com.verifone.cat.rig.Rig;

/**
 * The recovery target a rig run's ladder walks, built from the rig file: the Python's
 * {@code petrotest/recovery/plugin.py::_recovery_ladder_session}, which builds it once per session when
 * {@code --rig} was passed.
 *
 * <ul>
 *   <li>The {@link RigContext} rung 4's preflight runs against is {@link RigContextFactory#build} over the primary
 *       register (the Python's {@code build_rig_context(rig)}), its {@code screen_idle} and {@code signed_on} the
 *       register's DOM ({@link LivePosScreen}), as the Python's {@code live_pos_idle} and {@code live_signed_on}.</li>
 *   <li>Rungs 1 and 2 and the testability probe: the context's pos-shim surfaces plus the {@link LivePosScreen}
 *       ({@link LiveRecoveryTarget}).</li>
 *   <li>Rung 6 logs the drain at ERROR, then tells {@code drain}; the caller stops the run (the Python's
 *       {@code session.shouldstop}).</li>
 * </ul>
 * Building it connects to nothing; each rung connects when it runs.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RigRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(RigRecovery.class);

    private RigRecovery() {
    }

    /**
     * @param rig     the loaded rig
     * @param env     the environment, for the cashier password and SSH key the preflight checks resolve
     * @param summary where rung 4's repairs are recorded, or {@code null}
     * @param drain   told the drain message at rung 6, or {@code null}
     */
    public static LiveRecoveryTarget forRig(Rig rig, Map<String, String> env, RunSummary summary,
            Consumer<String> drain) {
        RigContext context = RigContextFactory.build(rig, rig.primaryRegister(), PosShimClient.DEFAULT_SHIM_PORT, env);
        return forRig(rig.name(), context, LivePosScreen.forRig(rig), summary, drain);
    }

    /** {@link #forRig(Rig, Map, RunSummary, Consumer)} over a given context and screen. */
    static LiveRecoveryTarget forRig(String rigName, RigContext context, PosScreen screen, RunSummary summary,
            Consumer<String> drain) {
        context.setScreenIdle(screen::idle);
        context.setSignedOn(screen::signedOn);
        Consumer<String> hook = reason -> {
            LOG.error("rig {} drained by recovery rung 6: {}", rigName, reason);
            if (drain != null) {
                drain.accept(reason);
            }
        };
        return new LiveRecoveryTarget(rigName, context, context.posStatus(), context.posControl(), screen, summary,
                hook);
    }
}
