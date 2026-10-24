package com.verifone.cat.preflight;

import java.util.List;

/**
 * The default check set. Ported from {@code petrotest/preflight/checks/__init__.py}, which lists
 * nine checks of DESIGN.md 6.2's ten (the tenth, {@code simhub_healthy}, went with simhub).
 *
 * <p>Wired here: the five POS checks, in table order. Left behind, with the Python module each
 * needs:
 * <ul>
 *   <li>{@code pumps_idle}: {@code petrotest/preflight/checks/forecourt.py}, reads Petro Simulator
 *       Suite ({@code devices/petro_forecourt.py}). No Java forecourt client yet.</li>
 *   <li>{@code eps_reachable}: {@code checks/services.py}, reads the Commander's sockets over SSH
 *       ({@code devices/eps.py}). No Java SSH transport yet.</li>
 *   <li>{@code config_loaded}, {@code build_matches}: {@code checks/config.py}, reads the Commander's
 *       CGILink ({@code devices/config_shim.py}) and {@code suiteinfo.dat}. The Commander client is
 *       blocked (HANDOFF.md, wave 1).</li>
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

    private DefaultChecks() {}

    /** A fresh registry holding the wired checks, in table order. */
    public static CheckRegistry defaults() {
        return new CheckRegistry(List.of(
                new PosChecks.PrinterConnected(),
                new PosChecks.PosIdle(),
                new PosChecks.NoParkedTransaction(),
                new PosChecks.DrawerClosed(),
                new PosChecks.CashierSignedOn()));
    }
}
