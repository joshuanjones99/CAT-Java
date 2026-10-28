package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.commander.CommanderInit;
import com.verifone.cat.build.Installed;
import com.verifone.cat.commander.Say;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * Step 4 over recording stages: the Python step's order, the ported stages run, and every part CAT-Java lacks
 * reported unavailable or as a warning, never as a pass. No stage here reaches a device.
 */
class CommanderConfigurationStepTest {

    @TempDir
    Path dir;

    private int runs;

    /** Records each stage it is asked to run, and answers as told. */
    private static final class Stages implements CommanderStages {

        final List<String> ran = new ArrayList<>();
        boolean provision = true;
        boolean prep = true;
        boolean init = true;
        boolean ready = true;
        String missing = CommanderConfigurationStep.MISSING_AFTER_INIT;

        @Override
        public boolean provision(Rig rig, Say say) {
            ran.add("provision");
            say.out("provision report");
            if (!provision) {
                say.err("provision: a target is not provisioned (its report is above)");
            }
            return provision;
        }

        @Override
        public boolean commanderPrep(Rig rig, Say say) {
            ran.add("commander-prep");
            return prep;
        }

        @Override
        public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
            ran.add("commander-init forecourt=" + forecourt);
            if (!init) {
                say.err("Commander syslog, FCC/TWS/COR errors:\n(none in the last 5000 lines)");
                say.err("commander init on commander.invalid failed: DeviceError: cfuelinit: CGIPortal.InternalError");
            }
            return init;
        }

        @Override
        public boolean paymentReady(Rig rig, Say say) {
            ran.add("payment-ready");
            if (!ready) {
                say.err("payment-ready: NOT ready after 900s: payment controller down");
            }
            return ready;
        }

        @Override
        public String missingAfterInit() {
            return missing;
        }

        // The install half (InstallCommanderBuild and the psi-init insertion points).
        Path root = Path.of("builds-root-unused");
        boolean download = true;
        boolean install = true;
        boolean psiNoPairing = true;
        boolean psiPairing = true;
        Optional<Path> newest = Optional.empty();
        Installed.Identification installed;

        @Override
        public Path buildsRoot() {
            return root;
        }

        @Override
        public boolean downloadCommanderBuild(String branch, String build, String dist, Say say) {
            ran.add("build " + branch + " " + build + " " + dist);
            if (!download) {
                say.err("build: Solutions 134 holds no buypass folder ()");
            }
            return download;
        }

        @Override
        public Optional<Path> newestBuild(String dist) {
            ran.add("newest " + dist);
            return newest;
        }

        @Override
        public boolean installNewInstall(Rig rig, Path buildDir, Say say) {
            ran.add("upgrade " + root.relativize(buildDir).toString().replace('\\', '/'));
            if (!install) {
                say.err("upgrade: install_commander failed: 'Installation Failed'");
            }
            return install;
        }

        @Override
        public boolean psiInit(Rig rig, boolean pair, Say say) {
            ran.add(pair ? "psi-init" : "psi-init --no-pairing");
            boolean ok = pair ? psiPairing : psiNoPairing;
            if (!ok) {
                say.err("psi-init failed: changepasswd still faulting after 6 attempts");
            }
            return ok;
        }

