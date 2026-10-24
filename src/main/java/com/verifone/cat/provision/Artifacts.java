package com.verifone.cat.provision;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The provision table (DESIGN.md 6.1), as data. A port of {@code petrotest.provision.artifacts}, row for row and
 * in the same push order, so a manifest built here has the same digest as the one the Python CAT stamps: the
 * two must agree or each would re-push what the other provisioned.
 *
 * <p>Remote paths are the ones the Python CAT corrected against PATS's {@code automn_app_config.prop} (see its
 * docstring): the POS classpath is {@code /home/topaz/lib}, JaCoCo is {@code /home/common/lib}, and so on.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Artifacts {

    private static final Set<TargetRole> REGISTER = Set.of(TargetRole.REGISTER);
    private static final Set<TargetRole> COMMANDER = Set.of(TargetRole.COMMANDER);
    private static final Set<TargetRole> BOTH = Set.of(TargetRole.REGISTER, TargetRole.COMMANDER);

    private static final String TOPAZ_CONFIG = "/home/topaz/config";
    private static final String CORESVCS_CONFIG = "/home/coresvcs/config";
    private static final String TOPAZ_AUTOMATION = "/home/topaz/automation";
    /** The POS's own classpath: {@code register.sh} globs {@code $APP_HOME/lib/*.jar} and nothing else. */
    private static final String TOPAZ_LIB = "/home/topaz/lib";
    private static final String JACOCO_LIB = "/home/common/lib";
    private static final String FILEBEAT_DIR = "/home/common/filebeat-8.2.2-linux-x86_64";
    /** BROWSER_STRATEGY.md 5.1: a path no Vewd file lives under. */
    private static final String TOPAZ_BROWSER = "/home/topaz/browser/chromium";

    /** The stamp file, one per target role: the content-hash guard reads it. */
    public static final Map<TargetRole, String> STAMP_PATHS = Map.of(
            TargetRole.COMMANDER, "/home/common/config/.petrotest-provision.json",
            TargetRole.REGISTER, "/home/topaz/config/.petrotest-provision.json");

    /** Push order is table order: the RMI agent lands first, because every later probe depends on it. */
    public static final List<Artifact> ARTIFACTS = List.of(
            new Artifact("pos_rmi_agent", REGISTER,
                    List.of(
                            new ArtifactFile("pos/automation-target-pos-topaz.jar", TOPAZ_LIB + "/automation-target-pos-topaz.jar"),
                            new ArtifactFile("pos/topazerp-connection-enabler.jar", TOPAZ_LIB + "/topazerp-connection-enabler.jar"),
                            new ArtifactFile("pos/tokenRMI.prop", TOPAZ_CONFIG + "/tokenRMI.prop"),
                            new ArtifactFile("pos/petrotest-pos-shim.jar", TOPAZ_AUTOMATION + "/petrotest-pos-shim.jar"),
                            new ArtifactFile("pos/petrotest-pos-shim.sh", TOPAZ_AUTOMATION + "/petrotest-pos-shim.sh", 0755)),
                    "DefaultRegisterInitService.transferPOSConnectionEnablerPackage()",
                    "quiet.mode.loadpatsfilemode"),
            new Artifact("private_props", REGISTER,
                    List.of(new ArtifactFile("props/private_system.prop", TOPAZ_CONFIG + "/private_system.prop")),
                    "PatsInitializerUtilityService.pushTopazPrivateSystemProp()",
                    "quiet.mode.systemproptransfermode"),
            new Artifact("coresvcs_private_props", COMMANDER,
                    List.of(new ArtifactFile("props/core_private_system.prop", CORESVCS_CONFIG + "/private_system.prop")),
                    "PatsInitializerUtilityService.updateCMDRCoresvcsPrivateProps()",
                    "quiet.mode.systemproptransfermode"),
            new Artifact("filebeat_config", COMMANDER,
                    List.of(new ArtifactFile("filebeat/commander/filebeat.yml", FILEBEAT_DIR + "/filebeat.yml")),
                    "PatsInitializerUtilityService.pushFilebeatConfig() (commander source)",
                    "elk.filebeat.filetransfermode"),
            new Artifact("filebeat_config_register", REGISTER,
                    List.of(new ArtifactFile("filebeat/pos/filebeat.yml", FILEBEAT_DIR + "/filebeat.yml")),
                    "PatsInitializerUtilityService.pushFilebeatConfig() (pos source)",
                    "elk.filebeat.filetransfermode"),
            new Artifact("jacoco", BOTH,
                    List.of(new ArtifactFile("jacoco/jacocoagent.jar", JACOCO_LIB + "/jacocoagent.jar")),
                    "PatsSetupFileTransferService.transferJacocoFilesToTargetDevices()",
                    "quiet.mode.jacocofiletransfermode"),
            new Artifact("chromium_override", REGISTER,
                    List.of(
                            new ArtifactFile("pos/chromium/chrome-linux64-152.0.7977.42.tar.gz",
                                    TOPAZ_BROWSER + "/chrome-linux64-152.0.7977.42.tar.gz"),
                            new ArtifactFile("pos/chromium/libxkbcommon.so.0", TOPAZ_BROWSER + "/lib/libxkbcommon.so.0"),
                            new ArtifactFile("pos/chromium/libcups.so.2", TOPAZ_BROWSER + "/lib/libcups.so.2"),
                            new ArtifactFile("pos/chromium/chromium-wrapper.sh", "/usr/local/bin/chromium", 0755),
                            new ArtifactFile("pos/chromium/petrotest-no-infobars.json",
                                    "/etc/opt/chrome_for_testing/policies/managed/petrotest-no-infobars.json")),
                    "n/a - new capability, not a PATS method (BROWSER_STRATEGY.md 5.1)",
                    null),
            new Artifact("cdp_relay", REGISTER,
                    List.of(
                            new ArtifactFile("pos/chromium/CdpRelay.java", TOPAZ_BROWSER + "/CdpRelay.java"),
                            new ArtifactFile("pos/chromium/cdp-relay-boot.sh", TOPAZ_BROWSER + "/cdp-relay-boot.sh", 0755)),
                    "n/a - new capability, not a PATS method (BROWSER_STRATEGY.md 7)",
                    null));

    private Artifacts() {}

    /** The artifacts destined for one target role, in push order. */
    public static List<Artifact> artifactsFor(TargetRole role, List<Artifact> artifacts) {
        return artifacts.stream().filter(a -> a.roles().contains(role)).toList();
    }

    /** The remote stamp path for {@code role}. */
    public static String stampPath(TargetRole role) {
        return STAMP_PATHS.get(role);
    }
}
