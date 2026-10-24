package com.verifone.cat.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewInstallToolsTest {

    @TempDir
    Path build;

    @Test
    void aMissingFileIsNamedBeforeAnythingRuns() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> NewInstallTools.forBuild(build, Map.of()));

        assertTrue(ex.getMessage().contains("overlay.zip"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Nothing was attempted"), ex.getMessage());
    }

    @Test
    void theToolsAreFoundWhenEveryFileIsThereAndTheEnvironmentOverridesTheDefaults() throws IOException {
        Path java = Files.writeString(build.resolve("java.exe"), "");
        Path netloader = Files.writeString(build.resolve("NetLoaderClient.jar"), "");
        Path root = Files.writeString(build.resolve("root.tgz"), "");
        Path gemreg = Files.writeString(build.resolve("gemreg.tgz"), "");
        for (String name : new String[] {"overlay.zip", "Petro_Suite_Installer.jar", "Auto_Upgrade.jar"}) {
            Files.writeString(build.resolve(name), "");
        }
        Map<String, String> env = new HashMap<>();
        env.put("PETROTEST_JAVA17_BIN", java.toString());
        env.put("PETROTEST_NETLOADER_JAR", netloader.toString());
        env.put("PETROTEST_UPGRADE_ROOT_ACCESS_PATH", root.toString());
        env.put("PETROTEST_UPGRADE_GEMREG_LICENSE_PATH", gemreg.toString());

        NewInstallTools tools = NewInstallTools.forBuild(build, env);

        assertEquals(java.toString(), tools.javaBin());
        assertEquals(root.toString(), tools.rootAccessPath());
        assertEquals(build.resolve("Petro_Suite_Installer.jar"), tools.installerJar());
        assertEquals(build.getFileName().toString(), tools.dist());
    }

    @Test
    void theDefaultsAreTheControllersPatsPaths() {
        NewInstallTools tools = NewInstallTools.defaults(build);

        assertTrue(tools.javaBin().endsWith("jdk-17.0.5+8\\bin\\java.exe"), tools.javaBin());
        assertTrue(tools.rootAccessPath().endsWith("update-all-jacoco-noPW.tgz"), tools.rootAccessPath());
    }
}
