package com.verifone.cat.preflight;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.rig.LoadedRig;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code preflight --rig <name|file.yaml> [--register <id>] [--shim-port <n>] [--no-repair]}.
 * The Python counterpart is {@code petrotest/preflight/cli.py}: load the rig, build the context,
 * run the checks, print the report, and map to an exit code.
 *
 * <p>Exit codes follow the Python CLI: 0 when the rig is testable, 1 when it is not, 2 for a usage
 * or load error. "Testable" is {@link PreflightReport#testable()}, so a probe with no source on this
 * port (ERROR) does not fail the run, while a readable check that fails does.
 *
 * <p>Not wired into {@code Main}: the orchestrator decides that. {@link #run(String[])} is the entry.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PreflightCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE =
            "usage: preflight --rig <name|file.yaml> [--register <id>] [--shim-port <n>] [--no-repair]";

    private static final Duration VERIFY_BUDGET = PreflightRunner.VERIFY_BUDGET;

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    /** The testable seam: the environment, the rigs folder and both streams are passed in. */
    int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(argv);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        }

        LoadedRig loaded;
        try {
            loaded = RigLoader.loadResolved(options.rig, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.println("Cannot load rig '" + options.rig + "': " + e.getMessage());
            return EXIT_USAGE;
        }
        Rig rig = loaded.rig();

        Rig.Register register;
        try {
            register = rig.register(options.register);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        if (!rig.posShim()) {
            err.println("Rig '" + rig.name() + "' does not set pos_shim: true, so its register has no shim to probe.");
            return EXIT_USAGE;
        }

        RigContext context = RigContextFactory.build(rig, register, options.shimPort, env);
        PreflightReport report = PreflightRunner.run(context, DefaultChecks.defaults().checks(),
                new PreflightRunner.RunOptions(options.repair, VERIFY_BUDGET, false));
        out.println(report.render());

        if (report.testable()) {
            return EXIT_OK;
        }
        String verdict = "rig " + rig.name() + " is not testable";
        if (!report.unhealthyChecks().isEmpty()) {
            verdict += " — failed verification twice: " + String.join(", ", report.unhealthyChecks());
        }
        out.println("\n" + verdict);
        return EXIT_FAILED;
    }

    /** The parsed command line. */
    private static final class Options {
        String rig;
        String register;
        int shimPort = PosShimClient.DEFAULT_SHIM_PORT;
        boolean repair = true;

        static Options parse(String[] argv) {
            Options o = new Options();
            Deque<String> args = new ArrayDeque<>(Arrays.asList(argv));
            while (!args.isEmpty()) {
                String flag = args.pop();
                switch (flag) {
                    case "--rig" -> o.rig = value(flag, args);
                    case "--register" -> o.register = value(flag, args);
                    case "--shim-port" -> o.shimPort = port(value(flag, args));
                    case "--no-repair" -> o.repair = false;
                    default -> throw new IllegalArgumentException("Unknown option " + flag + ".");
                }
            }
            if (o.rig == null) {
                throw new IllegalArgumentException("--rig is required.");
            }
            return o;
        }

        private static String value(String flag, Deque<String> args) {
            if (args.isEmpty()) {
                throw new IllegalArgumentException(flag + " needs a value.");
            }
            return args.pop();
        }

        private static int port(String text) {
            try {
                int port = Integer.parseInt(text);
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("--shim-port must be 1-65535, got " + text + ".");
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--shim-port must be a number, got " + text + ".");
            }
        }
    }
}
