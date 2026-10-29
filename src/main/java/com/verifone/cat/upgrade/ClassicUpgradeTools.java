package com.verifone.cat.upgrade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The external tool paths the AU (classic) path shells out to; the jars are never reimplemented.
 *
 * <p>A port of {@code petrotest.upgrade.classic.ClassicUpgradeTools}. Both jars are proprietary Verifone tools with
 * no source here; their argv is {@code CICDScript.bat}'s. {@link #forBuild} takes a downloaded build's
 * {@code overlay.zip} and {@code Auto_Upgrade.jar} and the PSI path's controller defaults for the rest (the Python's
 * {@code NewInstallTools(build_dir=...)} defaults, with no {@code PETROTEST_*} overrides, as the Python's
 * {@code for_build}); {@link #fromEnv} reads every path from {@code PETROTEST_*} variables.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 *
 * @param gemregLicensePath {@code update_pearl-controller_gemreg-force-registered.tgz}: the first of the two
 *                          packages {@code :SendRootaccessfiletoCommander} pushes
 */
public record ClassicUpgradeTools(String autoUpgradeJar, String netloaderJar, String overlayPath,
        String rootAccessPath, String gemregLicensePath, String javaBin) implements UpgradePushTools {

    /** The Python's default {@code java_bin}, when {@code PETROTEST_JAVA_BIN} is unset. */
    static final String DEFAULT_JAVA = "java";

    public ClassicUpgradeTools {
        javaBin = javaBin == null || javaBin.isBlank() ? DEFAULT_JAVA : javaBin;
    }

    @Override
    public String autoUpgradeJarPath() {
        return autoUpgradeJar;
    }

    /**
     * {@code buildDir}'s {@code overlay.zip} and {@code Auto_Upgrade.jar}, with the PSI path's controller defaults
     * for everything else.
     *
     * @throws IllegalArgumentException naming what is missing, before anything is touched
     */
    public static ClassicUpgradeTools forBuild(Path buildDir) {
        return forBuild(NewInstallTools.defaults(buildDir));
    }

    /** {@link #forBuild(Path)} over the given PSI tools ({@code defaults.buildDir()} is the build). */
    static ClassicUpgradeTools forBuild(NewInstallTools defaults) {
        Path buildDir = defaults.buildDir();
        ClassicUpgradeTools tools = new ClassicUpgradeTools(defaults.autoUpgradeJar().toString(),
                defaults.netloaderJar(), buildDir.resolve(NewInstallTools.OVERLAY_ZIP).toString(),
                defaults.rootAccessPath(), defaults.gemregLicensePath(), defaults.javaBin());
        List<String> missing = new ArrayList<>();
        for (String path : List.of(tools.autoUpgradeJar, tools.netloaderJar, tools.overlayPath, tools.rootAccessPath,
                tools.gemregLicensePath, tools.javaBin)) {
            if (!Files.isRegularFile(Path.of(path))) {
                missing.add(path);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the AU upgrade needs these files, missing: "
                    + String.join(", ", missing));
        }
        return tools;
    }

    /**
     * Every path from its {@code PETROTEST_*} variable. Choosing the classic mode is the operator's explicit request
     * for an upgrade, so a missing variable fails loudly rather than silently skipping it.
     *
     * @throws IllegalArgumentException naming every missing variable
     */
    public static ClassicUpgradeTools fromEnv(Map<String, String> environ) {
        Map<String, String> env = environ == null ? Map.of() : environ;
        Map<String, String> required = new LinkedHashMap<>();
        required.put("auto_upgrade_jar", "PETROTEST_AUTO_UPGRADE_JAR");
        required.put("netloader_jar", "PETROTEST_NETLOADER_JAR");
        required.put("overlay_path", "PETROTEST_UPGRADE_OVERLAY_PATH");
        required.put("root_access_path", "PETROTEST_UPGRADE_ROOT_ACCESS_PATH");
        required.put("gemreg_license_path", "PETROTEST_UPGRADE_GEMREG_LICENSE_PATH");
        List<String> missing = new ArrayList<>();
        for (String name : required.values()) {
            String value = env.get(name);
            if (value == null || value.isEmpty()) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("UpgradeMode.CLASSIC requires these environment variables, missing: "
                    + String.join(", ", missing) + ". Nothing was attempted.");
        }
        return new ClassicUpgradeTools(env.get("PETROTEST_AUTO_UPGRADE_JAR"), env.get("PETROTEST_NETLOADER_JAR"),
                env.get("PETROTEST_UPGRADE_OVERLAY_PATH"), env.get("PETROTEST_UPGRADE_ROOT_ACCESS_PATH"),
                env.get("PETROTEST_UPGRADE_GEMREG_LICENSE_PATH"), env.getOrDefault("PETROTEST_JAVA_BIN", DEFAULT_JAVA));
    }
}
