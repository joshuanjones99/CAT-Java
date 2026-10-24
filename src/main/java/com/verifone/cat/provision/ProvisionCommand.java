package com.verifone.cat.provision;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.function.Function;

import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code provision --rig <name> [--force] [--no-stage] [--payload-root <dir>]}, and {@code render-payload --rig <name>
 * [--payload-root <dir>]}: the CLI entries of DESIGN.md 6.1 and HANDOFF 6 item 1. A port of
 * {@code petrotest.provision.cli} and the {@code render-payload} command.
 *
 * <p>{@link #run} takes the arguments after the program name. A leading {@code provision} word is accepted and
 * skipped, so the dispatcher may pass the whole command line. Exit codes: 0 provisioned or rendered clean, 1 a
 * target failed or a template left a placeholder, 2 a usage or rig-load error.
 *
 * <p>The report is printed whether or not the run passed: the evidence is in the report, not the exit code.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class ProvisionCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java provision --rig <name> [--force] [--no-stage] [--payload-root <dir>]",
            "       cat-java render-payload --rig <name> [--payload-root <dir>]",
            "",
            "provision pushes the pos-shim payload to every target of the rig, verifies it, and stamps it.",
            "The payload is built by the Python CAT (tools/build_pos_shim.py); CAT-Java reads its output.");

    private final Function<TargetSpec, Transport> transports;
    private final Function<TargetSpec, AgentProbe> probes;

    /** The production command: real SSH to the rig's boxes, and the live shim's HTTP probe. */
    public ProvisionCommand() {
        this(null, null);
    }

    /**
     * A command with the transport and probe per box injected. Null means the production one. Tests use this so no
     * real host is ever reached.
     */
    public ProvisionCommand(Function<TargetSpec, Transport> transports, Function<TargetSpec, AgentProbe> probes) {
        this.transports = transports;
        this.probes = probes;
    }

    /** Runs the command with the process environment and standard streams. */
    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    /** Runs the command: parses {@code argv}, loads the rig, does the work, prints the report. */
    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        Deque<String> args = new ArrayDeque<>(Arrays.asList(argv));
        boolean render = false;
        if (!args.isEmpty() && args.peekFirst().equals("render-payload")) {
            render = true;
            args.pollFirst();
        } else if (!args.isEmpty() && args.peekFirst().equals("provision")) {
            args.pollFirst();
        }

        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        }
        if (options.help) {
            out.println(USAGE);
            return EXIT_OK;
        }
        if (options.rig == null) {
            err.println("--rig is required");
            err.println(USAGE);
            return EXIT_USAGE;
        }

        Rig rig;
        try {
            rig = RigLoader.load(options.rig, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.println("cannot load rig " + options.rig + ": " + e.getMessage());
            return EXIT_USAGE;
        }

        Path payloadRoot = options.payloadRoot != null ? options.payloadRoot : ProvisionPaths.payloadRoot(env);
        if (render) {
            Render.Report report = Render.renderPayload(rig.name(), payloadRoot, ProvisionPaths.templatesRoot(env),
                    Map.of());
            out.println(report.render());
            return report.ok() ? Render.EXIT_OK : Render.EXIT_FAILED;
        }

        ProvisionOptions provision = ProvisionOptions.defaults()
                .force(options.force)
                .stage(!options.noStage)
                .payloadRoot(payloadRoot)
                .stageSources(Stage.Sources.fromEnv(env))
                .environ(env)
                .toolVersion("cat-java");
        if (transports != null) {
            provision.transports(transports);
        }
        if (probes != null) {
            provision.probes(probes);
        }
        ProvisionReport report;
        try {
            report = ProvisionRunner.provisionRig(rig, provision);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_FAILED;
        }
        out.println(report.render());
        return report.ok() ? EXIT_OK : EXIT_FAILED;
    }

    /** The flags of both commands. */
    private static final class Options {
        private String rig;
        private boolean force;
        private boolean noStage;
        private Path payloadRoot;
        private boolean help;

        static Options parse(Deque<String> args) {
            Options o = new Options();
            while (!args.isEmpty()) {
                String flag = args.pollFirst();
                switch (flag) {
                    case "--rig" -> o.rig = value(flag, args);
                    case "--force" -> o.force = true;
                    case "--no-stage" -> o.noStage = true;
                    case "--payload-root" -> o.payloadRoot = Path.of(value(flag, args));
                    case "-h", "--help" -> o.help = true;
                    default -> throw new IllegalArgumentException("unknown argument: " + flag);
                }
            }
            return o;
        }

        private static String value(String flag, Deque<String> args) {
            String value = args.pollFirst();
            if (value == null || value.startsWith("--")) {
                throw new IllegalArgumentException(flag + " needs a value");
            }
            return value;
        }
    }
}
