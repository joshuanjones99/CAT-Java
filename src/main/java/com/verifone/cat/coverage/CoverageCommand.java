package com.verifone.cat.coverage;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.verifone.cat.build.BuildDiscovery;
import com.verifone.cat.build.Installed;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java coverage prepare|collect|report}: the Python's {@code petrotest coverage} ({@code coverage/cli.py}).
 *
 * <ul>
 *   <li>{@code prepare --rig <name>}: the agent on every device and loaded (rebooting where it is not), old data
 *       cleared ({@link CoverageDevice#prepare});</li>
 *   <li>{@code collect --rig <name> --out <dir> [--no-reboot]}: each Tomcat dumped, then the Commander rebooted and
 *       its exec files read ({@link CoverageDevice#collect}); {@code --no-reboot} is Tomcat only;</li>
 *   <li>{@code report --dir <dir> [--dist buypass] [--build <name>] [--overlay <zip>] [--run-id <id>]
 *       [--plan <key>]... [--sources <checkout>] [--results-root <dir>]}: HTML/XML reports and a summary from the
 *       exec files ({@link CoverageReport#buildReport}); the overlay defaults to the {@code --build}'s under the
 *       builds root, the run id to the coverage folder's parent, the results root to CAT's.</li>
 * </ul>
 *
 * <p>Exit codes, as the Python's: 0 on success, 1 on failure (a report with no component reported is a failure),
 * 2 for a usage error (argparse's) or a rig that does not load. {@code report} prints the summary's markdown.
 *
 * <p>The Python reconfigures stdout to UTF-8 because its summary's "Δ" crashed a cp1252 console (2026-10-13). Java
 * never throws on an unencodable character (it prints {@code ?}), and the launch runs this in-process, its lines
 * going straight into the step log, so nothing is needed for that here.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): prepare and collect reach the rig's devices over SSH.
 */
public final class CoverageCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java coverage prepare --rig <name>",
            "       cat-java coverage collect --rig <name> --out <dir> [--no-reboot]",
            "       cat-java coverage report --dir <dir> [--dist buypass] [--build <name>] [--overlay <zip>]",
            "                                [--run-id <id>] [--plan <key>]... [--sources <dir>] [--results-root <dir>]",
            "",
            "  prepare   the agent on every device and loaded (rebooting where it is not); old data cleared",
            "  collect   dump each Tomcat, then reboot the Commander and read its exec files",
            "  report    HTML/XML reports and a summary from exec files");

    private final Map<String, String> env;
    private final Function<Consumer<String>, DeviceOps> deviceOps;
    private final JacocoTools tools;
    private final Jacoco.CliRunner cli;

    /** The production command: the process environment, SSH to the devices, the real {@code jacococli}. */
    public CoverageCommand() {
        this(System.getenv(), DeviceOps::defaults, null, null);
    }

    /**
     * @param env       where the rigs, builds and results roots are read from
     * @param deviceOps the device seams for a log line consumer
     * @param tools     the JaCoCo tools for {@code report}, or null to find them
     * @param cli       runs {@code jacococli} for {@code report}, or null for the real process
     */
    public CoverageCommand(Map<String, String> env, Function<Consumer<String>, DeviceOps> deviceOps,
            JacocoTools tools, Jacoco.CliRunner cli) {
        this.env = env == null ? Map.of() : env;
        this.deviceOps = deviceOps;
        this.tools = tools;
        this.cli = cli;
    }

    /** Main's entry point: the arguments after {@code coverage}. */
    public static int run(String[] argv) {
        return new CoverageCommand().run(argv, System.out, System.err);
    }

    public int run(String[] argv, PrintStream out, PrintStream err) {
        return execute(List.of(argv), out::println, err::println);
    }

    /** Runs one command; {@code out} and {@code err} get its lines. */
    public int execute(List<String> argv, Consumer<String> out, Consumer<String> err) {
        List<String> args = new ArrayList<>(argv);
        if (!args.isEmpty() && "coverage".equals(args.get(0))) {
            args.remove(0);
        }
        if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
            (args.isEmpty() ? err : out).accept(USAGE);
            return args.isEmpty() ? EXIT_USAGE : EXIT_OK;
        }
        String command = args.remove(0);
        Options options;
        try {
            options = Options.parse(command, args);
        } catch (IllegalArgumentException e) {
            err.accept(e.getMessage());
            err.accept(USAGE);
            return EXIT_USAGE;
        }
        switch (command) {
            case "prepare" -> {
                Rig rig = loadRig(options.rig, err);
                if (rig == null) {
                    return EXIT_USAGE;
                }
                return CoverageDevice.prepare(rig, env, deviceOps.apply(out), null) ? EXIT_OK : EXIT_FAILED;
            }
            case "collect" -> {
                Rig rig = loadRig(options.rig, err);
                if (rig == null) {
                    return EXIT_USAGE;
                }
                return CoverageDevice.collect(rig, env, Path.of(options.out), !options.noReboot,
                        deviceOps.apply(out)) ? EXIT_OK : EXIT_FAILED;
            }
            default -> {
                return report(options, out, err);
            }
        }
    }

    private int report(Options options, Consumer<String> out, Consumer<String> err) {
        Path coverageDir = Path.of(options.dir);
        Path overlay = options.overlay.isEmpty() ? null : Path.of(options.overlay);
        if (overlay == null && !options.build.isEmpty()) {
            overlay = Installed.downloadedOverlays(options.dist, BuildDiscovery.buildsRoot(env)).get(options.build);
        }
        if (overlay == null || !Files.isRegularFile(overlay)) {
            err.accept("FAIL  coverage report — no overlay.zip for "
                    + (options.build.isEmpty() ? "this run (no --build or --overlay)" : "build '" + options.build + "'")
                    + "; the class files come from it");
            return EXIT_FAILED;
        }
        Path parent = coverageDir.toAbsolutePath().normalize().getParent();
        String runId = !options.runId.isEmpty() ? options.runId
                : parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
        CoverageReport.ReportInputs inputs = new CoverageReport.ReportInputs(coverageDir, overlay, options.dist, runId,
                options.build, options.plans, options.sources.isEmpty() ? null : Path.of(options.sources),
                Installed.overlayCommitHash(overlay, options.dist),
                options.resultsRoot.isEmpty() ? ReportPaths.resultsRoot(env) : Path.of(options.resultsRoot));
        CoverageSummary summary;
        try {
            summary = CoverageReport.buildReport(inputs, tools, cli, null, out);
        } catch (JacocoUnavailable e) {
            err.accept("FAIL  coverage report — " + e.getMessage());
            return EXIT_FAILED;
        }
        out.accept(summary.markdown());
        return summary.components.isEmpty() ? EXIT_FAILED : EXIT_OK;
    }

    private Rig loadRig(String name, Consumer<String> err) {
        try {
            return RigLoader.load(name, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.accept("cannot load rig " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** The parsed flags of one command; argparse's rules (required flags, repeatable {@code --plan}). */
    static final class Options {
        String rig = "";
        String out = "";
        boolean noReboot;
        String dir = "";
        String dist = "buypass";
        String build = "";
        String overlay = "";
        String runId = "";
        final List<String> plans = new ArrayList<>();
        String sources = "";
        String resultsRoot = "";

        static Options parse(String command, List<String> args) {
            List<String> allowed = switch (command) {
                case "prepare" -> List.of("--rig");
                case "collect" -> List.of("--rig", "--out", "--no-reboot");
                case "report" -> List.of("--dir", "--dist", "--build", "--overlay", "--run-id", "--plan", "--sources",
                        "--results-root");
                default -> throw new IllegalArgumentException("unknown coverage command: " + command
                        + " (choose from prepare, collect, report)");
            };
            Options o = new Options();
            for (int i = 0; i < args.size(); i++) {
                String flag = args.get(i);
                if (!allowed.contains(flag)) {
                    throw new IllegalArgumentException("unrecognized argument for coverage " + command + ": " + flag);
                }
                if ("--no-reboot".equals(flag)) {
                    o.noReboot = true;
                    continue;
                }
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException(flag + " needs a value");
                }
                String value = args.get(++i);
                switch (flag) {
                    case "--rig" -> o.rig = value;
                    case "--out" -> o.out = value;
                    case "--dir" -> o.dir = value;
                    case "--dist" -> o.dist = value;
                    case "--build" -> o.build = value;
                    case "--overlay" -> o.overlay = value;
                    case "--run-id" -> o.runId = value;
                    case "--plan" -> o.plans.add(value);
                    case "--sources" -> o.sources = value;
                    default -> o.resultsRoot = value;
                }
            }
            if (("prepare".equals(command) || "collect".equals(command)) && o.rig.isEmpty()) {
                throw new IllegalArgumentException("coverage " + command + ": --rig is required");
            }
            if ("collect".equals(command) && o.out.isEmpty()) {
                throw new IllegalArgumentException("coverage collect: --out is required");
            }
            if ("report".equals(command) && o.dir.isEmpty()) {
                throw new IllegalArgumentException("coverage report: --dir is required");
            }
            return o;
        }
    }
}
