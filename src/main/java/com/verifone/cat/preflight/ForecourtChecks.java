package com.verifone.cat.preflight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * The forecourt check, DESIGN.md 6.2 row 6. Ported from the Python CAT's
 * {@code petrotest/preflight/checks/forecourt.py}.
 *
 * <p>A rig with {@code forecourt: petro} reads its pumps from Petro Simulator Suite
 * ({@link RigContext#pumpStatus()}). There is no repair for those: clearing a fill sells or cancels it,
 * which is more than 6.2's "handle-down; clear prepay", and it is not a PATS step. A rig that declares pumps
 * but no {@code forecourt:} has nothing to read them from, and the check raises {@link DeviceUnavailable}
 * (ERROR, not FAIL).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ForecourtChecks {

    private ForecourtChecks() {}

    /** Row 6: every declared pump idle or authorised, no handle up, no prepay pending. */
    public static final class PumpsIdle extends BaseCheck {

        public PumpsIdle() {
            super("pumps_idle", Duration.ofSeconds(30));
        }

        /** An indoor-only rig declares no pumps: not running is more honest than a PASS. */
        @Override
        public boolean applicable(RigContext rig) {
            return !rig.expectations().pumps().isEmpty();
        }

        /**
         * Reading Petro Simulator Suite means logging in to it, which PATS never does mid-run (rig owner,
         * 2026-09-22), on the port the running test's pumps may be holding.
         */
        @Override
        public String operatorOnly(RigContext rig) {
            if (rig.pumpStatus() == null) {
                return "";
            }
            return "reading Petro Simulator Suite means logging in to it, which PATS never does mid-run; "
                    + "run `petrotest preflight` (CAT's `p`) for this row";
        }

        @Override
        public Result verify(RigContext rig) {
            if (rig.pumpStatus() == null) {
                throw new DeviceUnavailable("pumps_idle's pump read", "the rig declares pumps but no forecourt:");
            }
            List<String> busy = new ArrayList<>();
            for (Surfaces.PumpState state : rig.pumpStatus().pumps(rig.expectations().pumps())) {
                if (!state.idle() || state.pendingPrepay()) {
                    busy.add(state.number() + "=" + state.state() + (state.handleUp() ? "/handle-up" : "")
                            + (state.pendingPrepay() ? "/prepay" : ""));
                }
            }
            if (!busy.isEmpty()) {
                return Result.failed(name(), "pumps not idle: " + String.join(", ", busy));
            }
            return Result.passed(name(), rig.expectations().pumps().size() + " pump(s) idle");
        }

        @Override
        public Result repair(RigContext rig) {
            return Result.unrepairable(name(), "pumps on Petro Simulator Suite have no preflight repair; clear them "
                    + "at the simulator (DESIGN.md 6.2 row 6)");
        }
    }
}