        @Override
        public Installed.Identification installedBuild(Rig rig, String dist) {
            if (installed == null) {
                throw new DeviceError("could not read suiteinfo.dat");
            }
            return installed;
        }
    }

    private RunContext context(LaunchConfig config, boolean withRig) throws IOException {
        Path folder = dir.resolve("run" + runs++);
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "cccccccccccc", folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        if (withRig) {
            ctx.rig(RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir()));
        }
        return ctx;
    }

    private record Outcome(boolean keepGoing, StepEmitter step, Map<String, String> rows, Map<String, String> details,
            RunContext ctx) {}

    private Outcome run(LaunchConfig config, Stages stages, Map<String, String> environ, boolean withRig)
            throws IOException {
        RunContext ctx = context(config, withRig);
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.COMMANDER);
        boolean keepGoing = new CommanderConfigurationStep(stages, environ).run(ctx, step);
        ctx.closeEvents();
        Map<String, String> rows = new LinkedHashMap<>();
        Map<String, String> details = new LinkedHashMap<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            if (event.get("type").asText().equals("step.check")) {
                rows.put(event.get("label").asText(), event.get("outcome").asText());
                details.put(event.get("label").asText(), event.get("detail").asText());
            }
        }
        return new Outcome(keepGoing, step, rows, details, ctx);
    }

    private Outcome run(LaunchConfig config, Stages stages) throws IOException {
        return run(config, stages, Map.of(), true);
    }

    private static String label(String command) {
        return CommanderConfigurationStep.label(command);
    }

    @Test
    void aFakePosRunTouchesNothingAndTheStepIsSkipped() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder("").fakePos(true).build(), stages, Map.of(), false);

        assertTrue(out.keepGoing());
        assertEquals(StepResult.SKIPPED, out.step().state());
        assertEquals("existing build, upgrade mode 'skip', provision not requested", out.step().detail());
        assertTrue(stages.ran.isEmpty());
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("provision")));
    }

    @Test
    void anUpgradeModeOtherThanSkipIsUnavailableBeforeAnythingRuns() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.CLASSIC).build(), stages);

        assertFalse(out.keepGoing());
        assertEquals(StepResult.FAILED, out.step().state());
        assertTrue(out.step().detail().startsWith("unavailable in CAT-Java: needs the Commander build install for "
                + "upgrade mode 'classic'"), out.step().detail());
        assertTrue(stages.ran.isEmpty());
    }

    @Test
    void aRigRunRunsTheStagesInThePythonsOrderThenStopsUnavailableWhereThePortEnds() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertEquals(List.of("provision", "commander-prep", "commander-init forecourt=false"), stages.ran);
        assertFalse(out.keepGoing());
        assertEquals(StepResult.FAILED, out.step().state());
        assertEquals("unavailable in CAT-Java: needs " + CommanderConfigurationStep.MISSING_AFTER_INIT + " (Python: "
                + CommanderConfigurationStep.PYTHON_MODULE + ")", out.step().detail());

        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("provision")));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("commander-prep")));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("commander-init")));
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(CommanderConfigurationStep.POS_SHIM_BUILD_ROW));
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get("fuel and DCR driver inits"));
        // The snapshot restore is not ported: a warning, never a pass.
        assertEquals(SubstepOutcome.WARNED, out.rows().get(CommanderConfigurationStep.SNAPSHOT_ROW));
        assertTrue(out.details().get(CommanderConfigurationStep.SNAPSHOT_ROW).startsWith(CommanderInit.SNAPSHOT_UNAVAILABLE));
        assertFalse(out.rows().containsKey(label("payment-ready")));
    }

    @Test
    void petroSelectedSendsTheForecourtInits() throws IOException {
        Stages stages = new Stages();
        run(new LaunchConfig.Builder(LaunchFixtures.RIG).simulator(StartSimulatorsStep.PETRO_SIMULATOR).build(), stages);
        assertTrue(stages.ran.contains("commander-init forecourt=true"), stages.ran.toString());
    }

    @Test
    void aFailedProvisionFailsTheStepWithItsLastErrorLineAndStopsThere() throws IOException {
        Stages stages = new Stages();
        stages.provision = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("provision"), stages.ran);
        assertEquals("provision: a target is not provisioned (its report is above)", out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("provision")));
    }

    @Test
    void aFailedInitIsTheStepsReasonInItsOwnWords() throws IOException {
        Stages stages = new Stages();
        stages.init = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertFalse(out.keepGoing());
        assertEquals("commander init on commander.invalid failed: DeviceError: cfuelinit: CGIPortal.InternalError",
                out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("commander-init")));
    }

    @Test
    void aFailedPrepStopsBeforeTheInits() throws IOException {
        Stages stages = new Stages();
        stages.prep = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);
        assertFalse(out.keepGoing());
        assertEquals(List.of("provision", "commander-prep"), stages.ran);
        assertEquals("commander-prep failed", out.step().detail());
    }

    @Test
    void coverageAskedForIsUnavailableBeforeProvision() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).coverage(true).build(), stages);
        assertFalse(out.keepGoing());
        assertTrue(stages.ran.isEmpty());
        assertTrue(out.step().detail().contains("JaCoCo coverage's launch wiring"), out.step().detail());
    }

    @Test
    void aRigRunWithNoLoadedRigFails() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages, Map.of(), false);
        assertFalse(out.keepGoing());
        assertTrue(stages.ran.isEmpty());
        assertTrue(out.step().detail().startsWith("the rig file did not load"));
    }

    @Test
    void theSnapshotSwitchDropsTheSnapshotWarning() throws IOException {
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), new Stages(),
                Map.of(CommanderInit.NO_SNAPSHOT_ENV, "1"), true);
        assertFalse(out.rows().containsKey(CommanderConfigurationStep.SNAPSHOT_ROW));
    }

    @Test
    void onceNothingIsMissingPaymentReadyRunsLastAndIsAWarnNeverAFailure() throws IOException {
        Stages stages = new Stages();
        stages.missing = "";
        stages.ready = false;
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);

        assertTrue(out.keepGoing());
        assertEquals(StepResult.COMPLETE, out.step().state());
        assertEquals("payment-ready", stages.ran.get(stages.ran.size() - 1));
        assertEquals(SubstepOutcome.WARNED, out.rows().get(label("payment-ready")));
        assertEquals("payment-ready: NOT ready after 900s: payment controller down",
                out.details().get(label("payment-ready")));
        assertEquals(SubstepOutcome.WARNED, out.rows().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));

        Stages fine = new Stages();
        fine.missing = "";
        Outcome ready = run(LaunchConfig.forRig(LaunchFixtures.RIG), fine);
        assertEquals(SubstepOutcome.PASSED, ready.rows().get(label("payment-ready")));
    }

    // ------------------------------------------------------------ the clean install

    private static LaunchConfig psi(String branch, String build) {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch(branch).commanderBuild(build).build();
    }

    @Test
    void aNewInstallOrdersDownloadInstallResetProvisionPairProvisionThenPrep() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        Outcome out = run(psi("Solutions", "134"), stages);

        assertEquals(List.of("build Solutions 134 buypass", "upgrade Solutions134/buypass", "psi-init --no-pairing",
                "provision", "psi-init", "provision", "commander-prep", "commander-init forecourt=false"),
                stages.ran);
        assertTrue(out.ctx().commanderReached());
        assertTrue(out.ctx().commanderLoginReady());
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("build")));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("upgrade")));
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("psi-init")));
        assertEquals("Download the Commander build", label("build"));
        assertEquals("Install the Commander build", label("upgrade"));
        assertEquals("PSI init (Commander password, register pairing)", label("psi-init"));
    }

    @Test
    void aFailedPasswordResetLeavesTheLoginNotReadyAndStopsBeforeProvision() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.psiNoPairing = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("build Solutions 134 buypass", "upgrade Solutions134/buypass", "psi-init --no-pairing"),
                stages.ran);
        assertFalse(out.ctx().commanderLoginReady(), "the day close must not log in with the lab password");
        assertTrue(out.ctx().commanderReached());
        assertEquals("psi-init failed: changepasswd still faulting after 6 attempts", out.step().detail());
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("psi-init")));
    }

    @Test
    void aFailedPairingStopsTheStepButThePasswordIsAlreadyReset() throws IOException {
        // As the Python: the --no-pairing reset already made the login usable; pairing failing does not undo that.
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.psiPairing = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals("psi-init", stages.ran.get(stages.ran.size() - 1));
        assertTrue(out.ctx().commanderLoginReady());
    }

    @Test
    void aFailedInstallStopsWithTheLoginNotReady() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.install = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("build Solutions 134 buypass", "upgrade Solutions134/buypass"), stages.ran);
        assertFalse(out.ctx().commanderLoginReady());
        assertEquals("upgrade: install_commander failed: 'Installation Failed'", out.step().detail());
    }

    @Test
    void aFailedDownloadInstallsNothingAndLeavesTheLoginAlone() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.download = false;
        Outcome out = run(psi("Solutions", "134"), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("build Solutions 134 buypass"), stages.ran);
        assertTrue(out.ctx().commanderLoginReady(), "nothing was installed, so the password is still the lab's");
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("build")));
    }

    @Test
    void noBuildChosenInstallsTheNewestAlreadyDownloaded() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.newest = Optional.of(stages.root.resolve("Solutions90").resolve("buypass"));
        Outcome out = run(psi("", ""), stages);

        assertEquals(List.of("newest buypass", "upgrade Solutions90/buypass", "psi-init --no-pairing", "provision",
                "psi-init", "provision", "commander-prep", "commander-init forecourt=false"), stages.ran);
        assertFalse(out.rows().containsKey(label("build")));
    }

    @Test
    void noBuildChosenAndNoneDownloadedFailsNamingTheRoot() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        Outcome out = run(psi("", ""), stages);

        assertFalse(out.keepGoing());
        assertEquals(List.of("newest buypass"), stages.ran);
        assertTrue(out.step().detail().startsWith("no build under " + stages.root + " holds buypass"),
                out.step().detail());
    }

    @Test
    void aChosenBuildWithModeSkipDownloadsNothing() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).commanderBranch("Solutions")
                .commanderBuild("134").build(), stages);

        assertEquals("provision", stages.ran.get(0));
        assertEquals("Solutions 134: Upgrade mode is Skip, nothing to install", out.details().get(label("build")));
        assertTrue(out.ctx().commanderLoginReady());
    }

    @Test
    void vhqIsUnavailableBeforeAnythingRuns() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(UpgradeMode.VHQ).build(), stages);
        assertFalse(out.keepGoing());
        assertTrue(stages.ran.isEmpty());
        assertTrue(out.step().detail().contains("petrotest/upgrade/vhq.py"), out.step().detail());
    }

    @Test
    void aFakePosNewInstallInstallsNothing() throws IOException {
        Stages stages = new Stages();
        Outcome out = run(new LaunchConfig.Builder("").fakePos(true).upgradeMode(UpgradeMode.NEW_INSTALL)
                .commanderBranch("Solutions").commanderBuild("134").build(), stages, Map.of(), false);

        assertTrue(out.keepGoing());
        assertTrue(stages.ran.isEmpty(), stages.ran.toString());
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("upgrade")));
        assertTrue(out.ctx().commanderLoginReady());
    }

    @Test
    void theInstalledBuildIsReadAndFallsBackToTheBuildThisRunInstalled() throws IOException {
        Stages stages = new Stages();
        stages.root = dir.resolve("builds");
        stages.missing = "";
        stages.installed = new Installed.Identification("Solutions134", "Solutions134 (Commit_Hash abc, built t)");
        Outcome out = run(LaunchConfig.forRig(LaunchFixtures.RIG), stages);
        assertEquals(SubstepOutcome.PASSED, out.rows().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));
        assertEquals("Solutions134", out.ctx().installedBuild());

        Stages unread = new Stages();
        unread.root = dir.resolve("builds");
        unread.missing = "";
        Outcome installedHere = run(psi("Solutions", "134"), unread);
        assertEquals("Solutions134", installedHere.ctx().installedBuild());
        assertEquals("Solutions134, installed by this run (DeviceError: could not read suiteinfo.dat)",
                installedHere.details().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));

        Stages unknown = new Stages();
        unknown.missing = "";
        Outcome notHere = run(LaunchConfig.forRig(LaunchFixtures.RIG), unknown);
        assertEquals("", notHere.ctx().installedBuild());
        assertEquals(SubstepOutcome.WARNED, notHere.rows().get(CommanderConfigurationStep.INSTALLED_BUILD_ROW));
    }

    @Test
    void theDownloadsFieldsDefaultEmptyAndAreTrimmed() {
        LaunchConfig plain = LaunchConfig.forRig(LaunchFixtures.RIG);
        assertEquals("", plain.commanderBranch());
        assertEquals("", plain.simBuild());
        assertFalse(plain.commanderBuildChosen());
        LaunchConfig chosen = new LaunchConfig.Builder(LaunchFixtures.RIG).commanderBranch(" Solutions ")
                .commanderBuild("134").simBranch("master").simBuild("74").build();
        assertTrue(chosen.commanderBuildChosen());
        assertEquals("Solutions", chosen.commanderBranch());
        assertEquals("master", chosen.simBranch());
        assertEquals("74", chosen.simBuild());
    }

    @Test
    void theProductionStepIsTheOneTheLauncherRuns() {
        CommanderConfigurationStep step = new CommanderConfigurationStep();
        assertEquals(LaunchStepNames.COMMANDER, step.name());
        assertEquals(CommanderConfigurationStep.MISSING_AFTER_INIT, CommanderStages.real(Map.of()).missingAfterInit());
    }
}
