package com.verifone.cat.sims;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.verifone.cat.sims.base.NightlyBuilds;

/**
 * The registry's {@code artifactory:} blocks and {@code match_base:} flags, which {@link SimEntry} does not carry:
 * where each simulator row is fetched from, in each base form, and which rows must match the Commander's base. The
 * Python CAT keeps both in {@code cat/sims/registry.yaml}; this table is those rows, value for value, read from it on
 * 2026-10-28.
 *
 * <p>A separate table rather than new {@link SimEntry} fields, so the record the supervisor was ported against is
 * unchanged. {@link #check} keeps the two in step: every row of {@link Registry} has a source here.
 */
public final class SimSources {

    /** Where master's nightly simulators live; a chosen Simulator Build reads its own branch instead. */
    public static final String MASTER_REPO = "RMS_Nightly_CLW/Petro/master";

    /**
     * A registry row's {@code artifactory:} block: the Python's {@code cat.sims.fetch.ArtifactorySource}.
     *
     * @param repo   repo-relative folder of numbered builds
     * @param folder the folder inside a build that holds {@code file}
     * @param file   the zip to download
     * @param holds  where the row's directory sits inside the zip, {@code /}-separated; empty means at the zip's root
     *               under the row's own name; {@code "."} means the zip's contents are that directory
     * @param after  arguments run with the JDK's {@code java.exe} in the row's folder after unpacking
     */
    public record ArtifactorySource(String repo, String folder, String file, String holds, List<String> after) {

        public ArtifactorySource {
            holds = holds == null ? "" : holds;
            after = List.copyOf(after == null ? List.of() : after);
        }

        /** This source read from {@code branch} instead of the registry's repo; everything else unchanged. */
        public ArtifactorySource onBranch(String branch) {
            return new ArtifactorySource(NightlyBuilds.branchPath(branch), folder, file, holds, after);
        }

        /** Where the row's directory is inside the zip unpacked at {@code unpacked}. */
        public Path stagedIn(Path unpacked, Path target) {
            if (holds.isEmpty()) {
                return unpacked.resolve(target.getFileName().toString());
            }
            Path at = unpacked;
            for (String part : holds.split("/")) {
                if (!part.isEmpty() && !part.equals(".")) {
                    at = at.resolve(part);
                }
            }
            return at;
        }
    }

    private static ArtifactorySource simulators(String file, String holds) {
        return new ArtifactorySource(MASTER_REPO, "simulators", file, holds, List.of());
    }

    /** The Petro Simulator Suite's SSLUtil step, from {@code startSimulator.bat}. */
    private static final List<String> PETRO_AFTER = List.of(
            "--add-exports", "java.base/sun.security.x509=ALL-UNNAMED",
            "--add-exports", "java.base/sun.security.tools.keytool=ALL-UNNAMED",
            "-classpath", "Petro_Simulator_Suite.jar",
            "com.verifone.isd.vsms2.sys.util.channel.ssl.SSLUtil");

    /** Each row's source on base 57 and later. */
    private static final Map<String, ArtifactorySource> NEWER = Map.of(
            "BUYPASS Host", simulators("ArbitraryHostSim_v5.00.zip", "ArbitraryHostSim_v5.00"),
            "PCATS01 Loyalty", simulators("LoySim_V9.1.zip", "LoySim_V9.1/PCATS01"),
            "PCATS02 Loyalty", simulators("LoySim_V9.1.zip", "LoySim_V9.1/PCATS02"),
            "PCATS03 Loyalty", simulators("LoySim_V9.1.zip", "LoySim_V9.1/PCATS03"),
            "MPPA Server Sim", simulators("MPPAServerSim-04.00.00.zip", ""),
            "Petro Simulator Suite", new ArtifactorySource(MASTER_REPO, "simulators",
                    "install-isdApps.pcapps.simulators.app-V1.zip", "", PETRO_AFTER),
            "Terminal Connector", new ArtifactorySource(MASTER_REPO, "automation", "terminalConnector-V4.zip", ".",
                    List.of()));

    /** The rows whose {@code base_56_and_earlier} form names another source. */
    private static final Map<String, ArtifactorySource> OLDER = Map.of(
            "PCATS01 Loyalty", simulators("LoySim_V7.zip", "LoySim_V7/PCATS01"),
            "PCATS02 Loyalty", simulators("LoySim_V7.zip", "LoySim_V7/PCATS02"),
            "PCATS03 Loyalty", simulators("LoySim_V7.zip", "LoySim_V7/PCATS03"),
            "MPPA Server Sim", simulators("MPPAServerSim-03.00.00.zip", ""));

    /** The rows that say {@code match_base: true}: built per Commander base. */
    public static final Set<String> MATCH_BASE = Set.of(
            "PCATS01 Loyalty", "PCATS02 Loyalty", "PCATS03 Loyalty", "MPPA Server Sim", "Petro Simulator Suite");

    private SimSources() {
    }

    /** The row's source in the base form {@code olderBase} picks, or empty for a row with no {@code artifactory:}. */
    public static Optional<ArtifactorySource> forRow(String name, boolean olderBase) {
        if (olderBase && OLDER.containsKey(name)) {
            return Optional.of(OLDER.get(name));
        }
        return Optional.ofNullable(NEWER.get(name));
    }

    /** Whether the row must match the Commander's base. */
    public static boolean matchBase(String name) {
        return MATCH_BASE.contains(name);
    }

    /**
     * The jar after {@code -jar}, under the row's folder unless absolute, or empty: the first token after
     * {@code -jar} that is not an option (the Petro row puts {@code -agentlib:jdwp=...} between them). The Python's
     * {@code SimContext.jar_for}.
     */
    public static Optional<Path> jarFor(SimEntry entry, SimContext ctx) {
        List<String> argv = ctx.substitutedArgv(entry);
        int at = argv.indexOf("-jar");
        if (at < 0 || at == argv.size() - 1) {
            return Optional.empty();
        }
        for (String arg : argv.subList(at + 1, argv.size())) {
            if (!arg.startsWith("-")) {
                return Optional.of(ctx.cwdFor(entry).resolve(arg));
            }
        }
        return Optional.empty();
    }

    /** The registry rows with no source here: empty when the two tables agree. */
    public static List<String> check() {
        List<String> missing = new ArrayList<>();
        for (SimEntry row : Registry.all()) {
            if (!NEWER.containsKey(row.name())) {
                missing.add(row.name());
            }
        }
        return missing;
    }
}
