package com.verifone.cat.provision;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;

/**
 * {@code GET /health} as the pos-shim answers it through a replacement, on a loopback port. A port of the Python
 * test's {@code _Shim}: its answer depends on which commands the register has run, so the test checks the order.
 *
 * <p>Before the shim restart, the running (old) build with the POS drivable and state present. After it, the new
 * build: read-only and stateless, until the POS restarts; then {@link #silentReads} "drivable but silent" answers,
 * then the POS back. Evidence about this repository only: a fake, not a register.
 */
final class FakeShim implements AutoCloseable {

    static final String CLIENT = "topaz101";
    static final String OLD = "0ld" + "0".repeat(9);
    static final String NEW = "9e7" + "1".repeat(9);
    static final String SHIM_RESTART = "su - topaz -c '/home/topaz/automation/petrotest-pos-shim.sh restart'";

    private final RecordingTransport register;
    private final HttpServer server;
    final AtomicInteger reads = new AtomicInteger();

    volatile String running = OLD;
    volatile String restartedBuild = NEW;
    volatile boolean restartedDown;
    volatile int silentReads = 2;
    volatile boolean posBack = true;
    private int silentSeen;

    FakeShim(RecordingTransport register) throws IOException {
        this.register = register;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", exchange -> {
            String body = answer();
            int status = body.startsWith("{\"error\"") ? 503 : 200;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private synchronized String answer() {
        reads.incrementAndGet();
        List<String> ran = register.ran();
        if (!ran.contains(SHIM_RESTART)) {
            register.record("GET /health", "old shim");
            return health(running, List.of(CLIENT), List.of(), true, List.of());
        }
        if (restartedDown) {
            register.record("GET /health", "no shim");
            return "{\"error\": \"starting\", \"detail\": \"not up\"}";
        }
        if (!ran.contains(RegisterBringup.POS_APP_RESTART)) {
            register.record("GET /health", "new shim, POS not restarted");
            return health(restartedBuild, List.of(), List.of(CLIENT), false, List.of());
        }
        if (!posBack || silentSeen < silentReads) {
            silentSeen++;
            register.record("GET /health", "POS silent");
            return health(restartedBuild, List.of(CLIENT), List.of(), false, List.of(CLIENT));
        }
        register.record("GET /health", "POS back");
        return health(restartedBuild, List.of(CLIENT), List.of(), true, List.of());
    }

    /** The shim's own warning, worded as pos-shim's Routes.java words it, when a POS is drivable but silent. */
    static final String SILENT_WARNING = "POS [" + CLIENT + "] accepts keys but has pushed no status. "
            + "Usually means the shim was restarted while the POS kept running: the agent's static remoteToken "
            + "stub still points at the old shim JVM and is never replaced. Restart the POS to restore the status "
            + "surface.";

    private static String health(String build, List<String> drivable, List<String> readOnly, boolean state,
            List<String> silent) {
        String warning = silent.isEmpty() ? "" : ", \"warning\": \"" + SILENT_WARNING + "\"";
        return "{\"ok\": true, \"build\": " + (build == null ? "null" : "\"" + build + "\"")
                + ", \"uptime_s\": 3, \"clients\": [\"" + CLIENT + "\"]"
                + ", \"drivable_clients\": " + strings(drivable)
                + ", \"read_only_clients\": " + strings(readOnly)
                + ", \"state_present\": " + state
                + ", \"last_change_age_s\": {\"" + CLIENT + "\": 1}"
                + ", \"drivable_but_silent\": " + strings(silent) + warning + "}";
    }

    private static String strings(List<String> items) {
        return items.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
