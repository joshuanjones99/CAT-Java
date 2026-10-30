package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.build.Installed;
import com.verifone.cat.closeday.CloseDay;
import com.verifone.cat.commander.RigCommand;
import com.verifone.cat.commander.Say;
import com.verifone.cat.commander.links.CommanderLinks;
import com.verifone.cat.provision.ShimStamp;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLease;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.upgrade.StepResult;

/**
 * {@code install-resume} over recording stages and a recorded root-access resume: the order (HANDOFF §6 item 1 from the
 * root-access push on), a stop after pairing by default, {@code --continue} only with the simulators listening, the
 * run lease, the build choice, the pos-shim check before provision, a stop at the first failure, and the production
 * resume's check of the build's tools before anything is sent ({@code NewInstallTest} drives {@code resumeRootAccess}
 * itself). Nothing reaches a rig, a port or the real lease directory.
 */
class InstallResumeTest {

    @TempDir
    Path dir;

    private Rig rig;
    private Path leases;
    private final List<String> ran = new ArrayList<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Set<Integer> listening = Set.of(9001, 41107);

    @BeforeEach
    void setUp() throws IOException {
        LaunchPaths paths = LaunchFixtures.paths(dir);
        rig = RigLoader.loadResolved(LaunchFixtures.RIG, paths.rigsDir()).rig();
        leases = Files.createDirectories(dir.resolve("leases-resume"));
    }

    private final class Stages implements CommanderStages {
        String failing = "";
        boolean drift;
        List<Path> staged = List.of(dir.resolve("builds").resolve("Solutions134").resolve("buypass"));
        String installed = "";
        Path payload;

        private boolean stage(String name) {
            ran.add(name);
            return !name.equals(failing);
        }

        @Override
        public Path buildsRoot() {
            return dir.resolve("builds");
        }

        @Override
        public List<Path> stagedBuilds(String dist) {
            ran.add("staged " + dist);
            return staged;
        }

        @Override
        public Path posShimPayload() {
            return payload;
        }

        @Override
        public Path posShimRepoRoot() {
            return dir.resolve("python-cat");
        }

        @Override
        public boolean buildPosShim(Rig rig, Path petroapps, Say say) {
            return stage("pos-shim build");
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
            if (installed.isEmpty()) {
                throw new IllegalStateException("suiteinfo.dat unread");
            }
            return new Installed.Identification(installed, installed + " (Commit_Hash abc)");
        }
    }

    private Map<String, String> env() {
        return Map.of(RigLease.LEASE_DIR_ENV, leases.toString(), "PETROAPPS_ROOT", dir.resolve("petroapps").toString());
    }

