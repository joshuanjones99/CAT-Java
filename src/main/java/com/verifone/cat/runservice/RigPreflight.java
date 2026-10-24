package com.verifone.cat.runservice;

import java.util.Map;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.DefaultChecks;
import com.verifone.cat.preflight.PreflightReport;
import com.verifone.cat.preflight.PreflightRunner;
import com.verifone.cat.preflight.RigContext;
import com.verifone.cat.preflight.RigContextFactory;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * The Overview's Preflight button: the same checks as {@code cat-java preflight --no-repair}, run
 * on the web-selected rig's primary register. Check only: nothing is repaired from here, so the
 * button cannot drive the POS.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the checks reach the register's shim when run on a rig. Live
 * verdicts for the lab are in HANDOFF.md, not in the tests.
 */
public final class RigPreflight {

    private RigPreflight() {}

    /** The rendered report, or a sentence saying why it could not be made. Never repairs. */
    public static String checkOnly(String rigName, Map<String, String> env) {
        if (rigName == null || rigName.isBlank()) {
            return "No rig is named, so there is nothing to check. Start CAT-Java with --rig to check one.";
        }
        try {
            LoadedRig loaded = RigLoader.loadResolved(rigName, RigLoader.rigsDir(env));
            Rig rig = loaded.rig();
            if (!rig.posShim()) {
                return "Rig '" + rig.name() + "' does not set pos_shim: true, so its register has no shim to probe.";
            }
            RigContext context = RigContextFactory.build(rig, rig.primaryRegister(),
                    PosShimClient.DEFAULT_SHIM_PORT, env);
            PreflightReport report = PreflightRunner.run(context, DefaultChecks.defaults().checks(),
                    new PreflightRunner.RunOptions(false, PreflightRunner.VERIFY_BUDGET, false));
            return report.render();
        } catch (RuntimeException e) {
            return "Preflight could not run: " + e.getMessage();
        }
    }
}
