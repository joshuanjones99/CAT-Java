package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.Installed;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.upgrade.StepResult;

/**
 * {@code install-resume} over recording stages and a recorded root-access resume: the order (HANDOFF §6 item 1 from the
 * root-access push on), a stop at the first failure, {@code --stop-after-pairing}, and the production resume's check of
 * the build's tools before anything is sent ({@code NewInstallTest} drives {@code resumeRootAccess} itself). Nothing
 * reaches a rig.
 */
class InstallResumeTest {

    @TempDir
    Path dir;

    private Rig rig;
    private final List<String> ran = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        rig = RigLoader.loadResolved(LaunchFixtures.RIG, paths.rigsDir()).rig();
    }

    private final class Stages implements CommanderStages {
        String failing = "";
        boolean drift;

        private boolean stage(String name) {
            ran.add(name);
            return !name.equals(failing);
        }

        @Override
        public Path buildsRoot() {
            return dir.resolve("builds");
        }

        @Override
        public Optional<Path> newestBuild(String dist) {
            ran.add("newest " + dist);
            return Optional.of(dir.resolve("builds").resolve("Solutions134").resolve(dist));
        }

        @Override
        public boolean psiInit(Rig rig, boolean pair, Say say) {
            return stage(pair ? "psi-init" : "psi-init --no-pairing");
        }

        @Override
        public boolean provision(Rig rig, Say say) {
            return stage("provision");
        }

        @Override
        public boolean commanderPrep(Rig rig, Say say) {
            return stage("commander-prep");
        }

        @Override
        public boolean commanderInit(Rig rig, boolean forecourt, Say say) {
            return stage("commander-init forecourt=" + forecourt);
        }

        @Override
        public boolean paymentReady(Rig rig, Say say) {
            return stage("payment-ready");
        }

        @Override
        public boolean secondaryFep(Rig rig, List<String> feps, String serviceId, Map<String, String> csiteEnv,
                Say say) {
            return stage("secondary-fep " + feps);
        }

        @Override
        public boolean paymentLinks(Rig rig, Say say) {
            if (drift) {
                say.out(CommanderLinks.FEP_DRIFT_FIXED + " (sslEnabled)");
            }
            return stage("payment-links");
        }

        @Override
        public boolean emvKeys(Rig rig, String tables, Say say) {
            return stage("emv-keys " + tables);
        }

        @Override
        public boolean registerApp(Rig rig, Say say) {
            return stage("register-app");
        }

        @Override
        public CloseDay.Outcome closeDay(Rig rig, Say say) {
            ran.add("close-day");
            return CloseDay.Outcome.CLOSED;
        }

        @Override
        public boolean snapshotCapture(String rigName, String buildLabel, Say say) {
            return stage("commander-snapshot capture " + rigName + " " + buildLabel);
        }

        @Override
        public Installed.Identification installedBuild(Rig rig, String dist) {
            ran.add("installed-build");
            throw new IllegalStateException("suiteinfo.dat unread");
        }
    }

    private int run(Stages stages, InstallResume.RootAccessResume resume, String... argv) {
        InstallResume command = new InstallResume((name, env) -> rig, env -> stages, resume);
        return command.run(argv, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private InstallResume.RootAccessResume pushes() {
        return (rig, buildDir, environ, log) -> {
            ran.add("root-access " + dir.relativize(buildDir).toString().replace('\\', '/'));
            return List.of(StepResult.done("push_commander_root_access"), StepResult.done("cgilink_commander"));
        };
    }

    @Test
    void theChainRunsStepFoursOrderFromTheRootAccessPushOn() {
        Stages stages = new Stages();
        stages.drift = true;
        int code = run(stages, pushes(), "--rig", "bench", "--build-label", "Solutions134");

        assertEquals(RigCommand.EXIT_OK, code, err.toString(StandardCharsets.UTF_8));
        assertEquals(List.of("newest buypass", "root-access builds/Solutions134/buypass", "psi-init --no-pairing",
                "provision", "psi-init", "provision", "commander-prep", "commander-init forecourt=true",
                "secondary-fep [none]", "payment-links", "emv-keys always", "register-app", "close-day",
                "payment-ready", "commander-snapshot capture bench Solutions134", "installed-build"), ran);
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("BUYPASS Host may answer nothing after the FEP drift fix"), said);
        assertTrue(said.contains("install-resume: step 4 done; run the plan with launch --upgrade skip"), said);
    }

    @Test
    void aFailedPushStopsBeforePsiInit() {
        Stages stages = new Stages();
        int code = run(stages, (rig, buildDir, environ, log) -> List.of(
                StepResult.failed("push_commander_root_access", "root access on commander not confirmed within 1500 s")),
                "--rig", "bench", "--build-dir", dir.resolve("b").toString());
        assertEquals(RigCommand.EXIT_FAILED, code);
        assertEquals(List.of(), ran);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains(
                "install-resume: push_commander_root_access failed: root access on commander not confirmed"));
    }

    @Test
    void aFailedStageStopsTheChainThere() {
        Stages stages = new Stages();
        stages.failing = "psi-init";
        assertEquals(RigCommand.EXIT_FAILED, run(stages, pushes(), "--rig", "bench", "--no-forecourt"));
        assertEquals("psi-init", ran.get(ran.size() - 1));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("install-resume: psi-init failed; the chain stops "
                + "here"));
    }

    @Test
    void stopAfterPairingLeavesTheRestToTheNextLaunch() {
        Stages stages = new Stages();
        assertEquals(RigCommand.EXIT_OK, run(stages, pushes(), "--rig", "bench", "--stop-after-pairing"));
        assertEquals("provision", ran.get(ran.size() - 1));
        assertEquals(4, ran.stream().filter(s -> s.startsWith("psi-init") || s.equals("provision")).count());
    }

    @Test
    void theProductionResumeNamesMissingToolsAndSendsNothing() {
        // Every tool path overridden into the temp folder, so the check never looks at the real C:\CICD.
        Map<String, String> env = Map.of(
                "PETROTEST_JAVA17_BIN", dir.resolve("java.exe").toString(),
                "PETROTEST_NETLOADER_JAR", dir.resolve("NetLoaderClient.jar").toString(),
                "PETROTEST_UPGRADE_ROOT_ACCESS_PATH", dir.resolve("root.tgz").toString(),
                "PETROTEST_UPGRADE_GEMREG_LICENSE_PATH", dir.resolve("gemreg.tgz").toString());
        Path build = dir.resolve("builds").resolve("Solutions134").resolve("buypass");
        InstallResume command = new InstallResume((name, environ) -> rig, environ -> new Stages(),
                InstallResume.PRODUCTION_RESUME);
        int code = command.run(new String[] {"--rig", "bench", "--build-dir", build.toString()}, env,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(RigCommand.EXIT_FAILED, code);
        assertTrue(ran.isEmpty(), ran.toString());
        String said = err.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("install-resume: root_access_tools failed: the PSI install needs these files, "
                + "missing: " + build.resolve("overlay.zip")), said);
    }
}
