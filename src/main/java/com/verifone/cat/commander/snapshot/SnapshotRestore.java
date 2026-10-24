package com.verifone.cat.commander.snapshot;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.devices.commander.CommanderSuiteInfo;

/**
 * Step 4's snapshot restore as {@code commander-init} calls it, before the base import: the seam
 * {@code CommanderInit} takes, so its tests pass a fake and only {@link #forRig} reaches a Commander.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
@FunctionalInterface
public interface SnapshotRestore {

    /**
     * Restores the snapshot for the Commander's build.
     *
     * @param baseFolder petroapps {@code base/}, to say when it changed since the capture
     * @return the report, or null when there was nothing to restore from (said in a line)
     */
    Restore.RestoreReport restore(Path baseFolder, Consumer<String> say);

    /**
     * The production restore on {@code access}'s Commander: snapshots under {@code $CAT_SNAPSHOTS_ROOT} (from the
     * access's environment), the build from {@code suiteinfo.dat} over SSH, CGILink through a {@code PdkShim}.
     */
    static SnapshotRestore forRig(CommanderAccess access) {
        return (baseFolder, say) -> Restore.restoreForRig(Snapshots.snapshotsRoot(access.environ()),
                () -> suiteinfo(access), CommanderConfig.of(access.pdkShim()), baseFolder, say, Rules.DEFAULT);
    }

    /** The Commander's {@code suiteinfo.dat} over SSH, with the rig's SSH budget. */
    static Map<String, String> suiteinfo(CommanderAccess access) {
        return CommanderSuiteInfo.read(access::connectedTransport, access.sshTimeout());
    }
}
