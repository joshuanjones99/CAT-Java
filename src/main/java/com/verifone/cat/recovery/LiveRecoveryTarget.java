package com.verifone.cat.recovery;

import java.util.function.Consumer;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.preflight.DefaultChecks;
import com.verifone.cat.preflight.PreflightReport;
import com.verifone.cat.preflight.PreflightRunner;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.preflight.RigContext;
import com.verifone.cat.preflight.Surfaces;

/**
 * A {@link RecoveryTarget} backed by the subsystems this port has. Ported from the Python
 * {@code petrotest.recovery.wiring.LiveRecoveryTarget} and the rung half of
 * {@code petrotest.recovery.plugin}.
 *
 * <ul>
 *   <li><b>SOFT</b> (rung 1): the shim's cancel ladder. The Python also closes CSR menus over CDP
 *       ({@code petrotest.pos.driver}, not ported), so this rung is the shim half only. TODO: port
 *       the CDP half from {@code petrotest/recovery/plugin.py} {@code _live_soft_cancel}. The rung
 *       counts as performed once the shim has been asked, and the rig decides with
 *       {@link #isTestable()}; a shim that did not clear the screen is not treated as success.</li>
 *   <li><b>VOID</b> (rung 2): the shim's void of the open ticket. The Python tries the sales screen's
 *       Void Ticket button over CDP first and falls back to the shim (not ported). TODO: port the
 *       CDP half from {@code _live_void}, including the pinpad abort for {@code pinpad: vats} rigs.</li>
 *   <li><b>RESET</b> (rung 3): unavailable. simhub was its only reset and was removed (HANDOFF.md
 *       1.120), so the ladder escalates past it, as the Python does on every rig.</li>
 *   <li><b>PREFLIGHT</b> (rung 4): {@link PreflightRunner} with repair, mid-run, as the Python's
 *       {@code run_preflight}. Each repair it made is recorded in the run summary.</li>
 *   <li><b>REPROVISION</b> (rung 5): unavailable. TODO: port {@code petrotest/provision/} (provision
 *       --force over SSH, DESIGN.md 6.1) and wait for the restarted POS to draw
 *       ({@code wiring.POS_RESTART_WAIT}) before preflight reads the register.</li>
 *   <li><b>DRAIN</b> (rung 6): the drain hook, if one was given.</li>
 * </ul>
 *
 * <p>An unavailable rung throws {@link DeviceUnavailable}, which the ladder records as a rung that
 * did not run. It never reports success (CLAUDE.md hard rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LiveRecoveryTarget implements RecoveryTarget {

    private final String rigName;
    private final RigContext rig;
    private final Surfaces.PosStatus status;
    private final Surfaces.PosControl control;
    private final RunSummary summary;
    private final Consumer<String> drainHook;

    /**
     * @param rig       the preflight view of the rig, used by rung 4
     * @param status    the POS status surface, read by the testability probe
     * @param control   the POS control surface, the same one preflight's repairs use
     * @param summary   where rung 4's repairs are recorded, or {@code null}
     * @param drainHook told the drain message at rung 6, or {@code null}
     */
    public LiveRecoveryTarget(String rigName, RigContext rig, Surfaces.PosStatus status,
            Surfaces.PosControl control, RunSummary summary, Consumer<String> drainHook) {
        this.rigName = rigName;
        this.rig = rig;
        this.status = status;
        this.control = control;
        this.summary = summary;
        this.drainHook = drainHook;
    }

    @Override
    public String rigName() {
        return rigName;
    }

    /**
     * The idle sales screen with no open ticket. TODO: the Python also accepts the login screen
     * ({@code live_at_login}); this port has no signed-on probe, so a register at its login screen is
     * not testable here.
     */
    @Override
    public boolean isTestable() {
        return !status.transactionActive() && status.idleScreenPresent();
    }

    @Override
    public boolean softCancel() {
        // The shim's success is its own re-read of the screen, and may be stale (the Python's note at
        // _live_soft_cancel). The performed rung is judged by isTestable(), not by this answer.
        control.cancelLadder();
        return true;
    }

    @Override
    public boolean voidOpenTicket() {
        return control.voidOpenTicket();
    }

    @Override
    public boolean resetState() {
        throw new DeviceUnavailable("recovery rung 3's device reset",
                "simhub's POST /reset was the only one, and simhub was removed (HANDOFF.md 1.120); "
                        + "Python: petrotest/recovery/plugin.py _no_device_reset");
    }

    @Override
    public boolean runPreflight() {
        PreflightReport report = PreflightRunner.run(rig, DefaultChecks.defaults().checks(),
                new PreflightRunner.RunOptions(true, null, true));
        if (summary != null) {
            for (Result repair : report.repairs()) {
                summary.recordRepair(rigName, repair.check(), repair.outcome().value(), repair.detail(),
                        repair.elapsed());
            }
        }
        return report.testable();
    }

    @Override
    public boolean reprovision() {
        throw new DeviceUnavailable("recovery rung 5's provision --force",
                "provisioning is not ported to Java yet (Python: petrotest/provision/, over SSH, DESIGN.md 6.1)");
    }

    @Override
    public void drain(String reason) {
        if (drainHook != null) {
            drainHook.accept(reason);
        }
    }
}
