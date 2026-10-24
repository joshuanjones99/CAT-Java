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
 * {@code generate-jacoco-report} skill. The petroapps seed map ({@code SEEDS} in Python) is not ported: it
 * belongs to the source-root resolver, which is not ported yet.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Components {

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
