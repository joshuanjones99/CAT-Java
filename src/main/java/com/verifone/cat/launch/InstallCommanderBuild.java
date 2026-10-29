package com.verifone.cat.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import com.verifone.cat.build.BuildCommand;
import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.BuildException;
import com.verifone.cat.build.CommanderBuildFetch;
import com.verifone.cat.build.InstallerJars;
import com.verifone.cat.commander.Say;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.upgrade.UpgradeCommand;

/**
 * Download a Commander build, install it, and reset the password PSI left: step 4's first stage. A port of the
 * Python's {@code cat.launch.upgrade.install_commander_build}, in its order.
 *
 * <ol>
 *   <li>Download ({@code petrotest build}), when a Commander Build is chosen and the mode installs one (AU or PSI):
 *       the build's {@code overlay.zip} and the mode's jars into {@code <builds root>\<label>\<dist>}, a viper-only
 *       build merged onto the run's foundation pick ({@link LaunchConfig#foundationBranch}/{@code foundationBuild})
 *       or its paired foundation. VHQ downloads no build. No build chosen: nothing is downloaded, and a
 *       {@link LaunchConfig#localBuild} names the downloaded build to install
 *       ({@code <builds root>\<localBuild>\<dist>}), where "" is the newest.</li>
 *   <li>Install ({@code petrotest upgrade}), for any mode but skip: from the build's directory, or, with none, from
 *       the newest build already under the builds root. Classic is {@link com.verifone.cat.upgrade.ClassicUpgrade}
 *       and VHQ {@link com.verifone.cat.upgrade.VhqUpgrade} (which fails saying what it needs), both through
 *       {@link UpgradeCommand}.</li>
 *   <li>{@code psi-init --no-pairing} after a PSI install, at once: the Commander keeps the installer's generated
 *       {@code manager} password until it runs, and every login with the lab password before then is a failed attempt
 *       that can lock the account (2026-09-21). {@link RunContext#commanderLoginReady()} is false from the start of
 *       the install until this has succeeded. Pairing needs {@code provision}'s Chromium, so it stays in step 4,
 *       after {@code provision}.</li>
 * </ol>
 *
 * <p>Each stage is one timed checklist row, named by the Python's {@code STAGE_LABELS}; a failure fails the step
 * with the stage's last error line, as the Python's {@code run_petrotest}.
 *
 * <p>The stages reach the world through {@link Stages}. {@link #run(RunContext, StepEmitter, CommanderStages, Rig)}
 * takes step 4's {@link CommanderStages} and adapts them ({@link Stages#over}): a PSI download with no foundation, the
 * newest PSI build and the PSI install are the {@code CommanderStages}' own; a classic or VHQ install, a classic or
 * merged download and the local-build listing are this class's, real only for the production stages.
 *
 * <p>Deviations from the Python:
 * <ul>
 *   <li>Under {@code --fake-pos} nothing is installed in any mode (a row says so), where the Python would run the
 *       upgrade; it never touches a device under fake POS.</li>
 *   <li>A chosen build whose {@code overlay.zip} and jars are already under the builds root, and which is not a merged
 *       one ({@value CommanderBuildFetch#MERGE_STAMP}), is not downloaded, and Artifactory is not asked (its row says
 *       "already downloaded"); the Python lists the build and asks each file's size first, so needs a login even then
 *       ({@link CommanderBuildFetch#missingFiles}). A merged build always goes to the download, which checks its stamp
 *       against the foundation pick.</li>
 *   <li>No Distribution field: the distribution is the run's ({@code config.dist()}).</li>
 *   <li>No {@code on_stage} callback: the web page's Install Upgrade has its own sequence.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested over recording stages only.
 */
final class InstallCommanderBuild {

    static final String PYTHON_MODULE = "src/cat/launch/upgrade.py";

    /** Whether nothing failed, and whether any stage ran at all: the Python's {@code (ok, ran)}. */
    record Result(boolean ok, boolean ran) {}

    /** What the install reaches outside itself; a test passes recorders. */
    interface Stages {

        /** Where a download lands and an install looks. */
        Path buildsRoot();

        /**
         * {@code petrotest build --branch B --build N --dist D --mode M [--foundation-branch FB --foundation-build FN]}.
         * A failure is written to {@code say}'s error stream.
         */
        boolean downloadCommanderBuild(String branch, String build, String dist, UpgradeMode mode,
                String foundationBranch, String foundationBuild, Say say);

        /** The newest build under {@link #buildsRoot} holding {@code dist} with the PSI files. */
        Optional<Path> newestPsiBuild(String dist);

        /** {@code petrotest upgrade --mode new_install --build-dir <buildDir>}. */
        boolean installNewInstall(Rig rig, Path buildDir, Say say);

        /** {@code petrotest upgrade --mode M --dist D [--build-dir <buildDir>]} for classic or VHQ. */
        boolean installUpgrade(Rig rig, UpgradeMode mode, Path buildDir, String dist, Say say);

