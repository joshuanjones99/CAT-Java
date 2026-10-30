package com.verifone.cat.provision;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import com.verifone.cat.collect.Collect;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.SshTarget;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cat-java build-pos-shim [--deps-dir D] [--pull-deps-from HOST] [--rig R] [--ssh-key K] [--petroapps P]
 * [--payload P] [--check]}: the Python CAT's {@code python tools/build_pos_shim.py}, with its flags, over
 * {@link PosShimBuild}.
 *
 * <p>{@code --pull-deps-from HOST} fetches every compile jar from that register as root with {@code --ssh-key}
 * (default {@code $PETROTEST_COMMANDER_SSH_KEY}); {@code --rig R}, when the cache lacks a jar, fetches the missing ones
 * from the rig's first register. Exit codes: 0 built (or compiled, with {@code --check}), 1 a {@link
 * PosShimBuild.BuildError}, 2 usage.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PosShimBuildCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = "usage: cat-java build-pos-shim [--deps-dir D] [--pull-deps-from HOST] [--rig R] "
            + "[--ssh-key K] [--petroapps P] [--payload P] [--check]";

    private final Function<SshTarget, Transport> transports;
    private final PosShimBuild.Compiler compiler;

    /** The production command: JSch and this JVM's javac. */
    public PosShimBuildCommand() {
        this(JschTransport::new, PosShimBuild.javac());
    }

    /** A command over given seams; tests pass in-memory transports. */
    public PosShimBuildCommand(Function<SshTarget, Transport> transports, PosShimBuild.Compiler compiler) {
        this.transports = transports;
        this.compiler = compiler;
    }

    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        Path deps = PosShimBuild.defaultDeps(env);
        String pullFrom = null;
        String rigName = null;
        String sshKey = env.get(PosShimBuild.SSH_KEY_ENV);
        String petroapps = env.getOrDefault("PETROAPPS_ROOT", "");
        Path payload = PosShimBuild.defaultPayload(env);
        boolean check = false;
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("--check")) {
                check = true;
                continue;
            }
            if (arg.equals("-h") || arg.equals("--help")) {
                out.println(USAGE);
                return EXIT_OK;
            }
            if (i + 1 >= argv.length || !arg.startsWith("--")) {
                err.println("build-pos-shim: " + (arg.startsWith("--") ? arg + " needs a value" : "unknown argument "
                        + arg) + "\n" + USAGE);
                return EXIT_USAGE;
            }
            String value = argv[++i];
            switch (arg) {
                case "--deps-dir" -> deps = Path.of(value);
                case "--pull-deps-from" -> pullFrom = value;
                case "--rig" -> rigName = value;
                case "--ssh-key" -> sshKey = value;
                case "--petroapps" -> petroapps = value;
                case "--payload" -> payload = Path.of(value);
                default -> {
                    err.println("build-pos-shim: unknown argument " + arg + "\n" + USAGE);
                    return EXIT_USAGE;
                }
            }
        }
        Path petroappsRoot = Path.of(petroapps.isBlank() ? Collect.DEFAULT_PETROAPPS_ROOT : petroapps);
        try {
            if (pullFrom != null) {
                out.println("Fetching build dependencies from " + pullFrom);
                try (Transport transport = transports.apply(new SshTarget("register", pullFrom, SshTarget.DEFAULT_PORT,
                        "root", sshKey == null || sshKey.isBlank() ? null : sshKey))) {
                    PosShimBuild.pullDeps(transport, deps, false, out::println);
                }
            } else if (rigName != null && PosShimBuild.checkDepsMissing(deps)) {
                Rig rig;
                try {
                    rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
                } catch (RuntimeException e) {
                    err.println("cannot load rig " + rigName + ": " + e.getMessage());
                    return EXIT_USAGE;
                }
                PosShimBuild.pullDepsFromRig(rig, env, deps, transports, out::println);
            }
            PosShimBuild.build(new PosShimBuild.Options(ProvisionPaths.pythonRoot(env), petroappsRoot, deps, payload,
                    check), compiler, out::println);
            return EXIT_OK;
        } catch (PosShimBuild.BuildError e) {
            err.println();
            err.println("ERROR: " + e.getMessage());
            return EXIT_FAILED;
        }
    }
}
