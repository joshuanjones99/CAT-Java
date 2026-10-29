package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
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
import com.verifone.cat.commander.Say;
import com.verifone.cat.report.events.SubstepOutcome;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@link InstallCommanderBuild} over recording {@link InstallCommanderBuild.Stages}: the Python's
 * {@code install_commander_build} order and flags for classic, VHQ, a local build pick and a foundation pick. No stage
 * reaches Artifactory or a device.
 */
class InstallCommanderBuildTest {

    @TempDir
    Path dir;

    /** Records every stage call, and answers as told. */
    private final class Recorder implements InstallCommanderBuild.Stages {
        final List<String> ran = new ArrayList<>();
        boolean install = true;
        final List<Path> local = new ArrayList<>();

        @Override
        public Path buildsRoot() {
            return dir.resolve("builds");
        }

        @Override
        public boolean downloadCommanderBuild(String branch, String build, String dist, UpgradeMode mode,
                String foundationBranch, String foundationBuild, Say say) {
            ran.add(("build " + branch + " " + build + " " + dist + " " + mode.wire() + " " + foundationBranch + " "
                    + foundationBuild).strip());
            return true;
        }

        @Override
        public Optional<Path> newestPsiBuild(String dist) {
            ran.add("newest " + dist);
            return Optional.empty();
        }

        @Override
        public boolean installNewInstall(Rig rig, Path buildDir, Say say) {
            ran.add("new_install " + rel(buildDir));
            return install;
        }

        @Override
        public boolean installUpgrade(Rig rig, UpgradeMode mode, Path buildDir, String dist, Say say) {
            ran.add("upgrade " + mode.wire() + " " + (buildDir == null ? "-" : rel(buildDir)) + " " + dist);
            if (!install) {
                say.err("upgrade: cgi_upgrade_dance: failed — timed out");
            }
            return install;
        }

        @Override
        public boolean psiInit(Rig rig, boolean pair, Say say) {
            ran.add(pair ? "psi-init" : "psi-init --no-pairing");
            return true;
        }

        @Override
        public List<Path> localBuildDirs(UpgradeMode mode, String dist) {
            ran.add("local " + mode.wire() + " " + dist);
            return local;
        }

        private String rel(Path path) {
            return buildsRoot().relativize(path).toString().replace('\\', '/');
        }
    }

    private record Outcome(InstallCommanderBuild.Result result, StepEmitter step, Map<String, String> rows,
            Map<String, String> details, RunContext ctx) {}

    private int runs;

    private Outcome run(LaunchConfig config, Recorder stages) throws IOException {
        Path folder = dir.resolve("run" + runs++);
        RunContext ctx = new RunContext(config, LaunchFixtures.paths(dir), "cccccccccccc", folder,
                RunEvents.open(folder, Clock.systemUTC()), new PauseGate(), Clock.systemUTC());
        ctx.rig(RigLoader.loadResolved(LaunchFixtures.RIG, ctx.paths().rigsDir()));
        StepEmitter step = new StepEmitter(ctx, LaunchStepNames.COMMANDER);
        InstallCommanderBuild.Result result = InstallCommanderBuild.run(ctx, step, stages, ctx.rig().rig());
        ctx.closeEvents();
        Map<String, String> rows = new LinkedHashMap<>();
        Map<String, String> details = new LinkedHashMap<>();
        for (JsonNode event : LaunchFixtures.events(ctx.runFolder())) {
            if (event.get("type").asText().equals("step.check")) {
                rows.put(event.get("label").asText(), event.get("outcome").asText());
                details.put(event.get("label").asText(), event.get("detail").asText());
            }
        }
        return new Outcome(result, step, rows, details, ctx);
    }

    private static String label(String command) {
        return CommanderConfigurationStep.label(command);
    }

    private static LaunchConfig.Builder config(UpgradeMode mode) {
        return new LaunchConfig.Builder(LaunchFixtures.RIG).upgradeMode(mode);
    }

    @Test
    void aChosenClassicBuildIsDownloadedForAuThenInstalledFromItsFolder() throws IOException {
        Recorder stages = new Recorder();
        Outcome out = run(config(UpgradeMode.CLASSIC).commanderBranch("Solutions").commanderBuild("134").build(),
                stages);

        assertTrue(out.result().ok());
        assertTrue(out.result().ran());
        assertEquals(List.of("build Solutions 134 buypass classic", "upgrade classic Solutions134/buypass buypass"),
                stages.ran);
        assertEquals(SubstepOutcome.PASSED, out.rows().get(label("upgrade")));
        assertTrue(out.ctx().commanderLoginReady(), "only a PSI install resets the manager password");
    }