    private int run(Stages stages, InstallResume.RootAccessResume resume, String... argv) {
        InstallResume command = new InstallResume((name, env) -> rig, env -> stages, resume,
                port -> listening.contains(port));
        return command.run(argv, env(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private InstallResume.RootAccessResume pushes() {
        return (rig, buildDir, environ, log) -> {
            ran.add("root-access " + dir.relativize(buildDir).toString().replace('\\', '/'));
            return List.of(StepResult.done("push_commander_root_access"), StepResult.done("cgilink_commander"));
        };
    }

    private String said() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void withContinueTheChainRunsStepFoursOrderFromTheRootAccessPushOn() {
        Stages staged = new Stages();
        staged.drift = true;
        int code = run(staged, pushes(), "--rig", "bench", "--continue");

        assertEquals(RigCommand.EXIT_OK, code, errors());
        assertEquals(List.of("staged buypass", "root-access builds/Solutions134/buypass", "psi-init --no-pairing",
                "provision", "psi-init", "provision", "commander-prep", "commander-init forecourt=true",
                "secondary-fep [none]", "payment-links", "emv-keys always", "register-app", "close-day",
                "payment-ready", "commander-snapshot capture bench ", "installed-build"), ran);
        assertTrue(said().contains("BUYPASS Host may answer nothing after the FEP drift fix"), said());
        assertTrue(said().contains("install-resume: step 4 done; run the plan with launch --upgrade skip"), said());
        assertTrue(said().contains("install-resume: build " + staged.staged.get(0) + " (the only one staged)"), said());
    }

    @Test
    void byDefaultItStopsAfterPairingAndPrintsTheNextLaunch() {
        Stages stages = new Stages();
        assertEquals(RigCommand.EXIT_OK, run(stages, pushes(), "--rig", "bench"), errors());

        assertEquals("provision", ran.get(ran.size() - 1));
        assertEquals(4, ran.stream().filter(s -> s.startsWith("psi-init") || s.equals("provision")).count());
        assertTrue(said().contains("launch --rig bench --upgrade skip --capture-snapshot"), said());
        assertFalse(ran.contains("commander-prep"));
    }

    @Test
    void stopAfterPairingIsStillAcceptedAndIsTheDefault() {
        Stages stages = new Stages();
        assertEquals(RigCommand.EXIT_OK, run(stages, pushes(), "--rig", "bench", "--stop-after-pairing"));
        assertEquals("provision", ran.get(ran.size() - 1));
    }

    @Test
    void continueIsRefusedBeforeAnythingIsSentWhenTheSimulatorsAreNotListening() {
        listening = Set.of(41107);
        Stages stages = new Stages();
        assertEquals(RigCommand.EXIT_FAILED, run(stages, pushes(), "--rig", "bench", "--continue"));

        assertEquals(List.of(), ran, "nothing sent");
        assertTrue(errors().contains("--continue needs the run's simulators on this PC, and Petro Simulator Suite "
                + "(port 9001) is not listening"), errors());
    }

    @Test
    void aHeldRunLeaseRefusesTheResume() {
        RigLease held = new RigLease(rig.name() + ".run", leases, "a test run");
        held.acquire(Duration.ofSeconds(5));
        try {
            assertEquals(RigCommand.EXIT_FAILED, run(new Stages(), pushes(), "--rig", "bench"));
        } finally {
            held.release();
        }
        assertEquals(List.of(), ran);
        assertTrue(errors().contains("is in use by another CAT run"), errors());

        // And the resume releases the lease it took.
        run(new Stages(), pushes(), "--rig", "bench");
        RigLease after = new RigLease(rig.name() + ".run", leases, "after");
        after.acquire(Duration.ofSeconds(5));
        after.release();
    }

    @Test
    void severalStagedBuildsNeedAFlagUnlessTheInstalledBuildNamesOne() throws IOException {
        Path b1082 = Files.createDirectories(dir.resolve("builds").resolve("Solutions1082").resolve("buypass"));
        Path b134 = Files.createDirectories(dir.resolve("builds").resolve("Solutions134").resolve("buypass"));
        Stages stages = new Stages();
        stages.staged = List.of(b1082, b134);

        assertEquals(RigCommand.EXIT_FAILED, run(stages, pushes(), "--rig", "bench"));
        assertTrue(errors().contains("2 builds are staged (Solutions1082, Solutions134)"), errors());
        assertFalse(ran.stream().anyMatch(s -> s.startsWith("root-access")), ran.toString());

        ran.clear();
        out.reset();
        stages.installed = "Solutions134";
        assertEquals(RigCommand.EXIT_OK, run(stages, pushes(), "--rig", "bench"), errors());
        assertTrue(ran.contains("root-access builds/Solutions134/buypass"), ran.toString());
        assertTrue(said().contains("(the Commander's installed build, Solutions134)"), said());

        ran.clear();
        out.reset();
        stages.installed = "";
        assertEquals(RigCommand.EXIT_OK, run(stages, pushes(), "--rig", "bench", "--build-label", "Solutions1082"));
        assertTrue(ran.contains("root-access builds/Solutions1082/buypass"), ran.toString());
        assertTrue(said().contains("(from --build-label)"), said());
    }

    @Test
    void thePosShimIsBuiltBeforeTheFirstProvisionWhenItIsNotTheSourcesBuild() throws IOException {
        Stages stages = new Stages();
        stages.payload = Files.createDirectories(dir.resolve("payload").resolve("pos"));
        Path script = ShimStamp.resolve(dir.resolve("python-cat"), ShimStamp.BUILD_SCRIPT);
        Files.createDirectories(script.getParent());
        Files.writeString(script, "# a test stand-in");

        assertEquals(RigCommand.EXIT_OK, run(stages, pushes(), "--rig", "bench"), errors());
        assertEquals(ran.indexOf("pos-shim build") + 1, ran.indexOf("psi-init --no-pairing"), ran.toString());
        assertTrue(ran.indexOf("pos-shim build") < ran.indexOf("provision"), ran.toString());
        assertTrue(said().contains("BUILD pos-shim — " + stages.payload + " lacks"), said());
    }

    @Test
    void aFailedPushStopsBeforePsiInit() {
        Stages stages = new Stages();
        int code = run(stages, (rig, buildDir, environ, log) -> List.of(
                StepResult.failed("push_commander_root_access", "root access on commander not confirmed within 1500 s")),
                "--rig", "bench", "--build-dir", dir.resolve("b").toString());
        assertEquals(RigCommand.EXIT_FAILED, code);
        assertEquals(List.of(), ran);
        assertTrue(errors().contains(
                "install-resume: push_commander_root_access failed: root access on commander not confirmed"));
    }

    @Test
    void aFailedStageStopsTheChainThere() {
        Stages stages = new Stages();
        stages.failing = "psi-init";
        assertEquals(RigCommand.EXIT_FAILED, run(stages, pushes(), "--rig", "bench", "--no-forecourt"));
        assertEquals("psi-init", ran.get(ran.size() - 1));
        assertTrue(errors().contains("install-resume: psi-init failed; the chain stops here"));
    }

    @Test
    void theProductionResumeNamesMissingToolsAndSendsNothing() {
        // Every tool path overridden into the temp folder, so the check never looks at the real C:\CICD.
        Map<String, String> env = Map.of(
                RigLease.LEASE_DIR_ENV, leases.toString(),
                "PETROTEST_JAVA17_BIN", dir.resolve("java.exe").toString(),
                "PETROTEST_NETLOADER_JAR", dir.resolve("NetLoaderClient.jar").toString(),
                "PETROTEST_UPGRADE_ROOT_ACCESS_PATH", dir.resolve("root.tgz").toString(),
                "PETROTEST_UPGRADE_GEMREG_LICENSE_PATH", dir.resolve("gemreg.tgz").toString());
        Path build = dir.resolve("builds").resolve("Solutions134").resolve("buypass");
        InstallResume command = new InstallResume((name, environ) -> rig, environ -> new Stages(),
                InstallResume.PRODUCTION_RESUME, port -> true);
        int code = command.run(new String[] {"--rig", "bench", "--build-dir", build.toString()}, env,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(RigCommand.EXIT_FAILED, code);
        assertTrue(ran.isEmpty(), ran.toString());
        assertTrue(errors().contains("install-resume: root_access_tools failed: the PSI install needs these files, "
                + "missing: " + build.resolve("overlay.zip")), errors());
    }
}
