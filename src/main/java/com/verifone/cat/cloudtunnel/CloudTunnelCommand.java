package com.verifone.cat.cloudtunnel;

import java.io.PrintStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cloud-tunnel --rig R [--stop] [--force] [--no-gateway]}: the Python {@code petrotest cloud-tunnel}
 * ({@code cloud_tunnel_cli}).
 *
 * <ul>
 * <li>no switch: serves until the process ends ({@link CloudTunnelServer}), or exits 0 with {@code not needed} when
 * the Commander reaches its first endpoint itself. Prints {@code cloud-tunnel: up (<profile>): ...} once its forwards
 * are up, which is what a run waits for ({@link CloudTunnelProcess}).</li>
 * <li>{@code --force}: serve even when the Commander reaches the cloud itself.</li>
 * <li>{@code --no-gateway}: relay only the cloud endpoints, not every outbound TCP connection with DNS.</li>
 * <li>{@code --stop}: removes the hosts lines, redirects, host routes and the gateway, then exits. The sshd block is
 * left (idempotent), as the Python leaves it.</li>
 * </ul>
 *
 * <p>{@code --rig=R} is accepted as argparse accepts it. Ctrl-C (the Python's {@code KeyboardInterrupt}, which it
 * returns as success) stops the loop from a shutdown hook; the JVM's own exit status then applies.
 *
 * <p>Exit codes: 0 served and stopped, not needed, or stopped; 1 failed (no SSH, no endpoints, a refused sshd rule),
 * with {@code cloud-tunnel: <reason>} on standard error; 2 usage. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CloudTunnelCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = "usage: cloud-tunnel --rig R [--stop] [--force] [--no-gateway]";

    private static final Set<String> SWITCHES = Set.of("--stop", "--force", "--no-gateway");

    /** The rig's Commander as the tunnel reaches it: a new connected session per call, and the SSH budget. */
    public record Commander(Supplier<Transport> sessions, Duration timeout) {
        public Commander {
            Objects.requireNonNull(sessions, "sessions");
            Objects.requireNonNull(timeout, "timeout");
        }
    }

    /** Resolves the named rig's Commander: the real one in production (which loads the rig), a fake in tests. */
    @FunctionalInterface
    public interface Connector {
        Commander open(String rigName, Map<String, String> env);
    }

    /** Builds the serve loop for a Commander; tests shorten its timings and fake its DNS. */
    @FunctionalInterface
    public interface ServerFactory {
        CloudTunnelServer create(Commander commander, PrintStream out);
    }

    private final Connector connector;
    private final ServerFactory servers;

    /** The production command. */
    public CloudTunnelCommand() {
        this(CloudTunnelCommand::production, CloudTunnelCommand::productionServer);
    }

    /** A command with its own seams; tests use this so no Commander is reached. */
    public CloudTunnelCommand(Connector connector, ServerFactory servers) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.servers = Objects.requireNonNull(servers, "servers");
    }

    private static Commander production(String rigName, Map<String, String> env) {
        Rig rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
        CommanderAccess access = new CommanderAccess(rig, JschTransport::new, env);
        return new Commander(access::connectedTransport, access.sshTimeout());
    }

    private static CloudTunnelServer productionServer(Commander commander, PrintStream out) {
        return new CloudTunnelServer(commander.sessions(), commander.timeout(), CloudTunnel::controllerDns,
                CloudTunnel.relaySource(), say(out), CloudTunnelServer.Timing.production());
    }

    /** {@code cloud-tunnel: <message>} on {@code out}, flushed at once (the Python's {@code print(..., flush=True)}). */
    public static Consumer<String> say(PrintStream out) {
        return message -> {
            out.println("cloud-tunnel: " + message);
            out.flush();
        };
    }

    /** Runs with the process environment and standard streams. */
    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    /** Parses {@code argv}, runs the action, and returns the exit code. */
    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        String rigName = null;
        Set<String> switches = new HashSet<>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("--rig")) {
                if (i + 1 >= argv.length) {
                    err.println("cloud-tunnel: --rig needs a value\n" + USAGE);
                    return EXIT_USAGE;
                }
                rigName = argv[++i];
            } else if (arg.startsWith("--rig=")) {
                rigName = arg.substring("--rig=".length());
            } else if (SWITCHES.contains(arg)) {
                switches.add(arg);
            } else {
                err.println("cloud-tunnel: unknown argument " + arg + "\n" + USAGE);
                return EXIT_USAGE;
            }
        }
        if (rigName == null || rigName.isBlank()) {
            err.println("cloud-tunnel: --rig is required\n" + USAGE);
            return EXIT_USAGE;
        }
        try {
            Commander commander = connector.open(rigName, env);
            if (switches.contains("--stop")) {
                return stop(commander, out);
            }
            CloudTunnelServer server = servers.create(commander, out);
            Thread hook = new Thread(server::stop, "cloud-tunnel-stop");
            boolean hooked = addHook(hook);
            try {
                server.serve(switches.contains("--force"), !switches.contains("--no-gateway"));
                return EXIT_OK;
            } finally {
                if (hooked) {
                    removeHook(hook);
                }
            }
        } catch (DeviceError | IllegalArgumentException | IllegalStateException e) {
            err.println("cloud-tunnel: " + e.getMessage());
            err.flush();
            return EXIT_FAILED;
        }
    }

    private static int stop(Commander commander, PrintStream out) {
        Transport transport = commander.sessions().get();
        try {
            CloudTunnel.removeRoutes(transport, commander.timeout());
            CloudTunnel.removeGateway(transport, commander.timeout());
        } finally {
            transport.close();
        }
        say(out).accept("removed: hosts lines, redirects, host routes, the gateway");
        return EXIT_OK;
    }

    private static boolean addHook(Thread hook) {
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return true;
        } catch (IllegalStateException e) {
            return false; // the JVM is already shutting down
        }
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // shutting down: the hook has run or will
        }
    }
}
