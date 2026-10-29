package com.verifone.cat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import com.verifone.cat.closeday.CloseDayCommand;
import com.verifone.cat.cloudtunnel.CloudTunnelCommand;
import com.verifone.cat.commander.connect.CommanderConnectCommand;
import com.verifone.cat.commander.snapshot.CommanderSnapshotCommand;
import com.verifone.cat.launch.LaunchCommand;
import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.PreflightCommand;
import com.verifone.cat.provision.ProvisionCommand;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.web.WebApp;
import com.verifone.cat.upgrade.PsiInitCommand;
import com.verifone.cat.commander.emv.EmvKeysCommand;
import com.verifone.cat.xray.XraySidecar;

/**
 * {@code java -jar cat-java.jar --rig lab [--register topaz101] [--port 8090]} starts the web UI;
 * {@code cat-java web --rig lab ...} is the same. Any word in {@link #COMMANDS} ({@code preflight},
 * {@code launch}, {@code provision}, {@code xray}, {@code close-day}, {@code commander-snapshot},
 * {@code commander-connect}, {@code cloud-tunnel}, {@code coverage}, {@code xray-attach}) runs that command instead
 * and exits with its code.
 *
 * <p>Reads the rig, points a {@link PosShimClient} at the register's shim, and serves
 * the web UI on localhost. The web UI is told the rig's name, as typed, so its Start run launches on it.
 */
public final class Main {

    static final int DEFAULT_PORT = 8090;
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(5);

    /** One command word: the class that runs it, and how. */
    record Command(Class<?> type, ToIntFunction<String[]> run) {
    }

    /**
     * Every command word, in the usage's order. Each Python {@code petrotest <command>} that is ported is a
     * {@code <Thing>Command.run(String[])} here. Not here yet, because no {@code *Command} class exists in the tree:
     * {@code secondary-fep}, {@code register-app}, {@code payment-links}, {@code payment-ready},
     * {@code commander-init}, {@code commander-prep}, {@code psi-init}.
     */
    static final Map<String, Command> COMMANDS = commands();

    private static Map<String, Command> commands() {
        Map<String, Command> table = new LinkedHashMap<>();
        table.put("preflight", new Command(PreflightCommand.class, argv -> new PreflightCommand().run(argv)));
        table.put("launch", new Command(LaunchCommand.class, argv -> new LaunchCommand().run(argv)));
        table.put("provision", new Command(ProvisionCommand.class, argv -> new ProvisionCommand().run(argv)));
        table.put("xray", new Command(XraySidecar.class, XraySidecar::run));
        table.put("close-day", new Command(CloseDayCommand.class, argv -> new CloseDayCommand().run(argv)));
        table.put("commander-snapshot",
                new Command(CommanderSnapshotCommand.class, argv -> new CommanderSnapshotCommand().run(argv)));
        table.put("commander-connect",
                new Command(CommanderConnectCommand.class, argv -> new CommanderConnectCommand().run(argv)));
        table.put("cloud-tunnel", new Command(CloudTunnelCommand.class, argv -> new CloudTunnelCommand().run(argv)));
        table.put("psi-init", new Command(PsiInitCommand.class, argv -> new PsiInitCommand().run(argv)));
        table.put("emv-keys", new Command(EmvKeysCommand.class, argv -> new EmvKeysCommand().run(argv)));
        table.put("coverage", new Command(com.verifone.cat.coverage.CoverageCommand.class, com.verifone.cat.coverage.CoverageCommand::run));
        table.put("xray-attach", new Command(com.verifone.cat.xray.Attach.class, com.verifone.cat.xray.Attach::run));
        return Collections.unmodifiableMap(table);
    }

