package com.verifone.cat.preflight;

import java.util.List;
import java.util.Optional;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.posshim.ShimReplies.Screen;

/**
 * {@link Surfaces.PosStatus} over the register's pos-shim. Derives what the shim's pushed
 * {@code /pos/screen} carries (idle, transaction_active) and reports every other field as
 * {@link DeviceUnavailable}, because the shim does not push it.
 *
 * <p>Python's {@code petrotest.devices.pos_shim} reads separate {@code /pos/idle} and
 * {@code /pos/transaction} routes. The Java {@link PosShimClient} has no such methods and this
 * port adds none, so both are read from one {@code screen()} call (the same snapshot, one round trip
 * fewer). A shim with no pushed state (state_present false) is a shim the POS is not talking to:
 * {@link DeviceUnreachable}, never a pass. Note that such a reply still carries {@code idle: true}.
 *
 * <p>Not on the pushed surface, so {@link DeviceUnavailable}: the printer port (pos-shim answers 501
 * "unsupported"), parked transactions (the Commander's Day summary has them, {@code petrotest/devices/suspended.py}),
 * the drawer (the Python RMI status, {@code petrotest/devices/pos_shim.py}), the signed-on cashier
 * ({@code devices/context.py} live DOM) and the build version ({@code devices/suiteinfo.py}).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The screen reads were checked against the lab register
 * on 2026-10-24 through {@link PosShimClient}; the mapping above is this class's own.
 */
public final class ShimPosStatus implements Surfaces.PosStatus {

    private static final String REGISTER_SURFACE = "POS at the register (via the shim)";

    private final PosShimClient shim;

    public ShimPosStatus(PosShimClient shim) {
        this.shim = shim;
    }

    @Override
    public boolean printerPortOpen() {
        throw new DeviceUnavailable("printer port", "not on the pushed panel surface: pos-shim answers 501 "
                + "(unsupported) for it, and its receipt path says nothing about whether the port is open");
    }

    @Override
    public boolean transactionActive() {
        Boolean active = pushedScreen().transactionActive();
        if (active == null) {
            throw new DeviceUnavailable("POS shim /pos/screen transaction_active",
                    "the POS did not say whether a transaction is active");
        }
        return active;
    }

    @Override
    public boolean idleScreenPresent() {
        Boolean idle = pushedScreen().idle();
        if (idle == null) {
            throw new DeviceUnavailable("POS shim /pos/screen idle", "the POS did not say whether the idle screen is up");
        }
        return idle;
    }

    @Override
    public List<Surfaces.ParkedTransaction> parkedTransactions() {
        throw new DeviceUnavailable("parked transactions",
                "not on the pushed panel surface; the Commander's Day summary has them (devices/suspended.py)");
    }

    @Override
    public boolean drawerOpen() {
        throw new DeviceUnavailable("cash drawer", "not on the pushed panel surface (the Python RMI status reads it)");
    }

    @Override
    public boolean drawerPhysicallyBlocked() {
        throw new DeviceUnavailable("cash drawer", "not on the pushed panel surface (the Python RMI status reads it)");
    }

    @Override
    public Optional<String> cashierSignedOn() {
        throw new DeviceUnavailable("signed-on cashier",
                "not on the pushed panel surface; the register's DOM shows whether one is signed on, not who");
    }

    @Override
    public String buildVersion() {
        throw new DeviceUnavailable("POS build version", "not on the pushed panel surface (suiteinfo reads the Commander's build)");
    }

    /** The shim's pushed screen, refused when the POS has not pushed any state yet. */
    private Screen pushedScreen() {
        Screen screen = shim.screen();
        if (!screen.statePresent()) {
            throw new DeviceUnreachable(REGISTER_SURFACE, "the shim holds no state the POS has pushed yet");
        }
        return screen;
    }
}
