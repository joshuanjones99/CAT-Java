package com.verifone.cat.sims;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in simulator for tests: the test launches it itself, from {@code target/test-classes},
 * with the test JDK. It is never a PATS simulator.
 *
 * <p>Arguments: {@code <port> <token> <mode>}. The token is only there to be seen on the command
 * line. Modes:
 * <ul>
 *   <li>{@code ready}: binds {@code port}, prints {@code READY}, serves until killed or 120 s.</li>
 *   <li>{@code silent}: never binds; holds for 120 s.</li>
 *   <li>{@code idle}: prints a line and holds; no port.</li>
 *   <li>{@code exit}: prints a line and exits 3.</li>
 *   <li>{@code trip-once}: the first run prints the JAXB line and holds without binding; a
 *       marker file in its working directory makes every later run serve like {@code ready}.</li>
 * </ul>
 */
public final class FakeSimulator {

    private static final long HOLD_SECONDS = 120;

    private FakeSimulator() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        String mode = args.length > 2 ? args[2] : "ready";
        switch (mode) {
            case "ready" -> serve(port);
            case "silent" -> hold();
            case "idle" -> {
                System.out.println("IDLE");
                System.out.flush();
                hold();
            }
            case "exit" -> {
                System.out.println("boot failed");
                System.out.flush();
                System.exit(3);
            }
            case "trip-once" -> {
                Path marker = Path.of("").toAbsolutePath().resolve("tripped");
                if (Files.exists(marker)) {
                    serve(port);
                } else {
                    Files.createFile(marker);
                    System.out.println("javax/xml/bind/JAXBException: simulated");
                    System.out.flush();
                    hold();
                }
            }
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    private static void serve(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("READY");
            System.out.flush();
            hold();
        }
    }

    private static void hold() {
        try {
            new CountDownLatch(1).await(HOLD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
