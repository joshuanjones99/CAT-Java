package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.Map;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanSource;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.setup.SetupEnv;

/**
 * Where a run reads and writes, and what it reads its environment from: the results root, the rigs folder,
 * the lease folder, this checkout, the petroapps checkout, the plan catalogue and the setup environment the
 * workstation audit reads.
 *
 * <p>Built from the environment in production ({@link #fromEnv}) and from {@code @TempDir}s in tests, so no
 * test touches {@code C:\CICD}, {@code C:\gitrepos\petroapps}, {@code ~/.cat} or the lab's rig files.
 *
 * @param resultsRoot  the folder each run's {@code <run-id>/} is made in
 * @param rigsDir      where {@code <rig>.yaml} is read from
 * @param leaseDir     where the rig's run lock file ({@code <rig>.run.lock}) lives
 * @param repoRoot     this checkout: the {@code tests/suites} tree a run collects into and the {@code target} the build makes
 * @param petroappsRoot the petroapps checkout whose freshness System Checks checks and whose cases step 5 collects
 * @param catalogue    where the plan catalogue and the plans' requirements come from
 * @param setupEnv     the environment the workstation audit reads (variables, home, commands)
 * @param pythonRoot   the Python CAT checkout whose {@code tests/suites/**} hand-migrated suites step 5 runs cases from
 *                     before pulling any ({@code plans.LocalSuites}), or {@code null} for none: every case is pulled
 */
public record LaunchPaths(Path resultsRoot, Path rigsDir, Path leaseDir, Path repoRoot, Path petroappsRoot,
        PlanCatalogue catalogue, SetupEnv setupEnv, Path pythonRoot) {

    public LaunchPaths {
        if (resultsRoot == null || rigsDir == null || leaseDir == null || repoRoot == null
                || petroappsRoot == null || catalogue == null || setupEnv == null) {
            throw new IllegalArgumentException("every launch path, the catalogue and the setup environment are required");
        }
    }

    /** Paths with no local suites: every plan case is pulled from petroapps (the test fixtures' default). */
    public LaunchPaths(Path resultsRoot, Path rigsDir, Path leaseDir, Path repoRoot, Path petroappsRoot,
            PlanCatalogue catalogue, SetupEnv setupEnv) {
        this(resultsRoot, rigsDir, leaseDir, repoRoot, petroappsRoot, catalogue, setupEnv, null);
    }

    /**
     * The paths the environment names, with the documented defaults. The catalogue writes to {@code ~/.cat/java}
     * and reads the Python CAT's {@code ~/.cat} behind it, as {@link PlanCatalogue#offline} does; the setup
     * environment is the real process's (the map only feeds the folders above).
     */
    public static LaunchPaths fromEnv(Map<String, String> env) {
        Path repoRoot = Path.of("").toAbsolutePath();
        String petroapps = env.getOrDefault("PETROAPPS_ROOT", "").strip();
        Path home = Path.of(System.getProperty("user.home"));
        return new LaunchPaths(
                ReportPaths.resultsRoot(env),
                RigLoader.rigsDir(env),
                RigLease.resolveLeaseDir(null, env),
                repoRoot,
                petroapps.isEmpty() ? Path.of(Collect.DEFAULT_PETROAPPS_ROOT) : Path.of(petroapps),
                new PlanCatalogue(home.resolve(".cat").resolve("java"), home.resolve(".cat"),
                        PlanSource.unavailable("no Atlassian source in this build"), new SeedPlanSource(), repoRoot),
                SetupEnv.system(),
                ProvisionPaths.pythonRoot(env));
    }
}
