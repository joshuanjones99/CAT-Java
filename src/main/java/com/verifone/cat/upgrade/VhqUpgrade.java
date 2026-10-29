package com.verifone.cat.upgrade;

import java.util.List;

/**
 * The VHQ (cloud deployment) upgrade path: not implemented, as in the Python CAT.
 *
 * <p>A port of {@code petrotest.upgrade.vhq.upgrade_vhq}, which reports {@code FAILED} with a reason: it needs a live
 * VHQ tenant (URL, credentials, a {@code commander_serial}) to confirm the deployment job's request and reply shapes.
 * Not {@code SKIPPED}: a skipped step counts as ok, and a run that asked for an upgrade would then test the old build
 * as if it had one.
 */
public final class VhqUpgrade {

    static final String STEP = "upgrade_vhq";

    /** The Python's detail, word for word. */
    static final String DETAIL = "not implemented — needs a live VHQ tenant (URL, credentials, "
            + "commander_serial) to confirm the deployment-job request/response "
            + "shapes. Until this lands, request UpgradeMode.CLASSIC or "
            + "UpgradeMode.SKIP instead.";

    private VhqUpgrade() {}

    /** One failed step, saying what it needs. */
    public static List<StepResult> run() {
        return List.of(StepResult.failed(STEP, DETAIL));
    }
}
