package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.verifone.cat.devices.eps.CommanderLinkEpsProbe;
import com.verifone.cat.devices.forecourt.ForecourtTimeouts;
import com.verifone.cat.devices.forecourt.PetroPumpStatus;
import com.verifone.cat.devices.suspended.CommanderSuspends;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.rig.Rig;

/**
 * Builds the {@link RigContext} a preflight pass runs against from a loaded rig. The Python counterpart is
 * {@code petrotest.devices.context.build_rig_context}.
 *
 * <p>The POS status and the POS control are the register's pos-shim ({@link ShimPosStatus},
 * {@link ShimPosControl}). The control surface's cancel ladder and void are the same two actions recovery's
 * rungs 1 and 2 drive. The Commander-side sources are the Python's defaults ({@link Sources#live}):
 * <ul>
 *   <li>{@code eps_reachable}: {@link CommanderLinkEpsProbe}, the Commander's {@code netstat} over SSH with
 *       the rig's key; unavailable (ERROR) when {@code commander.ssh_key_path_env} does not resolve.</li>
 *   <li>{@code no_parked_transaction}: {@link CommanderSuspends}, the Day summary's {@code suspendUnsettled}
 *       over CGILink.</li>
 *   <li>{@code pumps_idle}: {@link PetroPumpStatus} (Petro Simulator Suite on the controller) on a
 *       {@code forecourt: petro} rig; none otherwise, so a rig
 *       with pumps and no forecourt reports ERROR.</li>
 * </ul>
 * All three are reads: {@code verify()} stays read-only. Nothing connects until a check asks.
 * The config surfaces have no Java client yet, so they are {@link Surfaces#unavailable}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RigContextFactory {

    /** The shim's per-request timeout; the same figure the web UI's shim client uses. */
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(5);

    private static final String CONFIG_REASON =
            "no CGILink command reads the active config folder (Python: config_status defaults to "
                    + "UnavailableConfigStatus, and PDKShim.active_config_folder raises DeviceUnavailable)";

    private RigContextFactory() {}

    /**
     * The forecourt bounds from the rig's {@code timeouts:} block. {@code http} is the rig's
     * {@code default_wait}, as the Python's {@code Timeouts.http} defaults to it.
     */
    static ForecourtTimeouts forecourtTimeouts(Rig rig) {
        Rig.Timeouts t = rig.timeouts();
        return new ForecourtTimeouts(t.fuelComplete(), t.promptWait(), t.defaultWait());
    }

    /**
     * The Commander- and forecourt-side sources a context reads. Each is injectable, as the Python's
     * {@code build_rig_context(eps=..., pump_status=..., parked_tickets=...)} keywords.
     *
     * @param eps           {@code eps_reachable}'s probe
     * @param parkedTickets {@code no_parked_transaction}'s Commander count, or null for the shim fallback
     * @param pumpStatus    {@code pumps_idle}'s source, or null when the rig names no forecourt
     */
    public record Sources(Surfaces.EpsProbe eps, Supplier<Surfaces.UnsettledSuspends> parkedTickets,
            Surfaces.PumpStatus pumpStatus) {

        /** The production sources for {@code rig}. Building them contacts nothing. */
        public static Sources live(Rig rig, Map<String, String> env) {
            // As the Python's PetroPumpStatus(rig.controller.host, timeouts=...): the suite runs on the controller.
            Surfaces.PumpStatus pumps = rig.forecourt() == Rig.Forecourt.PETRO
                    ? PetroPumpStatus.standard(rig.controller().host(), forecourtTimeouts(rig))
                    : null;
            return new Sources(CommanderLinkEpsProbe.fromRig(rig, env), CommanderSuspends.fromRig(rig), pumps);
        }
    }

    /**
     * @param rig       the loaded rig
     * @param register  the register whose shim is probed
     * @param shimPort  the shim's port, normally {@link PosShimClient#DEFAULT_SHIM_PORT}
     * @param env       the environment, for a cashier password or an SSH key held in an environment variable
     */
    public static RigContext build(Rig rig, Rig.Register register, int shimPort, Map<String, String> env) {
        return build(rig, register, shimPort, env, Sources.live(rig, env));
    }

    /**
     * {@link #build(Rig, Rig.Register, int, Map, Sources)}, with {@code eps_reachable}'s escalation to
     * {@code provision --force} ({@code provision.ProvisionForce.hook}) when {@code enableReprovision}: the Python's
     * {@code rig_context(enable_reprovision=True)}. Off by default, and deliberately: a preflight someone asked for
     * should not turn into a provisioning run unless they asked for that too.
     */
    public static RigContext build(Rig rig, Rig.Register register, int shimPort, Map<String, String> env,
            Sources sources, boolean enableReprovision) {
        RigContext context = build(rig, register, shimPort, env, sources);
        if (enableReprovision) {
            context.setReprovision(com.verifone.cat.provision.ProvisionForce.hook(rig, env));
        }
        return context;
    }

    /** {@link #build(Rig, Rig.Register, int, Map)} with the Commander and forecourt sources given. */
    public static RigContext build(Rig rig, Rig.Register register, int shimPort, Map<String, String> env,
            Sources sources) {
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
                sources.eps(),
                Surfaces.unavailable(Surfaces.ConfigStatus.class, "config status", CONFIG_REASON),
                Surfaces.unavailable(Surfaces.ConfigControl.class, "config control", CONFIG_REASON),
                expectations);
        context.setDefaultWait(rig.timeouts().defaultWait());
        context.setParkedTickets(sources.parkedTickets());
        context.setPumpStatus(sources.pumpStatus());

        // Each register's cash_drawer, from the rig file: FALSE declares none attached, null that the rig does not say.
        Map<String, Boolean> drawers = new LinkedHashMap<>();
        for (Rig.Register r : rig.registers()) {
            drawers.put(r.id(), r.cashDrawer());
        }
        context.setCashDrawers(drawers);
        return context;
    }
}
