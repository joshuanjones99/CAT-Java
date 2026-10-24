package com.verifone.cat.commander.connect;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.verifone.cat.commander.CommanderAccess;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.JschTransport;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigConfigException;
import com.verifone.cat.rig.RigLoader;

/**
 * {@code commander-connect --rig R --lab L --email E --service-id ID}: validates the values, then onboards the
 * Commander. The password comes from {@code $COMMANDER_CONNECT_PASSWORD}, never argv. A port of the Python
 * {@code petrotest commander-connect} command ({@code cli.py}) and {@code commander_connect_cli}.
 *
 * <p>Exit codes, as the Python's: 0 onboarded; 1 a value is wrong (nothing is sent) or a step failed; 2 usage, or a
 * rig file that does not load. As in the Python the rig is loaded first, then the values are checked, then the
 * Commander is touched.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class CommanderConnectCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String PREFIX = "commander-connect: ";
    static final String USAGE = "usage: commander-connect --rig R --lab {Prod,CST,QAT,DEV} --email E --service-id ID"
            + " (password: $" + CommanderConnect.PASSWORD_ENV + ")";

    private static final Set<String> OPTIONS = Set.of("--rig", "--lab", "--email", "--service-id");

    /**
     * Builds the onboarding for the named rig: the real one in production (which loads the rig and wires SSH and
     * CGILink), a fake in tests. It must not reach the Commander itself; the service's suppliers do that, later.
     *
     * @throws RigConfigException when the rig does not load
     */
    @FunctionalInterface
    public interface Wiring {
        ConnectService open(String rigName, Map<String, String> env, Consumer<String> say);
    }

    private final Wiring wiring;

    /** The production command: SSH through {@link CommanderAccess}, CGILink through {@link PdkShimConnectClient}. */
    public CommanderConnectCommand() {
        this((rigName, env, say) -> {
            Rig rig = RigLoader.load(rigName, RigLoader.rigsDir(env));
            CommanderAccess access = new CommanderAccess(rig, JschTransport::new, env);
            return new ConnectService(access::connectedTransport, () -> new PdkShimConnectClient(access.pdkShim()),
                    ConnectTiming.defaults(), access.sshTimeout(), say);
        });
    }

    /** A command with its own wiring; tests use this so no Commander is reached. */
    public CommanderConnectCommand(Wiring wiring) {
        this.wiring = wiring;
    }

    /** Runs with the process environment and standard streams. */
    public int run(String[] argv) {
        return run(argv, System.getenv(), System.out, System.err);
    }

    /** Parses {@code argv}, onboards, and returns the exit code. */
    public int run(String[] argv, Map<String, String> env, PrintStream out, PrintStream err) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (!OPTIONS.contains(name)) {
                err.println(PREFIX + "unknown argument " + arg + "\n" + USAGE);
                return EXIT_USAGE;
            }
            if (value == null) {
                if (i + 1 >= argv.length) {
                    err.println(PREFIX + name + " needs a value\n" + USAGE);
                    return EXIT_USAGE;
                }
                value = argv[++i];
            }
            values.put(name, value);
        }
        for (String required : new String[] {"--rig", "--lab", "--email", "--service-id"}) {
            if (!values.containsKey(required)) {
                err.println(PREFIX + required + " is required\n" + USAGE);
                return EXIT_USAGE;
            }
        }
        if (values.get("--rig").isBlank()) {
            err.println(PREFIX + "--rig is required\n" + USAGE);
            return EXIT_USAGE;
        }
        Consumer<String> say = line -> {
            out.println(PREFIX + line);
            out.flush();
        };
        ConnectService service;
        try {
            service = wiring.open(values.get("--rig"), env, say);
        } catch (RigConfigException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
        Map<String, String> environ = env == null ? Map.of() : env;
        CommanderConnect settings = new CommanderConnect(values.get("--lab"), values.get("--email"),
                values.get("--service-id"), environ.getOrDefault(CommanderConnect.PASSWORD_ENV, ""));
        return cli(service, settings, out, err);
    }

    /**
     * The Python's {@code commander_connect_cli}: validate, then apply. A wrong value is named and nothing is sent.
     *
     * @return {@link #EXIT_OK} or {@link #EXIT_FAILED}
     */
    public static int cli(ConnectService service, CommanderConnect settings, PrintStream out, PrintStream err) {
        var problems = settings.problems();
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                err.println(PREFIX + problem);
            }
            return EXIT_FAILED;
        }
        try {
            service.apply(settings);
        } catch (DeviceError | UncheckedIOException e) {
            // The Python's (DeviceError, DeviceUnavailable, OSError); DeviceUnavailable is a DeviceError here.
            err.println(PREFIX + e.getMessage());
            return EXIT_FAILED;
        }
        out.println(PREFIX + "onboarded to " + settings.lab() + " as " + settings.email());
        return EXIT_OK;
    }
}
