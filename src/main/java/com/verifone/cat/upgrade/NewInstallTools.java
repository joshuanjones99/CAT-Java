package com.verifone.cat.upgrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where the PSI path's inputs are: the build directory and the proprietary tools the install runs.
 *
 * <p>A port of {@code petrotest.upgrade.new_install.NewInstallTools}. {@code buildDir} is the build's
 * folder, named after its distribution ({@code C:\CICD\Builds\Solutions<n>\<dist>}). The Java and jar
 * defaults are the Python CAT's; each is overridable by its {@code PETROTEST_*} variable, as in Python.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param javaBin the Java 17 executable that runs the installer and the push tools
 * @param netloaderJar {@code NetLoaderClient.jar}, which pushes a register's root access
 * @param rootAccessPath {@code update-all-jacoco-noPW.tgz}, the register's and the Commander's root access
 * @param gemregLicensePath the first Commander package {@code -q lic} pushes
 */
public record NewInstallTools(Path buildDir, String javaBin, String netloaderJar, String rootAccessPath,
        String gemregLicensePath) implements UpgradePushTools {

    /** PATS's home on the controller; the tools and packages sit under it. */
    static final String PATS_HOME = "C:\\CICD";

    static final String INSTALLER_JAR = "Petro_Suite_Installer.jar";
    static final String AUTO_UPGRADE_JAR = "Auto_Upgrade.jar";
    static final String OVERLAY_ZIP = "overlay.zip";

    public NewInstallTools {
        if (buildDir == null) {
            throw new IllegalArgumentException("the PSI install needs a build directory");
        }
    }

    /** A set of tools with the Python CAT's defaults, under {@code buildDir}. */
    public static NewInstallTools defaults(Path buildDir) {
        return new NewInstallTools(buildDir,
                PATS_HOME + "\\Tools\\Java17\\jdk-17.0.5+8\\bin\\java.exe",
                PATS_HOME + "\\Tools\\NetLoaderClient.jar",
                PATS_HOME + "\\Packages\\update-all-jacoco-noPW.tgz",
                PATS_HOME + "\\Packages\\update_pearl-controller_gemreg-force-registered.tgz");
    }

    public Path installerJar() {
        return buildDir.resolve(INSTALLER_JAR);
    }

    public Path autoUpgradeJar() {
        return buildDir.resolve(AUTO_UPGRADE_JAR);
    }

    @Override
    public String autoUpgradeJarPath() {
        return autoUpgradeJar().toString();
    }

    /** The distribution name: the build directory's last element, as {@code psi_answers} takes it. */
    public String dist() {
        return buildDir.getFileName().toString();
    }

    /**
     * The defaults for this controller, each overridable by its {@code PETROTEST_*} variable, checked
     * for the files the install needs.
     *
     * @throws IllegalArgumentException naming what is missing, before anything is touched
     */
    public static NewInstallTools forBuild(Path buildDir, Map<String, String> environ) {
        NewInstallTools base = defaults(buildDir);
        NewInstallTools tools = new NewInstallTools(buildDir,
                override(environ, "PETROTEST_JAVA17_BIN", base.javaBin),
                override(environ, "PETROTEST_NETLOADER_JAR", base.netloaderJar),
                override(environ, "PETROTEST_UPGRADE_ROOT_ACCESS_PATH", base.rootAccessPath),
                override(environ, "PETROTEST_UPGRADE_GEMREG_LICENSE_PATH", base.gemregLicensePath));
        List<String> missing = new ArrayList<>();
        for (Path file : List.of(buildDir.resolve(OVERLAY_ZIP), tools.installerJar(), tools.autoUpgradeJar(),
                Path.of(tools.javaBin), Path.of(tools.netloaderJar), Path.of(tools.rootAccessPath),
                Path.of(tools.gemregLicensePath))) {
            if (!Files.isRegularFile(file)) {
                missing.add(file.toString());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the PSI install needs these files, missing: "
                    + String.join(", ", missing) + ". Nothing was attempted.");
        }
        return tools;
    }

    private static String override(Map<String, String> environ, String name, String fallback) {
        String value = environ == null ? null : environ.get(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
