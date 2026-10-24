package com.verifone.cat.cloudtunnel;

import java.io.PrintStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.provision.transport.Transport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code cloud-tunnel --rig R [--stop] [--force] [--no-gateway]}: the Python {@code petrotest cloud-tunnel}'s command
 * line, for what this port does.
 *
 * <ul>
 * <li>{@code --stop}: removes the hosts lines, redirects, host routes and the gateway. Done fully.</li>
 * <li>no switch: clears a stale gateway, then reports {@code not needed} when the Commander reaches its first
 * endpoint itself. Otherwise it fails: the tunnel's serve loop is not ported (see {@link CloudTunnel}).</li>
 * </ul>
 *
 * <p>{@code --no-gateway} and {@code --force} are accepted for the same command line; they only affect the serve loop,
 * which this port does not run.
 *
 * <p>Exit codes: 0 done or not needed, 1 failed, 2 usage. UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CloudTunnelCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = "usage: cloud-tunnel --rig R [--stop] [--force] [--no-gateway]";
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> SWITCHES = Set.of("--stop", "--force", "--no-gateway");

    /**
     * Opens the transport to the Commander of the named rig: the real one in production (which loads the rig), a fake
     * in tests.
     */
    @FunctionalInterface
    public interface Connector {
        Transport open(String rigName, Map<String, String> env);
    }

    private final Connector connector;

    /** The production command. */
    public CloudTunnelCommand() {
        this((rigName, env) -> {
            Rig rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
            return new CommanderAccess(rig, JschTransport::new, env).connectedTransport();
        });
    }

    /** A command with its own transport seam; tests use this so no Commander is reached. */
    public CloudTunnelCommand(Connector connector) {
        this.connector = connector;
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
            Transport transport = connector.open(rigName, env);
            try {
                return switches.contains("--stop") ? stop(transport, out)
                        : start(transport, switches.contains("--force"), out, err);
            } finally {
                transport.close();
            }
        } catch (DeviceUnavailable e) {
            err.println("cloud-tunnel: " + e.getMessage());
            return EXIT_FAILED;
        } catch (DeviceError | IllegalArgumentException | IllegalStateException e) {
            err.println("cloud-tunnel: " + e.getMessage());
            return EXIT_FAILED;
        }
    }

    private static int stop(Transport transport, PrintStream out) {
        CloudTunnel.removeRoutes(transport, TIMEOUT);
        CloudTunnel.removeGateway(transport, TIMEOUT);
        out.println("cloud-tunnel: removed: hosts lines, redirects, host routes, the gateway");
        return EXIT_OK;
    }

    private static int start(Transport transport, boolean force, PrintStream out, PrintStream err) {
        CloudTunnel.Discovery found = CloudTunnel.discover(transport, TIMEOUT);
        // A relay left by a tunnel that died accepts every connect, so the reachability check would pass.
        CloudTunnel.removeGateway(transport, TIMEOUT);
        Endpoint first = found.endpoints().get(0);
        if (!force && CloudTunnel.wanUp(transport, first, TIMEOUT)) {
            out.println("cloud-tunnel: not needed - the Commander reaches " + first + " itself");
            return EXIT_OK;
        }
        err.println("cloud-tunnel: the tunnel's serve loop is not ported yet (SSH reverse forwards need a Transport"
                + " method; see CloudTunnel). Profile " + found.profile() + " needs a tunnel to " + first + ".");
        return EXIT_FAILED;
    }
}
