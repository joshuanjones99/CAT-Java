package com.verifone.cat.launch;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.BuildException;
import com.verifone.cat.commander.Say;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;

/**
 * Download a Commander build, install it, and reset the password PSI left: step 4's first stage. A port of the
 * Python's {@code cat.launch.upgrade.install_commander_build}, in its order.
 *
 * <ol>
 *   <li>Download ({@code petrotest build}), when a Commander Build is chosen and the mode installs one: the build's
 *       {@code overlay.zip} and PSI jars into {@code <builds root>\<label>\<dist>}. No build chosen: nothing is
 *       downloaded.</li>
 *   <li>Install ({@code petrotest upgrade}), for any mode but skip: from the downloaded build's directory, or, with
 *       none chosen, from the newest build already under the builds root.</li>
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
 * <p>Deviations from the Python:
 * <ul>
 *   <li>AU ({@code classic}) and VHQ installs are unavailable in CAT-Java ({@code petrotest/upgrade/classic.py},
 *       {@code vhq.py}): the step says so before anything is downloaded, where the Python downloads first.</li>
 *   <li>No {@code local_build}, foundation pick or Distribution field: {@link LaunchConfig} carries none, so the
 *       distribution is the run.s ({@code config.dist()}) and a viper-only build (which the Python
 *       merges onto a foundation) fails its download with a reason.</li>
 *   <li>Under {@code --fake-pos} nothing is installed (a row says so), where the Python would run the upgrade; it
 *       never touches a device under fake POS.</li>
 *   <li>No {@code on_stage} callback: the web page's Install Upgrade has its own sequence.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tested over recording stages only.
 */
final class InstallCommanderBuild {

    static final String PYTHON_MODULE = "src/cat/launch/upgrade.py";

    /** Whether nothing failed, and whether any stage ran at all: the Python's {@code (ok, ran)}. */
    record Result(boolean ok, boolean ran) {}

    private InstallCommanderBuild() {}

    /**
     * Runs the download, install and password reset {@code ctx.config()} asks for.
     *
     * @param rig the loaded rig, or null when it did not load (a failure only if something must be installed)
     */
    static Result run(RunContext ctx, StepEmitter step, CommanderStages stages, Rig rig) {
        LaunchConfig config = ctx.config();
        UpgradeMode mode = config.upgradeMode();
        String dist = config.dist();
        boolean chosen = config.commanderBuildChosen();
        String label = config.commanderBranch() + " " + config.commanderBuild();

        if (mode == UpgradeMode.CLASSIC || mode == UpgradeMode.VHQ) {
            String module = mode == UpgradeMode.CLASSIC ? "petrotest/upgrade/classic.py" : "petrotest/upgrade/vhq.py";
            step.unavailable(PYTHON_MODULE, "the Commander build install for upgrade mode '" + mode.wire() + "' ("
                    + module + "); only skip and new_install are ported");
            return new Result(false, false);
        }

        boolean ran = false;
        Path buildDir = null;
        if (!chosen) {
            if (mode == UpgradeMode.SKIP) {
                step.skip(label("build"), "no Commander Build chosen; upgrade mode is skip");
            }
        } else if (mode == UpgradeMode.SKIP) {
            step.skip(label("build"), label + ": Upgrade mode is Skip, nothing to install");
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
            if (!stage(step, "build", say -> stages.downloadCommanderBuild(config.commanderBranch(),
                    config.commanderBuild(), dist, say))) {
                return new Result(false, ran);
            }
        }

        if (mode == UpgradeMode.SKIP) {
            return new Result(true, ran);
        }
        // From here on mode is NEW_INSTALL.
        if (config.fakePos()) {
            step.skip(label("upgrade"), "--fake-pos never touches real hardware");
            step.skip(label("psi-init"), "--fake-pos never touches real hardware");
            return new Result(true, ran);
        }
        if (rig == null) {
            step.fail("the rig file did not load, so the Commander build cannot be installed");
            return new Result(false, ran);
        }
        ran = true;
        // From here the Commander's manager password is whatever PSI generates, until psi-init resets it below.
        ctx.commanderLoginReady(false);
        if (buildDir == null) {
            Optional<Path> newest = stages.newestBuild(dist);
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
}
