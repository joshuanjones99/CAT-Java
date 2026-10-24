package com.verifone.cat.preflight;

import java.util.List;

/**
 * The default check set. Ported from {@code petrotest/preflight/checks/__init__.py}, which lists
 * nine checks of DESIGN.md 6.2's ten (the tenth, {@code simhub_healthy}, went with simhub).
 *
 * <p>Wired here, in table order: the five POS checks, {@code pumps_idle} ({@link ForecourtChecks},
 * reading {@link RigContext#pumpStatus()}) and {@code eps_reachable} ({@link ServiceChecks}, reading
 * the Commander's sockets over SSH through {@code devices.eps.CommanderLinkEpsProbe}). Left behind:
 * <ul>
 *   <li>{@code config_loaded}, {@code build_matches}: {@code checks/config.py}. Neither needs the
 *       CGILink client, which is ported ({@code devices.commander.PdkShim}). In the Python,
 *       {@code config_loaded} reads {@code RigContext.config_status}, which defaults to the
 *       unavailable stub because no CGILink command for the active folder is known
 *       ({@code PDKShim.active_config_folder} raises {@code DeviceUnavailable} too), and
 *       {@code build_matches} reads {@code suiteinfo.dat} over SSH (its HANDOFF.md §1.87).
 *       The checks themselves are not ported yet.</li>
 * </ul>
 * {@code checks/register.py} ({@code browser_override_safe}) is not registered by default in Python
 * either; it is left behind with the browser-override work.
 */
public final class DefaultChecks {

    /** The POS checks in DESIGN.md 6.2's table order. */
    public static final List<String> POS_CHECK_ORDER = List.of(
            "printer_connected",
            "pos_idle",
            "no_parked_transaction",
            "drawer_closed",
            "cashier_signed_on");

    /** Every wired check, in DESIGN.md 6.2's table order (the Python's {@code CHECK_ORDER}, less rows 9-10). */
    public static final List<String> CHECK_ORDER = List.of(
            "printer_connected",
            "pos_idle",
            "no_parked_transaction",
            "drawer_closed",
            "cashier_signed_on",
            "pumps_idle",
            "eps_reachable");

    private DefaultChecks() {}

    /** A fresh registry holding the wired checks, in table order. */
    public static CheckRegistry defaults() {
        return new CheckRegistry(List.of(
                new PosChecks.PrinterConnected(),
                new PosChecks.PosIdle(),
                new PosChecks.NoParkedTransaction(),
                new PosChecks.DrawerClosed(),
                new PosChecks.CashierSignedOn(),
                new ForecourtChecks.PumpsIdle(),
                new ServiceChecks.EpsReachable()));
    }
}