        /** {@code petrotest psi-init} ({@code --no-pairing} when {@code pair} is false). */
        boolean psiInit(Rig rig, boolean pair, Say say);

        /** Every downloaded {@code <build>\<dist>} {@code mode} can install from, newest first (*Don't download*). */
        default List<Path> localBuildDirs(UpgradeMode mode, String dist) {
            return BuildDiscovery.localBuildDirs(buildsRoot(), dist, InstallerJars.forMode(mode));
        }

        /**
         * Step 4's stages as these: every stage is theirs ({@link CommanderStages#downloadCommanderBuild(String,
         * String, String, UpgradeMode, String, String, Say)} and {@link CommanderStages#installUpgrade}, which a test's
         * recorder leaves throwing {@link UnsupportedOperationException}: a failed row naming the stage).
         */
        static Stages over(CommanderStages stages) {
            return new Stages() {
                @Override
                public Path buildsRoot() {
                    return stages.buildsRoot();
                }

                @Override
                public boolean downloadCommanderBuild(String branch, String build, String dist, UpgradeMode mode,
                        String foundationBranch, String foundationBuild, Say say) {
                    return stages.downloadCommanderBuild(branch, build, dist, mode, foundationBranch,
                            foundationBuild, say);
                }

                @Override
                public Optional<Path> newestPsiBuild(String dist) {
                    return stages.newestBuild(dist);
                }

                @Override
                public boolean installNewInstall(Rig rig, Path buildDir, Say say) {
                    return stages.installNewInstall(rig, buildDir, say);
                }

                @Override
                public boolean psiInit(Rig rig, boolean pair, Say say) {
                    return stages.psiInit(rig, pair, say);
                }

                @Override
                public boolean installUpgrade(Rig rig, UpgradeMode mode, Path buildDir, String dist, Say say) {
                    return stages.installUpgrade(rig, mode, buildDir, dist, say);
                }
            };
        }
    }

    private InstallCommanderBuild() {}

    /**
     * Runs the download, install and password reset {@code ctx.config()} asks for, over step 4's stages.
     *
     * @param rig the loaded rig, or null when it did not load (a failure only if something must be installed)
     */
    static Result run(RunContext ctx, StepEmitter step, CommanderStages stages, Rig rig) {
        return run(ctx, step, Stages.over(stages), rig);
    }

