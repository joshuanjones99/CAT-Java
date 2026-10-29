package com.verifone.cat.upgrade;

/**
 * The tool paths the root-access pushes need ({@link RootAccess#pushCommanderRootAccess},
 * {@link RootAccess#pushRegisterRootAccess}). A port of the Python's {@code classic.UpgradePushTools} protocol:
 * {@code CICDScript.bat}'s root-access tail is identical in its AU and PSI paths, so both {@link ClassicUpgradeTools}
 * and {@link NewInstallTools} carry these.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface UpgradePushTools {

    /** The Java that runs the push tools. */
    String javaBin();

    /** {@code Auto_Upgrade.jar}, which pushes the Commander's packages ({@code -q lic}). */
    String autoUpgradeJarPath();

    /** {@code NetLoaderClient.jar}, which pushes a register's root access. */
    String netloaderJar();

    /** The root-access package, pushed to the Commander second and to every register. */
    String rootAccessPath();

    /** The first Commander package {@code -q lic} pushes. */
    String gemregLicensePath();
}
