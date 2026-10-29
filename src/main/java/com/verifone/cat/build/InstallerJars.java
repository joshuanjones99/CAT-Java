package com.verifone.cat.build;

import java.util.List;

import com.verifone.cat.launch.UpgradeMode;

/**
 * What each upgrade mode needs beside {@code overlay.zip}: the Python's {@code solutions.INSTALLER_JARS}. PSI needs
 * {@code Auto_Upgrade.jar} too, since it pushes the root-access packages to the Commander ({@code -q lic}) after the
 * install. Skip and VHQ install no downloaded build.
 */
public final class InstallerJars {

    public static final String PSI_JAR = "Petro_Suite_Installer.jar";
    public static final String AU_JAR = "Auto_Upgrade.jar";

    /** {@code new_install}'s jars. */
    public static final List<String> NEW_INSTALL = List.of(PSI_JAR, AU_JAR);

    /** {@code classic}'s jar. */
    public static final List<String> CLASSIC = List.of(AU_JAR);

    private InstallerJars() {}

    /** Whether {@code mode} installs a downloaded build, so {@code cat-java build} downloads for it. */
    public static boolean downloads(UpgradeMode mode) {
        return mode == UpgradeMode.NEW_INSTALL || mode == UpgradeMode.CLASSIC;
    }

    /** {@code mode}'s jars; empty for a mode that installs no downloaded build (the Python's {@code .get(mode, ())}). */
    public static List<String> forMode(UpgradeMode mode) {
        if (mode == UpgradeMode.NEW_INSTALL) {
            return NEW_INSTALL;
        }
        if (mode == UpgradeMode.CLASSIC) {
            return CLASSIC;
        }
        return List.of();
    }
}
