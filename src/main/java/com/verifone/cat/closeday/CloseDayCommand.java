package com.verifone.cat.closeday;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java close-day --rig <name|file.yaml>}: closes the Commander's period, then confirms it in
 * the Commander's log. The Python's {@code petrotest close-day}, which Cleanup runs.
 *
 * <p>Exit codes, as the Python's: 0 when the log shows the close completing; 4 when the Commander refused
 * it because it forces automatic end of day (Cleanup warns on that, rig owner 2026-10-10); 1 otherwise;
 * 2 for a usage or rig-loading error.
 *
 * <p>{@link #run(String[])} takes the arguments after the program name. The {@code close-day} word is
 * accepted and dropped, as the other commands accept theirs. {@code Main} dispatches {@code cat-java close-day}
 * to it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CloseDayCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java close-day --rig <name|file.yaml>",
            "",
            "close-day sends cclosepdnow to the rig's Commander, then waits for the close's outcome in its log.",
            "It exits 0 only when the log shows the period closed, 4 when the Commander forces automatic end of",
            "day and refused the close, and 1 otherwise.");

    /** Builds the close for a rig: production in {@link CloseDay#forRig}, a test's own otherwise. */
    @FunctionalInterface
    public interface Factory {
        CloseDay create(Rig rig, Map<String, String> env, PrintStream out, PrintStream err);
    }

    private final Factory factory;

    /** The production command. */
    public CloseDayCommand() {
        this(CloseDay::forRig);
    }

    /** A command whose close is built by {@code factory}. Tests use this so no Commander is reached. */
    public CloseDayCommand(Factory factory) {
        this.factory = factory;
    }

    /** Runs the command with the process environment and standard streams. */
    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    /** Runs the command: parses {@code argv}, loads the rig, closes the day, and returns the exit code. */
    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "close-day".equals(args.get(0))) {
            args.remove(0);
        }

        String rigName = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--rig".equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println("--rig needs a rig name or a path");
                    err.println(USAGE);
                    return EXIT_USAGE;
                }
                rigName = args.get(++i);
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                out.println(USAGE);
                return EXIT_OK;
            } else {
                err.println("unknown argument: " + arg);
                err.println(USAGE);
                return EXIT_USAGE;
            }
        }
        if (rigName == null) {
            err.println("--rig is required");
            err.println(USAGE);
            return EXIT_USAGE;
        }

        Rig rig;
        try {
            rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.println("cannot load rig " + rigName + ": " + e.getMessage());
            return EXIT_USAGE;
        }

        try {
            return factory.create(rig, env, out, err).close().exitCode();
        } catch (RuntimeException e) {
            err.println("close day FAILED (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return EXIT_FAILED;
        }
    }
}
