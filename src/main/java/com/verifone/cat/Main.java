package com.verifone.cat;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.preflight.PreflightCommand;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.web.WebApp;

/**
 * {@code java -jar cat-java.jar --rig lab [--register topaz101] [--port 8090]} starts the web UI;
 * {@code cat-java web --rig lab ...} is the same. {@code cat-java preflight --rig lab ...} runs the
 * preflight checks instead.
 *
 * <p>Reads the rig, points a {@link PosShimClient} at the register's shim, and serves
 * the web UI on localhost.
 */
public final class Main {

    static final int DEFAULT_PORT = 8090;
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(5);

    private static final String USAGE = """
            usage: cat-java [web] --rig <name|file.yaml> [--register <id>] [--port <n>] [--shim-port <n>]
                   cat-java preflight --rig <name|file.yaml> [--register <id>] [--shim-port <n>] [--no-repair]

              web          the default: the web UI (the word is optional)
              preflight    check the rig and repair what can be repaired; exits 0 when testable
              --rig        a rig in $PETROTEST_RIGS_DIR (default C:\\gitrepos\\CAT\\rigs), or a rig file
              --register   which register's shim to use; default the rig's first
              --port       the web UI's port on 127.0.0.1; default 8090
              --shim-port  pos-shim's port on the register; default 8801
            """;

    private Main() {}

    /**
     * {@code cat-java preflight ...} runs {@link PreflightCommand} and exits with its code.
     * {@code cat-java web ...}, or the same flags with no word, starts the web UI.
     */
    public static void main(String[] argv) {
        if (argv.length > 0 && "preflight".equals(argv[0])) {
            System.exit(new PreflightCommand().run(Arrays.copyOfRange(argv, 1, argv.length)));
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
        WebApp.start(shim, title, options.port);
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
