package com.verifone.cat.coverage;

import java.util.List;
import java.util.Optional;

/**
 * The JaCoCo component table and the paths the product's agent uses on both devices.
 *
 * <p>The product loads the agent itself wherever {@link #AGENT_JAR} exists (petroapps' start scripts).
 * Tomcat (Commander and register) dumps over TCP on {@link #TOMCAT_PORT}; every other Commander JVM writes
 * {@code <vm>.exec} to {@link #COMMANDER_EXEC_DIR} when it exits cleanly.
 *
 * <p>A port of {@code petrotest.coverage.components}. The class-file lists are petroapps' own, from its
 * {@code generate-jacoco-report} skill. {@link #SEEDS} is the skill's {@code $componentSeedMap}: the petroapps
 * directory each class file is built from, which {@link SourceResolver} walks {@code componentDef.xml} from.
 *
 * <p>{@link #matches} is the Python's {@code fnmatch} as it behaves on Windows, where the controller runs:
 * case-insensitive ({@code os.path.normcase} lower-cases both sides).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Components {

    /** Class file to the petroapps directory its classes are built from ({@code $componentSeedMap}). */
    public static final java.util.Map<String, String> SEEDS = seeds();

    private static java.util.Map<String, String> seeds() {
        java.util.Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("isdApps.psc.coresvcs.app-V1.jar", "isdApps/psc/coresvcs/app");
        table.put("isdApps.psc.coresvcs.extpos.jaxb-V1.jar", "isdApps/psc/coresvcs/extpos/jaxb");
        table.put("isdApps.psc.fcc.fdc.adapter-V1.jar", "isdApps/psc/fcc/fdc/adapter");
        table.put("isdApps.psc.fcc.fdc.ent-V1.jar", "isdApps/psc/fcc/fdc/ent");
        table.put("isdApps.psc.fcc.fdc.jaxb-V1.jar", "isdApps/psc/fcc/fdc/jaxb");
        table.put("isdApps.vsmsV2.sys.locale-V1.jar", "isdApps/vsmsV2/sys/locale");
        table.put("cgi-bin.war", "isdApps/vsmsV2/CGIPortal");
        table.put("ExtActionWSServer.war", "isdApps/psc/coresvcs/extpos/websocket/server");
        table.put("i18n.war", "isdApps/psc/coresvcs/i18n");
        table.put("pkgupload.war", "isdApps/psc/coresvcs/pkgupload");
        table.put("isdApps.psc.fcc.app-V1.jar", "isdApps/psc/fcc/app");
        table.put("isdApps.psc.mobile.app-V1.jar", "isdApps/psc/mobile/app");
        table.put("viper.core.app-V1.jar", "viper/core/app");
        table.put("viper.core.jaxb-V1.jar", "viper/core/jaxb");
        table.put("isdApps.psc.kiosk.foodserviceupdater.app-V1.jar", "isdApps/psc/kiosk/foodserviceupdater/app");
        table.put("isdApps.psc.cdmagentupdater.app-V1.jar", "isdApps/psc/cdmagentupdater/app");
        table.put("viper.custom.{dist}.app-V1.jar", "viper/custom/{dist}/app");
        table.put("viper.custom.{dist}.jaxb-V1.jar", "viper/custom/{dist}/jaxb");
        table.put("isdApps.topaz.pos.app-V1.jar", "isdApps/topaz/pos/app");
        table.put("appselectorserver.war", "isdApps/topaz/appselector/server");
        table.put("isdApps.psc.coresvcs.vhqclient.app-V1.jar", "isdApps/psc/coresvcs/vhqclient/app");
        table.put("isdApps.psc.stc.app-V1.jar", "isdApps/psc/stc/app");
        return java.util.Collections.unmodifiableMap(table);
    }

    /** Whether {@code name} matches any of {@code patterns}: the Python's {@code matches}, case-insensitive. */
    public static boolean matches(String name, java.util.Collection<String> patterns) {
        String folded = name.toLowerCase(java.util.Locale.ROOT);
        for (String pattern : patterns) {
            if (Component.fnmatch(folded, pattern.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Where the product's start scripts look for the agent, on both devices. */
    public static final String AGENT_JAR = "/home/common/lib/jacocoagent.jar";

    /** The Commander's data drive, which the exec files are on; mounted late in a boot. */
    public static final String COMMANDER_EXEC_MOUNT = "/media/v950sc-drive";

    /** Where the Commander's file-output JVMs write {@code <vm>.exec} on exit. */
    public static final String COMMANDER_EXEC_DIR = COMMANDER_EXEC_MOUNT + "/v950sc/logs";

    /** Tomcat's agent port ({@code setenv.sh}: {@code output=tcpserver,address=*,port=6300}). */
    public static final int TOMCAT_PORT = 6300;

    public static final String COMMANDER_TOMCAT = "commander-tomcat";
    public static final String POS_TOMCAT = "pos-tomcat";

    /** The table, in Python's order. */
    public static final List<Component> COMPONENTS = List.of(
            component("coresvcs", "psc", List.of("isdApps.psc.coresvcs.app-V1.jar")),
            component("fcc", "psc", List.of("isdApps.psc.fcc.app-V1.jar")),
            component("stc", "psc", List.of("isdApps.psc.stc.app-V1.jar")),
            component("mobile", "psc", List.of("isdApps.psc.mobile.app-V1.jar", "viper.core.app-V1.jar",
                    "viper.core.jaxb-V1.jar")),
            component("kiosk", "psc", List.of("isdApps.psc.kiosk.foodserviceupdater.app-V1.jar")),
            component("cdm", "psc", List.of("isdApps.psc.cdmagentupdater.app-V1.jar")),
            component("vhq", "psc", List.of("isdApps.psc.coresvcs.vhqclient.app-V1.jar")),
            component("pmc", "psc", List.of("viper.core.app-V1.jar", "viper.core.jaxb-V1.jar",
                    "viper.custom.{dist}.app-V1.jar", "viper.custom.{dist}.jaxb-V1.jar")),
            new Component(COMMANDER_TOMCAT, "psc", List.of(
                    "isdApps.psc.coresvcs.extpos.jaxb-V1.jar",
                    "isdApps.psc.fcc.fdc.adapter-V1.jar",
                    "isdApps.psc.fcc.fdc.ent-V1.jar",
                    "isdApps.psc.fcc.fdc.jaxb-V1.jar",
                    "isdApps.vsmsV2.sys.locale-V1.jar",
                    "cgi-bin.war",
                    "ExtActionWSServer.war",
                    "i18n.war",
                    "pkgupload.war"), true, List.of("common", "coresvcs")),
            new Component(POS_TOMCAT, "pos", List.of(
                    "isdApps.psc.fcc.fdc.jaxb-V1.jar",
                    "isdApps.topaz.pos.app-V1.jar",
                    "isdApps.vsmsV2.sys.locale-V1.jar",
                    "appselectorserver.war"), true, List.of("topaz")));

    private Components() {}

    private static Component component(String name, String side, List<String> classfiles) {
        return new Component(name, side, classfiles, false, List.of());
    }

    /** The component an exec file belongs to, or empty for one the table does not name. */
    public static Optional<Component> componentFor(String execName) {
        return COMPONENTS.stream().filter(component -> component.execMatches(execName)).findFirst();
    }

    /** {@code pos-tomcat-<register id>.exec}: one Tomcat dump per register. */
    public static String posExecName(String registerId) {
        return POS_TOMCAT + "-" + registerId + ".exec";
    }
}
