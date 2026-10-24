package com.verifone.cat.pos.fake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The fake POS: a real page served over HTTP on loopback, for off-rig test execution. Ported from
 * the Python {@code petrotest.pos.fake} (the server, the handler and {@code fake_pos}).
 *
 * <p><b>This is evidence about this repository's test code, not about a register.</b> A run that
 * used it prints {@link FakePosBanner}. The page's element IDs come from {@link
 * com.verifone.cat.pos.Locators}; the state machine is {@link FakePosState}.
 *
 * <p>The server binds the loopback address only, on an ephemeral port, and serves on one thread,
 * as the Python's {@code HTTPServer} does, so state changes are ordered. Unlike the Python, the
 * MOP hooks belong to this instance and not to module globals, so two fake POS instances do not
 * share a pinpad.
 *
 * <p>Routes: {@code GET /} (the page), {@code /api/state}, {@code /api/select?item=},
 * {@code /api/mop?mop=}, {@code /api/total}, {@code /api/receipt}. Anything else is 404; a method
 * other than GET is 405.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's Playwright {@code PosDriver} wrapper is
 * not ported here; this class serves the page and its state only.
 */
public final class FakePos implements AutoCloseable {

    /** The MOP hooks, the seam to the pinpad double. Either may be null. */
    public record Hooks(Runnable onCashMop, Runnable onCreditMop) {

        /** No hooks: the fake POS runs with no pinpad behind it. */
        public static final Hooks NONE = new Hooks(null, null);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Hooks hooks;
    private final FakePosState state = new FakePosState();
    private HttpServer server;
    private ExecutorService executor;
    private URI url;

    private FakePos(Hooks hooks) {
        this.hooks = hooks == null ? Hooks.NONE : hooks;
    }

    /** Starts a fake POS on a free loopback port. Close it when done. */
    public static FakePos open(Hooks hooks) throws IOException {
        FakePos pos = new FakePos(hooks);
        pos.start();
        return pos;
    }

    private void start() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fake-pos");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        InetSocketAddress bound = server.getAddress();
        url = URI.create("http://" + bound.getAddress().getHostAddress() + ":" + bound.getPort() + "/");
    }

    /** The base URL: the loopback address and the bound port, with a trailing slash. */
    public URI url() {
        return url;
    }

    /** The bound port. */
    public int port() {
        return server.getAddress().getPort();
    }

    /** A snapshot of the state the next request would see. */
    public FakePosState.Snapshot state() {
        return state.snapshot();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/" -> respond(exchange, 200, "text/html; charset=utf-8",
                        FakePosPage.render(state.snapshot()));
                case "/api/state" -> json(exchange, stateJson());
                case "/api/select" -> {
                    int item = intParam(exchange, "item");
                    state.selectItem(item);
                    json(exchange, stateJson());
                }
                case "/api/mop" -> {
                    int mop = intParam(exchange, "mop");
                    state.tender(mop, hooks.onCashMop(), hooks.onCreditMop());
                    json(exchange, stateJson());
                }
                case "/api/total" -> json(exchange, stateJson());
                case "/api/receipt" -> respond(exchange, 200, "text/html; charset=utf-8",
                        FakePosPage.receiptHtml(state.snapshot().selectedItems()));
                default -> respond(exchange, 404, "text/plain; charset=utf-8", "not found");
            }
        } catch (BadRequest bad) {
            respond(exchange, 400, "text/plain; charset=utf-8", bad.getMessage());
        } catch (RuntimeException failed) {
            respond(exchange, 500, "text/plain; charset=utf-8", String.valueOf(failed.getMessage()));
        }
    }

    /** The JSON the page reads: the transaction flag, the sale, and the receipt rendered from it. */
    private Map<String, Object> stateJson() {
        FakePosState.Snapshot snapshot = state.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("is_transaction_active", snapshot.transactionActive());
        body.put("selected_items", snapshot.selectedItems());
        body.put("receipt_html", FakePosPage.receiptHtml(snapshot.selectedItems()));
        return body;
    }

    private static void json(HttpExchange exchange, Map<String, Object> body) throws IOException {
        respond(exchange, 200, "application/json", JSON.writeValueAsString(body));
    }

    private static void respond(HttpExchange exchange, int status, String type, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * The first value of a query parameter, read as an int. A missing parameter is 0, as the
     * Python's {@code query.get(name, [0])} gives. A value that is not an int is a 400.
     */
    static int intParam(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return 0;
        }
        List<String> values = List.of(raw.split("&"));
        for (String pair : values) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(key)) {
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException notAnInt) {
                    throw new BadRequest(name + " is not an integer: " + value);
                }
            }
        }
        return 0;
    }

    /** A request the fake POS cannot read. */
    static final class BadRequest extends RuntimeException {

        BadRequest(String message) {
            super(message);
        }
    }
}
