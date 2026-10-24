package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.Map;

import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;

/**
 * Where a run reads and writes: the results root, the rigs folder and the lease folder.
 *
 * <p>Built from the environment in production ({@link #fromEnv}) and from {@code @TempDir}s in
 * tests, so no test touches {@code C:\CICD} or the lab's rig files.
 *
 * @param resultsRoot the folder each run's {@code <run-id>/} is made in
 * @param rigsDir     where {@code <rig>.yaml} is read from
 * @param leaseDir    where the rig's run lock file ({@code <rig>.run.lock}) lives
 */
public record LaunchPaths(Path resultsRoot, Path rigsDir, Path leaseDir) {

    public LaunchPaths {
        if (resultsRoot == null || rigsDir == null || leaseDir == null) {
            throw new IllegalArgumentException("resultsRoot, rigsDir and leaseDir are all required");
        }
    }

    /** The paths the environment names, with the documented defaults. */
    public static LaunchPaths fromEnv(Map<String, String> env) {
        return new LaunchPaths(
                ReportPaths.resultsRoot(env),
                RigLoader.rigsDir(env),
                RigLease.resolveLeaseDir(null, env));
    }
}
