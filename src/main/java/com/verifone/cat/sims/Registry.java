package com.verifone.cat.sims;

import java.util.List;
import java.util.Optional;

/**
 * The seven PATS simulators CAT supervises, as rows. The Python CAT's {@code registry.yaml}
 * ({@code cat/sims/registry.yaml}) is the specification; each row below is that row, field for
 * field, except where {@link SimEntry} says a field is not ported.
 *
 * <p>What this port does with each row:
 * <ul>
 *   <li>{@code BUYPASS Host}: adopted when its 41107 listener is up; otherwise REFUSED, because
 *       Python builds a CAPK-re-dated jar from PATS's and this port does not (SimEntry.capkFrom).
 *       Its {@code windowed} minimising is ported but unreached, since the row is never launched.</li>
 *   <li>The three LoySim rows and the MPPA row: started, then their Swing buttons pressed through
 *       the Java Access Bridge ({@link AccessBridgeClicker}); {@code ready} once pressed,
 *       {@code needs attention} otherwise.</li>
 *   <li>{@code Petro Simulator Suite}: started with its argv, the JAXB fallback on the
 *       {@code javax/xml/bind/JAXBException} line, D9 adoption on 9001 and {@code replace_stale}.
 *       Its tank-level, SSLUtil and well-formedness pre-steps are not ported.</li>
 *   <li>{@code Terminal Connector}: {@code staged_only}; never launched by CAT. The row exists so
 *       the zip is still checked.</li>
 * </ul>
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Registry {

    private static final String LOYSIM_BUTTON = "Start Host";

    /** The Petro Simulator Suite's argv, as {@code startSimulator.bat} gives it, minus the SSLUtil step. */
    private static final List<String> PETRO_ARGV = List.of(
            "-Xms300m", "-Xmx1024m",
            "--add-exports", "java.base/sun.security.x509=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.tools.keytool=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.math=ALL-UNNAMED",
            "--add-opens", "java.base/java.net=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio.charset=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio.file=ALL-UNNAMED",
            "--add-opens", "java.base/java.time=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent.locks=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.stream=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.cs=ALL-UNNAMED",
            "--add-opens", "java.base/sun.nio.fs=ALL-UNNAMED",
            "--add-opens", "java.rmi/java.rmi.server=ALL-UNNAMED",
            "--module-path", "{jaxb};{java_home}/javafx-sdk-19/lib",
            "--add-modules", "ALL-MODULE-PATH",
            "-Dlog4j.configDebug=true",
            "-XX:CompileCommand=exclude,com/verifone/isd/pcapps/simulators/forecourt/dcr/comm/client/DCRCommunicator$RequestQueueProcessor.run",
            "-jar", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=7777",
            "Petro_Simulator_Suite.jar", "{username}", "");

    private static final SimEntry BUYPASS = SimEntry.javaw("BUYPASS Host")
            .cwd("BUYPASS")
            .argv("-jar", "ArbitraryHostSim_v5.00-cat.jar", "UMF", "Server", "Start",
                    "-ip={controller_host}", "-port=41107", "-Prop={cwd}",
                    "-RuleParam=VEHN,N;TS;M4;X25")
            .capkFrom("ArbitraryHostSim_v5.00.jar")
            .tcp(41107)
            .adoptOnlyMatch()
            .windowed()
            .build();

    private static final List<SimEntry> ROWS = List.of(
            BUYPASS,
            loysim("PCATS01 Loyalty", "PCATS01", "LoySim_Debug.log"),
            loysim("PCATS02 Loyalty", "PCATS02", "LoySim_Debug.log"),
            loysim("PCATS03 Loyalty", "PCATS03", "LoySim_Debug.log"),
            SimEntry.javaw("MPPA Server Sim")
                    .cwd("MPPAServerSim-04.00.00")
                    .argv("-classpath", "lib\\*", "com.verifone.isd.mobile.mppa.app.MobileMain")
                    .click("Load Data", "Load Servers")
                    .clicked()
                    .olderBase(SimEntry.javaw("MPPA Server Sim")
                            .cwd("MPPAServerSim-03.00.00")
                            .argv("-classpath", "lib\\*", "com.verifone.isd.mobile.mppa.app.MobileMain")
                            .click("Load Data", "Load Servers")
                            .clicked()
                            .build())
                    .build(),
            SimEntry.javaw("Petro Simulator Suite")
                    .cwd("Petro_Simulator_Suite")
                    .argv(PETRO_ARGV.toArray(String[]::new))
                    .match("Petro_Simulator_Suite.jar")
                    .log("log4j-application.log")
                    .windowed()
                    .tcp(9001)
                    .adoptOnlyMatch()
                    .replaceStale()
                    .fallback("{tools_root}/Java17v1.1/jdk-17.0.5+8",
                            "{tools_root}/Java17v1.1/jaxb-ri/mod_",
                            "javax/xml/bind/JAXBException")
                    .build(),
            SimEntry.exeRow("Terminal Connector")
                    .cwd("terminalConnector-V4")
                    .exe("{cwd}/python-3.9.11-embed-amd64/python.exe")
                    .argv("app/main.py")
                    .env("PYTHONPATH", "app/vats_runner")
                    .match("{cwd}/python-3.9.11-embed-amd64/python.exe")
                    .tcp(11000)
                    .log("log/pats_connector.log")
                    .stagedOnly()
                    .build());

    private Registry() {
    }

    /** One LoySim row. Base 55.02 ships LoySim V7 (LoySim7.jar) where 57 ships 9.1. */
    private static SimEntry loysim(String name, String folder, String log) {
        SimEntry older = SimEntry.javaw(name)
                .cwd(folder)
                .argv("-jar", "{cwd}/LoySim7.jar")
                .click(LOYSIM_BUTTON)
                .clicked()
                .log(log)
                .build();
        return SimEntry.javaw(name)
                .cwd(folder)
                .argv("-jar", "{cwd}/LoySim9.1.jar")
                .click(LOYSIM_BUTTON)
                .clicked()
                .log(log)
                .olderBase(older)
                .build();
    }

    /** Every row, for a Commander on base 57 and later. */
    public static List<SimEntry> all() {
        return ROWS;
    }

    /**
     * Every row as a run on this base sees it: a base 56-or-earlier run takes each row's
     * {@code olderBaseForm} where it has one.
     */
    public static List<SimEntry> forBase(boolean olderBase) {
        return ROWS.stream()
                .map(row -> olderBase && row.olderBaseForm() != null ? row.olderBaseForm() : row)
                .toList();
    }

    /** The rows CAT may launch: every row but the staged-only Terminal Connector. */
    public static List<SimEntry> launchable(boolean olderBase) {
        return forBase(olderBase).stream().filter(row -> !row.stagedOnly()).toList();
    }

    /** The row with this name, on base 57 and later. */
    public static Optional<SimEntry> byName(String name) {
        return ROWS.stream().filter(row -> row.name().equals(name)).findFirst();
    }
}
