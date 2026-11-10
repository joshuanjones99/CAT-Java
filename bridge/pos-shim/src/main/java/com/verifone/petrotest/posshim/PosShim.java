package com.verifone.petrotest.posshim;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

/**
 * The petrotest POS HTTP shim — DESIGN.md §5.2, the artifact §6.1 pushes to the C18.
 *
 * <p>Two halves, started in this order and for a reason:
 *
 * <ol>
 *   <li>{@link ShimRmiHost} hosts the RMI service the retained POS agent connects
 *       into, binding {@code tokenRMI.app} on port 1094.
 *   <li>{@link Routes} serves the state that arrives, on port 8801.
 * </ol>
 *
 * <p>The HTTP listener comes second so that a 200 from {@code /health} means the
 * RMI side finished binding. Starting them together would let `provision`'s
 * post-push verification (§6.1) succeed against a shim that had not yet exported
 * anything — a race that would show up as an intermittently failing provision run
 * and get blamed on the network.
 */
public final class PosShim {

    /**
     * A small pool, deliberately.
     *
     * <p>Key injection blocks: {@code LexTokenRMIServiceImpl.sendToken} waits up
     * to 75 × 200ms for the POS to acknowledge. A cached/unbounded pool would let
     * a caller queue dozens of concurrent keypresses at a POS that can only be in
     * one state at a time, and the interleaving would be untraceable. Four threads
     * lets status reads stay responsive while a control call is in flight, and
     * makes anything beyond that wait its turn.
     */
    private static final int HTTP_THREADS = 4;

    private PosShim() {}

    public static void main(String[] args) {
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        log("build " + (Build.id() == null ? "unstamped" : Build.id()));
        ShimRmiHost host = new ShimRmiHost(config);
        try {
            log("starting RMI host: registry port " + config.rmiPort()
                    + ", client port " + config.rmiClientPort()
                    + ", service '" + config.serviceName() + "'");
            host.start();
            log("RMI host up; seeded clients " + config.seedClients());
        } catch (RuntimeException e) {
            // Exit non-zero rather than serving HTTP without an RMI side. A shim
            // that answers /health but never has state would read to `provision`
            // as "pushed but the POS is silent", sending whoever debugs it to the
            // register's observer config instead of to this log line.
            System.err.println("[pos-shim] FATAL: could not start the RMI host: " + e);
            e.printStackTrace();
            System.exit(1);
            return;
        }

        HttpServer server;
        try {
            server = HttpServer.create(
                    new InetSocketAddress(config.httpBind(), config.httpPort()), 0);
        } catch (Exception e) {
            System.err.println("[pos-shim] FATAL: could not bind HTTP "
                    + config.httpBind() + ":" + config.httpPort() + ": " + e);
            System.exit(1);
            return;
        }
        StateWatcher watcher = new StateWatcher(host);
        watcher.startDaemon();
        server.createContext("/",
                new Routes(host, new Keys(host, config.keyWaitMillis()), new Scanner(host), watcher));
        server.setExecutor(Executors.newFixedThreadPool(HTTP_THREADS));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("shutting down");
            server.stop(1);
        }));
        server.start();
        log("HTTP listening on " + config.httpBind() + ":" + config.httpPort()
                + " — GET / lists the routes");
    }

    private static void log(String message) {
        // stdout, not slf4j: the launcher redirects to a log file the provision
        // transport can read back over the same SSH connection it pushed with,
        // and the shim should not depend on whichever log4j config the register
        // happens to carry.
        System.out.println("[pos-shim] " + message);
    }
}