    /** {@link #run(RunContext, StepEmitter, CommanderStages, Rig)} over the given stages. */
    static Result run(RunContext ctx, StepEmitter step, Stages stages, Rig rig) {
        LaunchConfig config = ctx.config();
        UpgradeMode mode = config.upgradeMode();
        String dist = config.dist();
        boolean chosen = config.commanderBuildChosen();
        String label = config.commanderBranch() + " " + config.commanderBuild();

        boolean ran = false;
        Path buildDir = null;
        if (!chosen) {
            if (mode == UpgradeMode.SKIP) {
                step.skip(label("build"), "no Commander Build chosen; upgrade mode is skip");
            } else if (!config.localBuild().isEmpty() && InstallerJars.downloads(mode)) {
                // Don't download, with a build on this PC picked: that one, not the newest.
                List<Path> local = stages.localBuildDirs(mode, dist);
                buildDir = local.stream().filter(path -> path.getParent() != null
                        && path.getParent().getFileName().toString().equals(config.localBuild()))
                        .findFirst().orElse(null);
                if (buildDir == null) {
                    List<String> needs = new ArrayList<>();
                    needs.add(BuildDiscovery.OVERLAY);
                    needs.addAll(InstallerJars.forMode(mode));
                    String why = config.localBuild() + " is not a " + dist + " build " + mode.wire()
                            + " can install from in " + stages.buildsRoot() + " (it needs " + String.join(" and ", needs)
                            + ")";
                    step.check(false, label("upgrade"), why, true);
                    step.fail(why);
                    return new Result(false, ran);
                }
            }
        } else if (mode == UpgradeMode.SKIP) {
            step.skip(label("build"), label + ": Upgrade mode is Skip, nothing to install");
        } else if (!InstallerJars.downloads(mode)) {
            step.skip(label("build"), label + ": " + mode.wire() + " downloads no build");
        } else if (config.fakePos()) {
            step.skip(label("build"), label + ": --fake-pos never touches real hardware");
        } else {
            try {
                buildDir = BuildDiscovery.buildDir(config.commanderBranch(), config.commanderBuild(), dist,
                        stages.buildsRoot());
            } catch (BuildException e) {
                step.substep(label("build"), SubstepOutcome.FAILED, e.getMessage(), 0.0);
                step.fail(e.getMessage());
                return new Result(false, false);
            }
            ran = true;
            List<String> jars = InstallerJars.forMode(mode);
            List<String> missing = CommanderBuildFetch.missingFiles(buildDir, jars);
            if (missing.isEmpty() && !Files.exists(buildDir.resolve(CommanderBuildFetch.MERGE_STAMP))) {
                // Everything the install needs is on disk: no Artifactory login is needed, and none is asked for.
                String held = "already downloaded: " + buildDir + " holds " + BuildDiscovery.OVERLAY + ", "
                        + String.join(", ", jars) + "; Artifactory not asked";
                step.log(held, "INFO");
                step.substep(label("build"), SubstepOutcome.PASSED, held, 0.0);
            } else if (!stage(step, "build", say -> stages.downloadCommanderBuild(config.commanderBranch(),
                    config.commanderBuild(), dist, mode, config.foundationBranch(), config.foundationBuild(), say))) {
                return new Result(false, ran);
            }
        }

        if (mode == UpgradeMode.SKIP) {
            return new Result(true, ran);
        }
        if (config.fakePos()) {
            step.skip(label("upgrade"), "--fake-pos never touches real hardware");
            if (mode == UpgradeMode.NEW_INSTALL) {
                step.skip(label("psi-init"), "--fake-pos never touches real hardware");
            }
            return new Result(true, ran);
        }
        if (rig == null) {
            step.fail("the rig file did not load, so the Commander build cannot be installed");
            return new Result(false, ran);
        }
        ran = true;
        if (mode != UpgradeMode.NEW_INSTALL) {
            // No build dir means "install the newest build already downloaded" (UpgradeCommand resolves it, and for
            // classic falls back to the PETROTEST_* variables); the dist tells that search which folder is this rig's.
            Path dir = buildDir;
            return new Result(stage(step, "upgrade", say -> stages.installUpgrade(rig, mode, dir, dist, say)), ran);
        }
        // From here on mode is NEW_INSTALL. The Commander's manager password is whatever PSI generates, until
        // psi-init resets it below.
        ctx.commanderLoginReady(false);
        if (buildDir == null) {
            Optional<Path> newest = stages.newestPsiBuild(dist);
            if (newest.isEmpty()) {
                String why = "no build under " + stages.buildsRoot() + " holds " + dist + " with overlay.zip and "
                        + "the PSI jars: choose a Commander Build to download one";
                step.substep(label("upgrade"), SubstepOutcome.FAILED, why, 0.0);
                step.fail(why);
                return new Result(false, ran);
            }
            buildDir = newest.get();
            step.log("no Commander Build chosen; using the build already downloaded: " + buildDir, "INFO");
        }
        Path dir = buildDir;
        if (!stage(step, "upgrade", say -> stages.installNewInstall(rig, dir, say))) {
            return new Result(false, ran);
        }
        // The password reset the moment the Commander is back, before anything else can log in with the lab
        // password (Petro polls every 3 min; six failures soft-lock the account, 2026-09-21).
        if (!stage(step, "psi-init", say -> stages.psiInit(rig, false, say))) {
            return new Result(false, ran);
        }
        ctx.commanderLoginReady(true);
        step.log("psi-init: password reset; registers not paired", "INFO");
        return new Result(true, ran);
    }

    static String label(String command) {
        return CommanderConfigurationStep.label(command);
    }

    /** One stage as one timed row: the same bracket as the step's own stages. */
    static boolean stage(StepEmitter step, String command, Function<Say, Boolean> body) {
        return CommanderConfigurationStep.stage(step, command, body);
    }

    /** The real download: {@link BuildCommand#build}, its {@code ERROR} line the stage's reason. */
    static boolean realDownload(String branch, String build, String dist, UpgradeMode mode, String foundationBranch,
            String foundationBuild, Path root, Map<String, String> env, Say say) {
        List<String> foundation = foundationBranch.isEmpty() || foundationBuild.isEmpty() ? null
                : List.of(foundationBranch, foundationBuild);
        return new BuildCommand().build(branch, build, dist, mode, foundation, null, env, root, errorsTo(say))
                == BuildCommand.EXIT_OK;
    }

    /** The real classic or VHQ install: {@link UpgradeCommand#upgrade}, its first failed step the stage's reason. */
    static boolean realInstall(Rig rig, UpgradeMode mode, Path buildDir, String dist, Path root,
            Map<String, String> env, Say say) {
        List<String> lines = new ArrayList<>();
        int code = new UpgradeCommand().upgrade(rig, mode, buildDir, dist, env, root, line -> {
            lines.add(line);
            say.out(line);
        });
        if (code == UpgradeCommand.EXIT_OK) {
            return true;
        }
        say.err("upgrade: " + failureLine(lines));
        return false;
    }

    /** The first {@code step: failed — why} line of an upgrade's output, else its last line. */
    static String failureLine(List<String> lines) {
        String last = "upgrade failed";
        for (String block : lines) {
            for (String line : block.split("\n")) {
                if (line.contains(": failed")) {
                    return line;
                }
                if (!line.isBlank() && !"FAILED".equals(line)) {
                    last = line;
                }
            }
        }
        return last;
    }

    /** Lines to {@code say}: an {@code ERROR ...} line to its error stream (the reason), the rest to its out. */
    static Consumer<String> errorsTo(Say say) {
        return line -> {
            if (line.startsWith("ERROR ")) {
                say.err(line);
            } else {
                say.out(line);
            }
        };
    }
}
