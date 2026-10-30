package com.verifone.cat.upgrade;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.commander.Say;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java psi-init --rig <name|file.yaml> [--no-pairing]}: after a PSI install, reset the Commander password,
 * then pair every register. The Python's {@code petrotest psi-init}.
 *
 * <p>Exit codes, as the Python's: 0 when it all succeeded, 1 when it failed (its reason is the last stderr line), 2 for
 * a usage or rig-loading error. The {@code psi-init} word is accepted and dropped, as the other commands accept
 * theirs. {@code Main} dispatches {@code cat-java psi-init} to it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PsiInitCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java psi-init --rig <name|file.yaml> [--no-pairing]",
            "",
            "After a PSI install: reset the Commander password, then pair every register.",
            "  --no-pairing  only reset the Commander password; pair the registers on a later call");

    /** Builds the psi-init for a rig: production in {@link PsiInit#forRig}, a test's own otherwise. */
    @FunctionalInterface
    public interface Factory {
        PsiInit create(Rig rig, Map<String, String> env, Say say);
    }

    private final Factory factory;

    public PsiInitCommand() {
        this(PsiInit::forRig);
    }

    public PsiInitCommand(Factory factory) {
        this.factory = factory;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "psi-init".equals(args.get(0))) {
            args.remove(0);
        }
        String rigName = null;
        boolean pair = true;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--rig".equals(arg)) {
                if (i + 1 >= args.size()) {
                    err.println("--rig needs a rig name or a path");
                    err.println(USAGE);
                    return EXIT_USAGE;
                }
                rigName = args.get(++i);
            } else if ("--no-pairing".equals(arg)) {
                pair = false;
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
            return factory.create(rig, env, Say.to(out, err)).run(rig, pair) ? EXIT_OK : EXIT_FAILED;
        } catch (RuntimeException e) {
            // The Python's traceback: anything psi_init does not catch (a Playwright error, say) exits 1.
            err.println("psi-init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EXIT_FAILED;
        }
    }
}
