package com.verifone.cat.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.commander.connect.CommanderConnect;

/** The saved-choices defaults: flag over the Java file over the Python file over the default, read-only. */
class LastConfigTest {

    @TempDir
    Path dir;

    private Path write(String name, String json) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    private static LaunchConfig config(LastConfig saved, String... argv) {
        return LaunchCommand.Options.parse(argv).toConfig(saved, Map.of(), java.util.List.of(), java.util.List.of());
    }

    @Test
    void theJavaFileWinsKeyByKeyAndThePythonFileFillsIn() throws IOException {
        Path java = write("java.json", "{\"config-upgrade\": \"classic\", \"config-dist\": \"chevron\"}");
        Path python = write("python.json", "{\"config-upgrade\": \"new_install\", \"config-exec\": \"BDD\","
                + " \"config-tp-branch\": \"release\"}");

        LastConfig saved = LastConfig.load(java, python);

        assertEquals("classic", saved.upgrade());
        assertEquals("chevron", saved.dist());
        assertEquals("BDD", saved.exec());
        assertEquals("release", saved.testPlanBranch());
    }

    @Test
    void precedenceIsFlagThenJavaThenPythonThenDefault() throws IOException {
        Path java = write("java.json", "{\"config-upgrade\": \"classic\"}");
        Path python = write("python.json", "{\"config-upgrade\": \"new_install\", \"config-exec\": \"BDD\"}");
        LastConfig saved = LastConfig.load(java, python);

        LaunchConfig flagged = config(saved, "--rig", "bench", "--upgrade", "skip", "--exec", "dev");
        assertEquals(UpgradeMode.SKIP, flagged.upgradeMode());
        assertEquals("dev", flagged.executionMode());

        LaunchConfig fromFiles = config(saved, "--rig", "bench");
        assertEquals(UpgradeMode.CLASSIC, fromFiles.upgradeMode());
        assertEquals("BDD", fromFiles.executionMode());

        LaunchConfig pythonOnly = config(LastConfig.load(dir.resolve("absent.json"), python), "--rig", "bench");
        assertEquals(UpgradeMode.NEW_INSTALL, pythonOnly.upgradeMode());

        LaunchConfig defaults = config(LastConfig.load(dir.resolve("a.json"), dir.resolve("b.json")), "--rig", "bench");
        assertEquals(UpgradeMode.SKIP, defaults.upgradeMode());
        assertEquals("dev", defaults.executionMode());
        assertEquals("buypass", defaults.dist());
        assertEquals("master", defaults.testPlanBranch());
        assertTrue(defaults.xrayReport(), "Xray is on when nothing is saved");
        assertFalse(defaults.coverage());
        assertFalse(defaults.commanderConnect());
    }

    @Test
    void theSavedSwitchesApplyAndTheNoFlagsTurnThemOff() throws IOException {
        LastConfig saved = LastConfig.load(write("java.json",
                "{\"config-coverage\": true, \"config-xray\": true, \"config-cc-onboard\": true,"
                        + " \"config-cc-lab\": \"QAT\", \"config-cc-email\": \"a@b.invalid\","
                        + " \"config-cc-service-id\": \"S1\", \"config-cc-password\": \"hunter2\"}"), null);

        LaunchConfig on = config(saved, "--rig", "bench");
        assertTrue(on.coverage());
        assertTrue(on.xrayReport());
        assertTrue(on.commanderConnect());
        assertEquals(new CommanderConnect("QAT", "a@b.invalid", "S1", "hunter2"), on.commanderConnectSettings());

        LaunchConfig off = config(saved, "--rig", "bench", "--no-coverage", "--no-xray");
        assertFalse(off.coverage());
        assertFalse(off.xrayReport());

        LaunchConfig savedXrayOff = config(LastConfig.of(Map.of("config-xray", false)), "--rig", "bench");
        assertFalse(savedXrayOff.xrayReport());
    }

    @Test
    void thePasswordIsNeverInToStringAndFallsBackToTheEnvironment() {
        LastConfig saved = LastConfig.of(Map.of("config-cc-password", "hunter2", "config-cc-email", "a@b.invalid"));
        assertFalse(saved.toString().contains("hunter2"), saved.toString());
        assertFalse(saved.commanderConnect(Map.of()).toString().contains("hunter2"));

        CommanderConnect fromEnv = LastConfig.none()
                .commanderConnect(Map.of(CommanderConnect.PASSWORD_ENV, "from-env"));
        assertEquals("from-env", fromEnv.password());
        assertEquals("Prod", fromEnv.lab());
        assertEquals("hunter2", saved.commanderConnect(Map.of(CommanderConnect.PASSWORD_ENV, "from-env")).password());
    }

    @Test
    void aFileThatWillNotReadIsNoChoicesAndNothingIsWritten() throws IOException {
        Path java = write("java.json", "not json");
        Path python = write("python.json", "{\"config-dist\": \"x\"}");
        String before = Files.readString(python);

        LastConfig saved = LastConfig.load(java, python);
        config(saved, "--rig", "bench");

        assertEquals("x", saved.dist());
        assertEquals("not json", Files.readString(java));
        assertEquals(before, Files.readString(python));
    }

    @Test
    void theDownloadsResolveAsRunPlanDoes() {
        LastConfig saved = LastConfig.of(Map.of(
                "config-cmd-branch", "Solutions", "config-cmd-build", "125",
                "config-fnd-branch", "master", "config-fnd-build", "9",
                "config-sim-branch", "master", "config-sim-build", 74));

        assertEquals(new LastConfig.Downloads("Solutions", "125", "master", "9", "master", "74"),
                saved.downloads("", "", "", ""));
        assertEquals(new LastConfig.Downloads("PETROCORE-1", "3", "", "", "dev", "74"),
                saved.downloads("PETROCORE-1", "3", "dev", null));
    }

    @Test
    void fromHomeReadsTheJavaFileUnderCatJavaAndThePythonFileUnderCat() throws IOException {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(".cat").resolve("java"));
        Files.writeString(home.resolve(".cat").resolve("last-config.json"), "{\"config-dist\": \"py\", \"config-exec\": \"BDD\"}");
        Files.writeString(home.resolve(".cat").resolve("java").resolve("last-config.json"), "{\"config-dist\": \"java\"}");

        LastConfig saved = LastConfig.fromHome(Map.of(), home.toString());

        assertEquals("java", saved.dist());
        assertEquals("BDD", saved.exec());
    }
}
