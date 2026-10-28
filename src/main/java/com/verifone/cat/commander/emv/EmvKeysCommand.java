package com.verifone.cat.commander.emv;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.commander.Say;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java emv-keys --rig <name|file.yaml> [--tables [always|auto]] [--host-packets FILE]}: the Python's
 * {@code petrotest emv-keys}, which launch step 4 runs with {@code --tables} and the pinpad link repeats without.
 *
 * <p>{@code --tables} alone means {@code always}, as the Python's {@code nargs="?", const="always"}. Exit codes,
 * as the Python's {@code _exit_code}: 0 when {@link EmvKeys#cli} succeeded, 1 when it did not or raised, 2 for a
 * usage or rig-loading error. The {@code emv-keys} word is accepted and dropped. Main does not dispatch to it yet.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class EmvKeysCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java emv-keys --rig <name|file.yaml> [--tables [always|auto]] [--host-packets FILE]",
            "",
            "Download the EMV CA public keys on the primary register, then sign the cashier off.",
            "  --tables        run EPS Network Functions' table load first (after a new install, as PATS does);",
            "                  'auto' only when the Commander has no record of one since its install",
            "  --host-packets  the payment host's packet file, read for each download's answer; default",
            "                  BUYPASS Host's packetFile.txt");

    /** Builds the flow for a rig: {@link EmvKeys#forRig} in production, a test's own otherwise. */
    @FunctionalInterface
    public interface Factory {
        EmvKeys create(Rig rig, Map<String, String> env, Say say);
    }

    private final Factory factory;

    /** The production command. */
    public EmvKeysCommand() {
        this((rig, env, say) -> new EmvKeys(rig, new CommanderAccess(rig, JschTransport::new, env),
                EmvKeys::connectRegister, say));
    }

    /** A command whose flow is built by {@code factory}; tests use it so nothing is reached. */
    public EmvKeysCommand(Factory factory) {
        this.factory = factory;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "emv-keys".equals(args.get(0))) {
            args.remove(0);
        }
        String rigName = null;
        String tables = null;
        Path hostPackets = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--rig" -> {
                    if (i + 1 >= args.size()) {
                        return usage(err, "--rig needs a rig name or a path");
                    }
                    rigName = args.get(++i);
                }
                case "--tables" -> {
                    String next = i + 1 < args.size() ? args.get(i + 1) : null;
                    if (next != null && !next.startsWith("-")) {
                        if (!EmvKeys.TABLES_ALWAYS.equals(next) && !EmvKeys.TABLES_AUTO.equals(next)) {
                            return usage(err, "--tables: invalid choice '" + next + "' (choose from 'always', 'auto')");
                        }
                        tables = next;
                        i++;
                    } else {
                        tables = EmvKeys.TABLES_ALWAYS;
                    }
                }
                case "--host-packets" -> {
                    if (i + 1 >= args.size()) {
                        return usage(err, "--host-packets needs a file");
                    }
                    hostPackets = Path.of(args.get(++i));
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
        Say say = Say.to(out, err);
        try {
            return factory.create(rig, env, say).cli(tables, hostPackets) ? EXIT_OK : EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println("emv-keys FAILED (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return EXIT_FAILED;
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println(USAGE);
        return EXIT_USAGE;
    }
}
