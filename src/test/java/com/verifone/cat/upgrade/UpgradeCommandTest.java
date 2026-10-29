package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.launch.UpgradeMode;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java upgrade}'s build resolution, dispatch and output: the Python's {@code test_upgrade_cli}. No install
 * runs: the paths are recorders, so what these assert is the choice of directory, the tools and the exit code.
 */
class UpgradeCommandTest {

    private static final List<String> PSI_FILES = List.of("overlay.zip", "Petro_Suite_Installer.jar",
            "Auto_Upgrade.jar");

    @TempDir
    Path root;

    /** Records the build directory each path was pointed at; installs nothing. */
    private static final class Recorder implements UpgradePaths {
        final List<String> seen = new ArrayList<>();
        List<StepResult> results = List.of(StepResult.done("install"));

        @Override
        public List<StepResult> classic(Rig rig, ClassicUpgradeTools tools, Map<String, String> env,
                Consumer<String> log) {
            seen.add("classic " + tools.overlayPath());
            return results;
        }

        @Override
        public List<StepResult> newInstall(Rig rig, NewInstallTools tools, Map<String, String> env,
                Consumer<String> log) {
            seen.add("new_install " + tools.buildDir());
            return results;
        }

        @Override
        public ClassicUpgradeTools classicTools(Path buildDir) {
            return new ClassicUpgradeTools("au", "nl", buildDir.resolve("overlay.zip").toString(), "r", "g", null);
        }

        @Override
        public NewInstallTools newInstallTools(Path buildDir, Map<String, String> env) {
            return new NewInstallTools(buildDir, "java", "nl", "r", "g");
        }
    }

    /** {@link UpgradeCommand.Paths}, named for the test. */
    private interface UpgradePaths extends UpgradeCommand.Paths {}

    private Rig rig() throws Exception {
        return RigLoader.loadFile(Path.of(getClass().getResource("/upgrade/rigs/psi.yaml").toURI())).rig();
    }

    private Path stage(String build, String dist, List<String> files) throws IOException {
        Path dest = root.resolve(build).resolve(dist);
        Files.createDirectories(dest);
        for (String name : files) {
            Files.writeString(dest.resolve(name), "x");
        }
        return dest;
    }

    private int upgrade(Recorder paths, UpgradeMode mode, Path buildDir, String dist, List<String> out)
            throws Exception {
        return new UpgradeCommand(paths).upgrade(rig(), mode, buildDir, dist, Map.of(), root, out::add);
    }

    @Test
    void skipDoesNothing() throws Exception {
        Recorder paths = new Recorder();
        List<String> out = new ArrayList<>();
        assertEquals(0, upgrade(paths, UpgradeMode.SKIP, null, "buypass", out));
        assertEquals(List.of("upgrade mode is 'skip' — nothing to do."), out);
        assertTrue(paths.seen.isEmpty());
    }

    @Test
    void withNoBuildDirTheNewestBuildAlreadyDownloadedIsInstalled() throws Exception {
        stage("Solutions875", "buypass", PSI_FILES);
        Path newest = stage("Solutions1052", "buypass", PSI_FILES);
        Recorder paths = new Recorder();
        List<String> out = new ArrayList<>();

        assertEquals(0, upgrade(paths, UpgradeMode.NEW_INSTALL, null, "buypass", out));
        assertEquals(List.of("new_install " + newest), paths.seen);
        assertEquals("no --build-dir given; using the build already downloaded: " + newest, out.get(0));
        assertEquals("install: done\nOK", out.get(out.size() - 1));
    }

    @Test
    void anExplicitBuildDirStillWins() throws Exception {
        stage("Solutions1052", "buypass", PSI_FILES);
        Path chosen = stage("Solutions900", "buypass", PSI_FILES);
        Recorder paths = new Recorder();
        assertEquals(0, upgrade(paths, UpgradeMode.NEW_INSTALL, chosen, null, new ArrayList<>()));
        assertEquals(List.of("new_install " + chosen), paths.seen);
    }

    @Test
    void noBuildAnywhereNamesTheRootItLookedIn() throws Exception {
        Recorder paths = new Recorder();
        List<String> out = new ArrayList<>();
        assertEquals(1, upgrade(paths, UpgradeMode.NEW_INSTALL, null, "buypass", out));
        assertTrue(out.get(0).startsWith("new_install found no build to install under " + root), out.toString());
        assertTrue(paths.seen.isEmpty());
    }

    @Test
    void classicFindsTheNewestBuildWithItsOneJar() throws Exception {
        Path au = stage("master-60", "buypass", List.of("overlay.zip", "Auto_Upgrade.jar"));
        Recorder paths = new Recorder();
        assertEquals(0, upgrade(paths, UpgradeMode.CLASSIC, null, "buypass", new ArrayList<>()));
        assertEquals(List.of("classic " + au.resolve("overlay.zip")), paths.seen);
    }

    @Test
    void classicWithNoBuildAndNoVariablesFailsNamingThem() throws Exception {
        Recorder paths = new Recorder();
        List<String> out = new ArrayList<>();
        assertEquals(1, upgrade(paths, UpgradeMode.CLASSIC, null, "buypass", out));
        assertTrue(out.get(0).contains("PETROTEST_AUTO_UPGRADE_JAR"), out.toString());
        assertTrue(paths.seen.isEmpty());
    }

    @Test
    void aFailedStepFailsTheUpgradeAndTheSummarySaysWhich() throws Exception {
        Path chosen = stage("Solutions900", "buypass", PSI_FILES);
        Recorder paths = new Recorder();
        paths.results = List.of(StepResult.done("bypass_mem_size_check"),
                StepResult.failed("cgi_upgrade_dance", "timed out"));
        List<String> out = new ArrayList<>();
        assertEquals(1, upgrade(paths, UpgradeMode.CLASSIC, chosen, null, out));
        assertEquals("bypass_mem_size_check: done\ncgi_upgrade_dance: failed — timed out\nFAILED",
                out.get(out.size() - 1));
    }

    @Test
    void aRequestedVhqUpgradeDoesNotReportSuccess() throws Exception {
        List<String> out = new ArrayList<>();
        assertEquals(1, upgrade(new Recorder(), UpgradeMode.VHQ, null, null, out));
        assertTrue(String.join("\n", out).contains("not implemented"), out.toString());
    }

    @Test
    void theCommandLineChecksItsFlagsBeforeLoadingARig() throws Exception {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UpgradeCommand command = new UpgradeCommand(new Recorder());
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(2, command.run(new String[] {"--mode", "skip"}, Map.of(), o, e));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--rig is required"));
        assertEquals(2, command.run(new String[] {"--rig", "x", "--mode", "au"}, Map.of(), o, e));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("invalid choice: 'au'"));
        assertEquals(2, command.run(new String[] {"--rig", "x", "--bogus"}, Map.of(), o, e));
    }

    @Test
    void theCommandLineRunsTheModeOnTheNamedRig() throws Exception {
        Path rigFile = Path.of(getClass().getResource("/upgrade/rigs/psi.yaml").toURI());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        int code = new UpgradeCommand(new Recorder()).run(new String[] {"upgrade", "--rig", rigFile.toString(),
                "--mode", "skip"}, Map.of("CAT_BUILDS_ROOT", root.toString()), o, o);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("nothing to do"));
    }
}