    private static final String USAGE = """
            usage: cat-java [web] --rig <name|file.yaml> [--register <id>] [--port <n>] [--shim-port <n>]
                   cat-java preflight --rig <name|file.yaml> [--register <id>] [--shim-port <n>] [--no-repair]
                   cat-java launch --rig <name|file.yaml> [--plan <key>...]... [--upgrade <mode>] [--exec dev|BDD]
                                   [--no-xray] [--no-coverage] [--cc-onboard] [--fep <name>]... (launch --help)
                   cat-java provision --rig <name> [--force] [--no-stage] [--payload-root <dir>]
                   cat-java xray --run <run-id> [--results-root <dir>] [--once] [--dry-run]
                   cat-java close-day --rig <name|file.yaml> ...
                   cat-java commander-snapshot ACTION ...
                   cat-java commander-connect --rig <name> --lab <lab> --email <e> --service-id <id> ...
                   cat-java cloud-tunnel --rig <name> [--stop] [--force] [--no-gateway]

              web                 the default: the web UI (the word is optional)
              preflight           check the rig and repair what can be repaired; exits 0 when testable
              launch              one run, headless, through the seven steps (run_plan's flags); exits 0 only
                                  when every step completed
              provision           push, verify and stamp the pos-shim payload on every target; exits 0 when provisioned
              xray                the Xray reporting sidecar for a run; exits 0 whatever Xray did
              close-day           close the Commander's day (petrotest close-day)
              commander-snapshot  capture, restore or check a Commander config snapshot (petrotest commander-snapshot)
              commander-connect   onboard the Commander to Commander Connect (petrotest commander-connect)
              cloud-tunnel        the Commander's cloud tunnel (petrotest cloud-tunnel)
              --rig        a rig in $PETROTEST_RIGS_DIR (default C:\\gitrepos\\CAT\\rigs), or a rig file
              --register   which register's shim to use; default the rig's first
              --port       the web UI's port on 127.0.0.1; default 8090
              --shim-port  pos-shim's port on the register; default 8801
            """;

    private Main() {}

    /**
     * A word in {@link #COMMANDS} runs that command and exits with its code. {@code cat-java web ...}, or the
     * same flags with no word, starts the web UI, which keeps running after {@code main} returns.
     */
    public static void main(String[] argv) {
        Command command = argv.length > 0 ? COMMANDS.get(argv[0]) : null;
        if (command != null) {
            System.exit(command.run().applyAsInt(Arrays.copyOfRange(argv, 1, argv.length)));
            return;
        }
        serveWeb(argv.length > 0 && "web".equals(argv[0]) ? Arrays.copyOfRange(argv, 1, argv.length) : argv);
    }

    private static void serveWeb(String[] argv) {
        Options options;
        try {
            options = Options.parse(List.of(argv));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(USAGE);
            System.exit(2);
            return;
        }

        Rig rig;
        Rig.Register register;
        try {
            rig = RigLoader.load(options.rig, RigLoader.rigsDir(System.getenv()));
            register = rig.register(options.register);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        if (!rig.posShim()) {
            System.err.println("Rig '" + rig.name() + "' does not set pos_shim: true, so its register has no shim "
                    + "to read. Provision the shim with the Python CAT (petrotest provision) first.");
            System.exit(2);
            return;
        }

        PosShimClient shim = new PosShimClient(register.host(), options.shimPort, null, SHIM_TIMEOUT);
        String title = "CAT-Java: " + rig.name() + " / " + register.id();
        WebApp.start(shim, title, options.rig, options.port);
        System.out.println(title + " on http://" + WebApp.BIND_HOST + ":" + options.port + "/ (shim " + shim.baseUri() + ")");
    }

    static final class Options {
        String rig;
        String register;
        int port = DEFAULT_PORT;
        int shimPort = PosShimClient.DEFAULT_SHIM_PORT;

        static Options parse(List<String> argv) {
            Options o = new Options();
            Deque<String> args = new ArrayDeque<>(argv);
            while (!args.isEmpty()) {
                String flag = args.pop();
                switch (flag) {
                    case "--rig" -> o.rig = value(flag, args);
                    case "--register" -> o.register = value(flag, args);
                    case "--port" -> o.port = port(flag, value(flag, args));
                    case "--shim-port" -> o.shimPort = port(flag, value(flag, args));
                    case "-h", "--help" -> throw new IllegalArgumentException("");
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

        private static int port(String flag, String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " must be a port number, not '" + value + "'.");
            }
        }
    }
}
