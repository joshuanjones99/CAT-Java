package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.rig.Rig;

/**
 * Builds the {@link RigContext} a preflight pass runs against from a loaded rig. The Python
 * counterpart is {@code petrotest.devices.build_rig_context}.
 *
 * <p>The POS status and the POS control are the register's pos-shim ({@link ShimPosStatus},
 * {@link ShimPosControl}). The control surface's cancel ladder and void are the same two actions
 * recovery's rungs 1 and 2 drive. The EPS, the config surfaces and the forecourt have no Java client yet, so they are
 * {@link Surfaces#unavailable} or null, and the checks that need them report ERROR rather than a
 * guess. Each gap names its Python module in {@link DefaultChecks}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RigContextFactory {

    /** The shim's per-request timeout; the same figure the web UI's shim client uses. */
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(5);

    private static final String EPS_REASON = "the EPS probe is not ported yet (Python: petrotest/devices/eps.py, over SSH)";
    private static final String CONFIG_REASON =
            "the config surfaces are not ported yet (Python: petrotest/devices/config_shim.py, Commander CGILink)";

    private RigContextFactory() {}

    /**
     * @param rig       the loaded rig
     * @param register  the register whose shim is probed
     * @param shimPort  the shim's port, normally {@link PosShimClient#DEFAULT_SHIM_PORT}
     * @param env       the environment, for a cashier password held in an environment variable
     */
    public static RigContext build(Rig rig, Rig.Register register, int shimPort, Map<String, String> env) {
        PosShimClient shim = new PosShimClient(register.host(), shimPort, null, SHIM_TIMEOUT);
        Rig.Cashier cashier = rig.cashier();
        Expectations expectations = new Expectations(
                rig.build() == null ? "" : rig.build().expectedVersion(),
                "",
                cashier == null ? "" : cashier.id(),
                cashier == null ? "" : cashier.resolvePassword(env),
                rig.pumps());
        RigContext context = new RigContext(
                rig.name(),
                new ShimPosStatus(shim),
                new ShimPosControl(shim),
                Surfaces.unavailable(Surfaces.EpsProbe.class, "EPS probe", EPS_REASON),
                Surfaces.unavailable(Surfaces.ConfigStatus.class, "config status", CONFIG_REASON),
                Surfaces.unavailable(Surfaces.ConfigControl.class, "config control", CONFIG_REASON),
                expectations);

        // Each register's cash_drawer, from the rig file: FALSE declares none attached, null that the rig does not say.
        Map<String, Boolean> drawers = new LinkedHashMap<>();
        for (Rig.Register r : rig.registers()) {
            drawers.put(r.id(), r.cashDrawer());
        }
        context.setCashDrawers(drawers);
        return context;
    }
}
