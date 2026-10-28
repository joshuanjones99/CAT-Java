package com.verifone.cat.launch;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.verifone.cat.build.ArtifactoryClient;
import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.BuildException;
import com.verifone.cat.build.CommanderBuildFetch;
import com.verifone.cat.build.EnvArtifactoryCredentials;
import com.verifone.cat.build.Installed;
import com.verifone.cat.devices.commander.CommanderSuiteInfo;
import com.verifone.cat.upgrade.NewInstall;
import com.verifone.cat.upgrade.NewInstallTools;
import com.verifone.cat.upgrade.PsiInit;
import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.commander.CommanderPrep;
import com.verifone.cat.commander.PaymentReady;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.ProvisionOptions;
import com.verifone.cat.provision.ProvisionPaths;
import com.verifone.cat.provision.ProvisionReport;
import com.verifone.cat.provision.ProvisionRunner;
import com.verifone.cat.provision.Stage;
import com.verifone.cat.rig.Rig;

/**
 * The ported stages of step 4, each the Python step's {@code petrotest <command>} child run in-process: a
 * stage writes its lines to a {@link Say} and returns whether it passed. {@link #real} reaches the rig; a
 * test passes recorders, so no step test signs in to a box.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
interface CommanderStages {

    /** The builds root, {@code $CAT_BUILDS_ROOT} or {@code C:\CICD\Builds}: where a download lands and an install looks. */
    default Path buildsRoot() {
        throw new UnsupportedOperationException("buildsRoot: not given to these stages");
    }

    /**
     * {@code petrotest build}: {@code overlay.zip} and the PSI jars of {@code branch} build {@code build}'s
     * {@code dist} folder, into {@link #buildsRoot()}. A failure is written to {@code say}'s error stream.
     */
    default boolean downloadCommanderBuild(String branch, String build, String dist, Say say) {
        throw new UnsupportedOperationException("downloadCommanderBuild: not given to these stages");
    }

    /** The newest build under {@link #buildsRoot()} holding {@code dist} with the PSI files, as the Python's upgrade picks. */
    default Optional<Path> newestBuild(String dist) {
        throw new UnsupportedOperationException("newestBuild: not given to these stages");
    }

    /** {@code petrotest upgrade --mode new_install --build-dir <buildDir>}: the PSI install. */
    default boolean installNewInstall(Rig rig, Path buildDir, Say say) {
        throw new UnsupportedOperationException("installNewInstall: not given to these stages");
    }

    /** {@code petrotest psi-init} ({@code --no-pairing} when {@code pair} is false). */
    default boolean psiInit(Rig rig, boolean pair, Say say) {
        throw new UnsupportedOperationException("psiInit: not given to these stages");
    }

    /** {@code petrotest.build.installed.installed_build_name}: the build on the Commander, by commit hash. Never throws. */
    default Installed.Identification installedBuild(Rig rig, String dist) {
        throw new UnsupportedOperationException("installedBuild: not given to these stages");
    }

    /** {@code petrotest provision}: the pos-shim payload pushed, verified and stamped on every target. */
    boolean provision(Rig rig, Say say);

    /** {@code petrotest commander-prep}: the license dates and the Gemreg marker. */
    boolean commanderPrep(Rig rig, Say say);

    /** {@code petrotest commander-init} ({@code --no-forecourt} when {@code forecourt} is false). */
    boolean commanderInit(Rig rig, boolean forecourt, Say say);

    /** {@code petrotest payment-ready}: the EPS ready for card sales. */
    boolean paymentReady(Rig rig, Say say);

    /**
     * What step 4 runs between the inits and the payment-ready wait that CAT-Java does not have, or ""
     * when nothing is missing. While it is not "", the step ends unavailable there.
     */
    default String missingAfterInit() {
        return CommanderConfigurationStep.MISSING_AFTER_INIT;
    }

    /** The Artifactory client's per-request budget for a build download, the web Downloads page's. */
    Duration ARTIFACTORY_TIMEOUT = Duration.ofMinutes(10);

    /** The real stages, against the rig's devices, with {@code environ} for keys and paths. */
    static CommanderStages real(Map<String, String> environ) {
        return new CommanderStages() {
            @Override
            public Path buildsRoot() {
                return BuildDiscovery.buildsRoot(environ);
            }

            @Override
            public boolean downloadCommanderBuild(String branch, String build, String dist, Say say) {
                ArtifactoryClient client = new ArtifactoryClient(URI.create(ArtifactoryClient.DEFAULT_BASE_URL),
                        new EnvArtifactoryCredentials(environ), ARTIFACTORY_TIMEOUT);
                try {
                    CommanderBuildFetch.fetch(client, branch, build, dist, CommanderBuildFetch.PSI_FILES,
                            buildsRoot(), say::out);
                    return true;
                } catch (BuildException e) {
                    say.err("build: " + e.getMessage());
                    return false;
                }
            }

            @Override
            public Optional<Path> newestBuild(String dist) {
                return BuildDiscovery.latestBuildDir(buildsRoot(), dist, CommanderBuildFetch.PSI_FILES);
            }

            @Override
            public boolean installNewInstall(Rig rig, Path buildDir, Say say) {
                NewInstallTools tools;
                try {
                    tools = NewInstallTools.forBuild(buildDir, environ);
                } catch (IllegalArgumentException e) {
                    say.err("upgrade: " + e.getMessage());
                    return false;
                }
                List<com.verifone.cat.upgrade.StepResult> results =
                        NewInstall.system(tools, say::out).run(rig, environ);
                for (com.verifone.cat.upgrade.StepResult result : results) {
                    if (result.failed()) {
                        say.err("upgrade: " + result.step() + " failed: " + result.detail());
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean psiInit(Rig rig, boolean pair, Say say) {
                return PsiInit.forRig(rig, environ, say).run(rig, pair);
            }

            @Override
            public Installed.Identification installedBuild(Rig rig, String dist) {
                CommanderAccess access = access(rig);
                return Installed.installedBuildName(
                        () -> CommanderSuiteInfo.read(access::transport, access.sshTimeout()), dist, buildsRoot());
            }

            @Override
            public boolean provision(Rig rig, Say say) {
                ProvisionOptions options = ProvisionOptions.defaults()
                        .payloadRoot(ProvisionPaths.payloadRoot(environ))
                        .stageSources(Stage.Sources.fromEnv(environ))
                        .environ(environ)
                        .toolVersion("cat-java");
                ProvisionReport report;
                try {
                    report = ProvisionRunner.provisionRig(rig, options);
                } catch (IllegalArgumentException e) {
                    say.err("provision: " + e.getMessage());
                    return false;
                }
                say.out(report.render());
                if (!report.ok()) {
                    say.err("provision: a target is not provisioned (its report is above)");
                }
                return report.ok();
            }

            @Override
            public boolean commanderPrep(Rig rig, Say say) {
                return CommanderPrep.run(access(rig), LocalDate.now(), say);
            }

            @Override
            public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
                return CommanderInit.forRig(access(rig), say).run(null, forecourt, true);
            }

            @Override
            public boolean paymentReady(Rig rig, Say say) {
                return PaymentReady.run(access(rig), say);
            }

            private CommanderAccess access(Rig rig) {
                return new CommanderAccess(rig, com.verifone.cat.provision.transport.JschTransport::new, environ);
            }
        };
    }
}
