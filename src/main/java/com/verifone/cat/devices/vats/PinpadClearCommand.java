package com.verifone.cat.devices.vats;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.commander.Say;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java pinpad-clear --rig <name|file.yaml>}: take out a simulated card the physical pinpad still holds from
 * an earlier session. The Python's {@code petrotest pinpad-clear}, which launch step 4 runs on a {@code pinpad: vats}
 * rig before the payment links ({@link PinpadClear}).
 *
 * <p>Exit codes, as the Python's {@code _exit_code}: 0 when {@link PinpadClear#run} returned true (an unreachable
 * terminal included: it is a WARN), 1 when the rig has no {@code vats:} block, 2 for a usage or rig-loading error. The
 * {@code pinpad-clear} word is accepted and dropped.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PinpadClearCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java pinpad-clear --rig <name|file.yaml>",
            "",
            "Take out a simulated card the physical pinpad still holds from an earlier session.",
            "  --rig   rig name; resolves to rigs/<name>.yaml");

    /** The clear for a loaded rig: {@link PinpadClear#run(Rig, Map, Say)} in production. */
    @FunctionalInterface
    public interface Clear {
        boolean run(Rig rig, Map<String, String> env, Say say);
    }

    private final Clear clear;

    public PinpadClearCommand() {
        this(PinpadClear::run);
    }

    /** A command whose clear is a test's own, so nothing is reached. */
    public PinpadClearCommand(Clear clear) {
        this.clear = clear;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "pinpad-clear".equals(args.get(0))) {
            args.remove(0);
        }
        String rigName = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--rig" -> {
                    if (i + 1 >= args.size()) {
                        return usage(err, "--rig needs a rig name or a path");
                    }
                    rigName = args.get(++i);
                }
                case "--help", "-h" -> {
                    out.println(USAGE);
                    return EXIT_OK;
                }
                default -> {
                    return usage(err, "unknown argument: " + arg);
                }
            }
        }
        if (rigName == null) {
            return usage(err, "--rig is required");
        }
        Rig rig;
        try {
            rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
        } catch (RuntimeException e) {
            err.println("cannot load rig " + rigName + ": " + e.getMessage());
            return EXIT_USAGE;
        }
        try {
            return clear.run(rig, env, Say.to(out, err)) ? EXIT_OK : EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println("pinpad-clear: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_FAILED;
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(USAGE);
        err.println("cat-java pinpad-clear: error: " + message);
        return EXIT_USAGE;
    }
}
