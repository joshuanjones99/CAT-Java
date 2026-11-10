package com.verifone.petrotest.posshim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line configuration for the shim.
 *
 * <p>Defaults are chosen to match what already exists rather than to be
 * memorable: {@code 1094} and {@code tokenRMI.app} are the retained agent's own
 * {@code DEFAULT_RMI_PORT}/{@code DEFAULT_APP_NAME}, {@code 5003} its
 * {@code DEFAULT_CLIENT_PORT}, and {@code 8801} is
 * {@code provision.verify.DEFAULT_SHIM_PORT} on the Python side. Changing any of
 * them means changing it in two places, which is why each default names its
 * source here.
 */
final class Config {

    private int httpPort = 8801;
    private String httpBind = "0.0.0.0";
    private int rmiPort = 1094;
    private int rmiClientPort = 5003;
    private String serviceName = "tokenRMI.app";
    private final List<String> seedClients = new ArrayList<>();
    private int keyWaitMillis = 4000;

    static Config parse(String[] args) {
        Config config = new Config();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--http-port" -> config.httpPort = intArg(args, ++i, arg);
                case "--http-bind" -> config.httpBind = stringArg(args, ++i, arg);
                case "--rmi-port" -> config.rmiPort = intArg(args, ++i, arg);
                case "--rmi-client-port" -> config.rmiClientPort = intArg(args, ++i, arg);
                case "--service-name" -> config.serviceName = stringArg(args, ++i, arg);
                case "--client" -> config.seedClients.add(stringArg(args, ++i, arg));
                case "--key-wait-ms" -> config.keyWaitMillis = intArg(args, ++i, arg);
                case "--help", "-h" -> {
                    System.out.println(usage());
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException(
                        "unknown argument " + arg + "\n\n" + usage());
            }
        }
        if (config.seedClients.isEmpty()) {
            // Co-located with the POS, so the name the agent will call itself is
            // this host's name — POSStatusRMIService derives its client id from
            // InetAddress.getLocalHost().getHostName() too. Seeding it means the
            // holder exists before the first panel event rather than after the
            // first dropped one (see ShimRmiHost.ensureHolder).
            config.seedClients.add(ShimRmiHost.localHostName());
        }
        return config;
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "petrotest POS HTTP shim — DESIGN.md §5.2",
                "",
                "Hosts the RMI service the retained POS automation agent connects into, and",
                "serves the agent's pushed screen state over HTTP for petrotest to read.",
                "",
                "Usage: java -jar petrotest-pos-shim.jar [options]",
                "",
                "  --http-port N         HTTP listen port (default 8801)",
                "  --http-bind ADDR      HTTP bind address (default 0.0.0.0)",
                "  --rmi-port N          RMI registry port the POS dials (default 1094)",
                "  --rmi-client-port N   RMI client callback port (default 5003)",
                "  --service-name NAME   bound RMI service name (default tokenRMI.app)",
                "  --client HOST         seed a client holder; repeatable",
                "                        (default: this host's name)",
                "  --key-wait-ms N       how long a key injection waits for the POS to",
                "                        report the token processed (default 4000)",
                "  -h, --help            this message");
    }

    private static String stringArg(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    private static int intArg(String[] args, int index, String flag) {
        String raw = stringArg(args, index, flag);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " needs a number, got " + raw);
        }
    }

    int httpPort() {
        return httpPort;
    }

    String httpBind() {
        return httpBind;
    }

    int rmiPort() {
        return rmiPort;
    }

    int rmiClientPort() {
        return rmiClientPort;
    }

    String serviceName() {
        return serviceName;
    }

    List<String> seedClients() {
        return Arrays.asList(seedClients.toArray(new String[0]));
    }

    int keyWaitMillis() {
        return keyWaitMillis;
    }
}
