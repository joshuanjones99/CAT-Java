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
 *   <li>{@code BUYPASS Host}: adopted when its 41107 listener is CAT's {@code -cat} jar
 *       ({@code adoptOnlyMatch}); otherwise CAT's jar is built from PATS's
 *       ({@code capkFrom}, {@link Capk}: CAPK expiries re-dated, cut time moved) and started,
 *       its windows minimised as they appear ({@code windowed}).</li>
 *   <li>The three LoySim rows and the MPPA row: started, then their Swing buttons pressed through
 *       the Java Access Bridge ({@link AccessBridgeClicker}); {@code ready} once pressed,
 *       {@code needs attention} otherwise.</li>
 *   <li>{@code Petro Simulator Suite}: started with its argv, the JAXB fallback on the
 *       {@code javax/xml/bind/JAXBException} line, D9 adoption on 9001 and {@code replace_stale}; before
 *       each launch its tank level simulator is set from the rig ({@link PetroTls}) and its SSLUtil step
 *       runs; System Checks parses its {@code fuelPosTotals.xml}.</li>
 *   <li>{@code BUYPASS Host} is {@code superseded_by} that suite's FEP simulator v2 ({@link Fepsim}).</li>
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
            // Petro Simulator Suite's FEP simulator v2 (PETROCORE-142214) answers the same Buypass UMF on the same
            // port from its own BUYPASS-41107 instance; v1 cannot (rig owner, 2026-10-12). Its answers come from the
            // suite's WireMock (HTTPSIM) on 80, which step 3 checks too.
            .supersededBy("Petro Simulator Suite", 2, "BUYPASS-41107", "BUYPASS", 41107, 80)
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
                    // Rewritten by the suite as it runs; cut short twice when C: filled (SimulatorStaging).
                    .wellFormed("config/fuelPosTotals.xml")
                    // The tank level simulator: on the rig's controller.tls_port, or off (PetroTls).
                    .tls("config/simulator_common_config.properties", "config/TLS_config.properties")
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

    /**
     * Each row with a base-56-and-earlier form, as the <em>other</em> base reads it: the Python's
     * {@code config.other_variants}. For an older-base run that is each such row's base 57 form (LoySim 9.1, MPPA 04),
     * and the reverse. Against this Commander, that form is the wrong version ({@link Supervisor#stopWrongVariants}).
     */
    public static java.util.Map<String, SimEntry> otherVariants(boolean olderBase) {
        java.util.Map<String, SimEntry> others = new java.util.LinkedHashMap<>();
        for (SimEntry row : ROWS) {
            if (row.olderBaseForm() != null) {
                others.put(row.name(), olderBase ? row : row.olderBaseForm());
            }
        }
        return others;
    }

    /**
     * Both registry forms of every launchable row named, base 57's first: what a Stop must close, since either may be
     * running (the Python's {@code stop_simulators}, which loads the registry for {@code ""} and {@code "055.02"}).
     */
    public static List<SimEntry> bothForms(java.util.Collection<String> names) {
        List<SimEntry> rows = new java.util.ArrayList<>();
        for (boolean older : new boolean[] {false, true}) {
            for (SimEntry row : launchable(older)) {
                if (names.contains(row.name()) && !rows.contains(row)) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** The row with this name, on base 57 and later. */
    public static Optional<SimEntry> byName(String name) {
        return ROWS.stream().filter(row -> row.name().equals(name)).findFirst();
    }
}