    @Test
    void classicWithNoBuildChosenInstallsTheNewestThroughTheUpgradeItself() throws IOException {
        Recorder stages = new Recorder();
        Outcome out = run(config(UpgradeMode.CLASSIC).build(), stages);

        assertTrue(out.result().ok());
        assertEquals(List.of("upgrade classic - buypass"), stages.ran);
    }

    @Test
    void aFailedClassicInstallFailsTheStepWithItsReason() throws IOException {
        Recorder stages = new Recorder();
        stages.install = false;
        Outcome out = run(config(UpgradeMode.CLASSIC).build(), stages);

        assertFalse(out.result().ok());
        assertEquals(StepResult.FAILED, out.step().state());
        assertTrue(out.step().detail().contains("cgi_upgrade_dance"), out.step().detail());
    }

    @Test
    void vhqDownloadsNothingAndItsInstallIsTheUpgradeStage() throws IOException {
        Recorder stages = new Recorder();
        Outcome out = run(config(UpgradeMode.VHQ).commanderBranch("Solutions").commanderBuild("134").build(), stages);

        assertEquals(List.of("upgrade vhq - buypass"), stages.ran);
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("build")));
        assertEquals("Solutions 134: vhq downloads no build", out.details().get(label("build")));
    }

    @Test
    void theFoundationPickReachesTheDownload() throws IOException {
        Recorder stages = new Recorder();
        Outcome out = run(config(UpgradeMode.NEW_INSTALL).commanderBranch("master").commanderBuild("241")
                .foundationBranch("master").foundationBuild("220").build(), stages);

        assertTrue(out.result().ok());
        assertEquals(List.of("build master 241 buypass new_install master 220", "new_install master-241/buypass",
                "psi-init --no-pairing"), stages.ran);
    }

    @Test
    void aPickedLocalBuildIsTheOneInstalledNotTheNewest() throws IOException {
        Recorder stages = new Recorder();
        stages.local.add(stages.buildsRoot().resolve("Solutions90").resolve("buypass"));
        stages.local.add(stages.buildsRoot().resolve("Solutions65").resolve("buypass"));
        Outcome out = run(config(UpgradeMode.NEW_INSTALL).localBuild("Solutions65").build(), stages);

        assertTrue(out.result().ok());
        assertEquals(List.of("local new_install buypass", "new_install Solutions65/buypass", "psi-init --no-pairing"),
                stages.ran);
    }

    @Test
    void aPickedLocalBuildThatIsNotThereFailsSayingWhatItNeeds() throws IOException {
        Recorder stages = new Recorder();
        Outcome out = run(config(UpgradeMode.CLASSIC).localBuild("Solutions65").build(), stages);

        assertFalse(out.result().ok());
        assertEquals(List.of("local classic buypass"), stages.ran);
        assertEquals(SubstepOutcome.FAILED, out.rows().get(label("upgrade")));
        assertEquals("Solutions65 is not a buypass build classic can install from in " + stages.buildsRoot()
                + " (it needs overlay.zip and Auto_Upgrade.jar)", out.step().detail());
    }

    @Test
    void aChosenBuildAlreadyOnDiskIsNotDownloadedUnlessItWasMerged() throws IOException {
        Recorder stages = new Recorder();
        Path held = stages.buildsRoot().resolve("Solutions134").resolve("buypass");
        Files.createDirectories(held);
        Files.writeString(held.resolve("overlay.zip"), "o");
        Files.writeString(held.resolve("Auto_Upgrade.jar"), "a");
        run(config(UpgradeMode.CLASSIC).commanderBranch("Solutions").commanderBuild("134").build(), stages);
        assertEquals(List.of("upgrade classic Solutions134/buypass buypass"), stages.ran);

        Files.writeString(held.resolve(".cat-merge.json"), "{}");
        stages.ran.clear();
        run(config(UpgradeMode.CLASSIC).commanderBranch("Solutions").commanderBuild("134").build(), stages);
        assertEquals("build Solutions 134 buypass classic", stages.ran.get(0), "a merged build checks its stamp");
    }

    @Test
    void underFakePosAClassicInstallTouchesNothing() throws IOException {
        Recorder stages = new Recorder();
        Outcome out = run(config(UpgradeMode.CLASSIC).fakePos(true).build(), stages);

        assertTrue(out.result().ok());
        assertTrue(stages.ran.isEmpty(), stages.ran.toString());
        assertEquals(SubstepOutcome.SKIPPED, out.rows().get(label("upgrade")));
    }

    @Test
    void theStageLineThatNamesAFailedStepIsTheReason() {
        assertEquals("cgi_upgrade_dance: failed — timed out", InstallCommanderBuild.failureLine(List.of(
                "overlay pushed", "bypass_mem_size_check: done\ncgi_upgrade_dance: failed — timed out\nFAILED")));
        assertEquals("missing: x", InstallCommanderBuild.failureLine(List.of("missing: x")));
    }
}
