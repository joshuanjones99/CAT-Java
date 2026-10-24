package com.verifone.cat.preflight;

import java.util.Map;

import com.verifone.cat.posshim.PosShimClient;

/**
 * {@link Surfaces.PosControl} over the register's pos-shim. Ported from the control half of the
 * Python CAT's {@code petrotest.devices.pos_shim.PosShimClient}.
 *
 * <p>Two methods have a source: the cancel/escape ladder and the void of the open ticket. They are
 * the same two actions recovery's rungs 1 and 2 use, so {@code pos_idle}'s repair and the ladder
 * drive the POS the same way. Each returns the shim's own re-read of the screen, so a key the POS
 * ignored reads as {@code false}.
 *
 * <p>The rest have no source on the pushed surface. The shim answers 501 for them, and this class
 * asks the shim rather than deciding locally, as the Python does: a route that starts working is
 * then picked up without an edit here. Each one raises {@code DeviceUnavailable}, which preflight
 * records as ERROR (CLAUDE.md hard rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Nothing here has pressed a key on a register.
 */
public final class ShimPosControl implements Surfaces.PosControl {

    private final PosShimClient shim;

    public ShimPosControl(PosShimClient shim) {
        this.shim = shim;
    }

    @Override
    public boolean reopenPrinterPort() {
        shim.unsupported("POST", "/pos/control/printer/reopen", Map.of());
        return false;
    }

    @Override
    public boolean resetSerialConnection() {
        shim.unsupported("POST", "/pos/control/serial/reset", Map.of());
        return false;
    }

    @Override
    public boolean cancelLadder() {
        return shim.cancelLadder();
    }

    @Override
    public boolean voidOpenTicket() {
        return shim.voidOpenTicket();
    }

    @Override
    public boolean recallAndVoid(String ticketId) {
        shim.unsupported("POST", "/pos/control/ticket/recall-void", Map.of("ticket_id", ticketId));
        return false;
    }

    @Override
    public boolean closeDrawer() {
        shim.unsupported("POST", "/pos/control/drawer/close", Map.of());
        return false;
    }

    /**
     * Sign-on needs digit keys, and the digit-to-code table is not derivable from the agent source
     * (pos_shim.py {@code sign_on}). The password is not sent to a route that cannot use it.
     */
    @Override
    public boolean signOn(String cashier, String password) {
        shim.unsupported("POST", "/pos/control/cashier/sign-on", Map.of("cashier_id", cashier));
        return false;
    }
}
