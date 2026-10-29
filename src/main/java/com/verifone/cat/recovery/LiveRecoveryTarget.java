package com.verifone.cat.recovery;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.preflight.DefaultChecks;
import com.verifone.cat.preflight.PreflightReport;
import com.verifone.cat.preflight.PreflightRunner;
import com.verifone.cat.preflight.Result;
import com.verifone.cat.preflight.RigContext;
import com.verifone.cat.preflight.Surfaces;
import com.verifone.cat.provision.Poll;
import com.verifone.cat.provision.ProvisionReport;

/**
 * A {@link RecoveryTarget} backed by the subsystems this port has. Ported from the Python
 * {@code petrotest.recovery.wiring.LiveRecoveryTarget} and the rung half of
 * {@code petrotest.recovery.plugin}.
 *
 * <ul>
 *   <li><b>Testability</b>: with a {@link PosScreen}, the register's DOM, as the Python's
 *       {@code _testable(live_pos_idle, live_at_login)}: the idle sales screen (or an SCO lane's welcome
 *       screen), else the login screen. Without one, the shim's status surface (stale on the lab rig:
 *       HANDOFF.md 1.37), and the login screen is not testable.</li>
 *   <li><b>SOFT</b> (rung 1): the shim's cancel ladder (a raise is logged, and the DOM half still
 *       runs), then, with a {@link PosScreen}, its {@link PosScreen#softCancel()} (an OK-only message,
 *       an SCO overlay, the CSR menus), as {@code _live_soft_cancel}. The rung counts as performed once
 *       both were tried, and the rig decides with {@link #isTestable()}.</li>
 *   <li><b>VOID</b> (rung 2): with a {@link PosScreen}, its {@link PosScreen#voidOpenSale()}, falling
 *       back to the shim's void when that raises, as {@code _live_void}; without one, the shim's void.</li>
 *   <li><b>RESET</b> (rung 3): unavailable. simhub was its only reset and was removed (HANDOFF.md
 *       1.120), so the ladder escalates past it, as the Python does on every rig.</li>
 *   <li><b>PREFLIGHT</b> (rung 4): {@link PreflightRunner} with repair, mid-run, as the Python's
 *       {@code run_preflight}. Each repair it made is recorded in the run summary.</li>
 *   <li><b>REPROVISION</b> (rung 5): {@code provision --force} ({@link com.verifone.cat.provision.ProvisionForce},
 *       the Python's {@code provision_force}), then, when it restarted the POS app, a wait of at most
 *       {@link #POS_RESTART_WAIT} for the POS to draw ({@link PosScreen#drawn()}, the Python's
 *       {@code live_pos_drawn}; a wait that runs out is a WARNING and preflight reads it anyway), then preflight
 *       again as rung 4. A provision that is not ok fails the rung. Without a provision seam (the six-argument
 *       constructor) the rung is unavailable.</li>
 *   <li><b>DRAIN</b> (rung 6): the drain hook, if one was given.</li>
 * </ul>
 *
 * <p>An unavailable rung throws {@link DeviceUnavailable}, which the ladder records as a rung that
 * did not run. It never reports success (CLAUDE.md hard rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class LiveRecoveryTarget implements RecoveryTarget {

    private static final Logger LOG = LoggerFactory.getLogger(LiveRecoveryTarget.class);

    private final String rigName;
    private final RigContext rig;
    private final Surfaces.PosStatus status;
    private final Surfaces.PosControl control;
    private final RunSummary summary;
    private final Consumer<String> drainHook;
    private final PosScreen screen;
    private final Supplier<ProvisionReport> provision;
    private final Duration posRestartWait;
    private final Duration posPoll;

    /** The Python's {@code wiring.POS_RESTART_WAIT}: how long rung 5 waits for a restarted POS to draw. */
    public static final Duration POS_RESTART_WAIT = Duration.ofSeconds(300);
    /** How often that wait looks (the Python's 2 s). */
    static final Duration POS_POLL = Duration.ofSeconds(2);

    /**
     * @param rig       the preflight view of the rig, used by rung 4
     * @param status    the POS status surface, read by the testability probe
     * @param control   the POS control surface, the same one preflight's repairs use
     * @param summary   where rung 4's repairs are recorded, or {@code null}
     * @param drainHook told the drain message at rung 6, or {@code null}
     */
    public LiveRecoveryTarget(String rigName, RigContext rig, Surfaces.PosStatus status,
            Surfaces.PosControl control, RunSummary summary, Consumer<String> drainHook) {
        this(rigName, rig, status, control, null, summary, drainHook);
    }

    /**
     * @param screen the register's DOM ({@link LivePosScreen#forRig}), or {@code null} for the shim alone
     */
    public LiveRecoveryTarget(String rigName, RigContext rig, Surfaces.PosStatus status,
            Surfaces.PosControl control, PosScreen screen, RunSummary summary, Consumer<String> drainHook) {
        this(rigName, rig, status, control, screen, summary, drainHook, null, POS_RESTART_WAIT, POS_POLL);
    }

    /**
     * @param provision      rung 5's {@code provision --force}, or {@code null} for an unavailable rung
     * @param posRestartWait how long rung 5 waits for a POS the provision restarted to draw
     * @param posPoll        how often that wait looks
     */
    public LiveRecoveryTarget(String rigName, RigContext rig, Surfaces.PosStatus status,
            Surfaces.PosControl control, PosScreen screen, RunSummary summary, Consumer<String> drainHook,
            Supplier<ProvisionReport> provision, Duration posRestartWait, Duration posPoll) {
        this.provision = provision;
        this.posRestartWait = posRestartWait;
        this.posPoll = posPoll;
        this.screen = screen;
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
     * With a {@link PosScreen}: the idle sales screen, else the login screen, asked only when the first
     * says no (the Python's {@code _testable}). Without one: the shim's idle screen with no open ticket.
     */
    @Override
    public boolean isTestable() {
        if (screen != null) {
            return screen.idle() || screen.atLogin();
        }
        return !status.transactionActive() && status.idleScreenPresent();
    }

    @Override
    public boolean softCancel() {
        // The shim's success is its own re-read of the screen, and may be stale (the Python's note at
        // _live_soft_cancel). The performed rung is judged by isTestable(), not by this answer.
        if (screen == null) {
            control.cancelLadder();
            return true;
        }
        try {
            control.cancelLadder();
        } catch (RuntimeException e) { // the CDP half still runs
            LOG.warn("recovery SOFT: shim cancel raised {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
        screen.softCancel();
        return true;
    }

    @Override
    public boolean voidOpenTicket() {
        if (screen == null) {
            return control.voidOpenTicket();
        }
        try {
            screen.voidOpenSale();
            return true;
        } catch (RuntimeException e) {
            LOG.warn("recovery VOID: CDP void raised {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return control.voidOpenTicket();
        }
    }

    @Override
    public boolean resetState() {
        throw new DeviceUnavailable("recovery rung 3's device reset",
                "simhub's POST /reset was the only one, and simhub was removed (HANDOFF.md 1.120); "
                        + "Python: petrotest/recovery/plugin.py _no_device_reset");
    }

    @Override
    public boolean runPreflight() {
        return preflight();
    }

    private boolean preflight() {
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

    /**
     * Rung 5: {@code provision --force}, then preflight again. Both halves are required: provisioning says nothing
     * about whether the POS is testable, and a rung that passed on a provisioning report alone would end the ladder
     * on a rig that is provisioned and still wedged. A provision that restarted the POS is waited out first (run
     * 4a04258badad read the register 15 s after the restart and drained a rig that was back within two minutes).
     */
    @Override
    public boolean reprovision() {
        if (provision == null) {
            throw new DeviceUnavailable("recovery rung 5's provision --force",
                    "no provision is wired to this recovery target (Python: petrotest.provision.provision_force)");
        }
        ProvisionReport report = provision.get();
        LOG.info("recovery REPROVISION: {}", report.render().strip());
        if (!report.ok()) {
            return false;
        }
        if (report.restartedPos() && screen != null) {
            try {
                Poll.until(() -> screen.drawn() ? Boolean.TRUE : null, posRestartWait, posPoll,
                        "the restarted POS to draw its login or sales screen");
            } catch (com.verifone.cat.preflight.Bounded.TimeoutExpired e) {
                LOG.warn("recovery REPROVISION: {}; preflight reads it anyway", e.getMessage());
            }
        }
        return preflight();
    }

    @Override
    public void drain(String reason) {
        if (drainHook != null) {
            drainHook.accept(reason);
        }
    }
}
