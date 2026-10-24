package com.verifone.cat.devices.csite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A loopback stand-in for OpenAM, the OAuth device grant and the CSITE API. Each route answers its replies in order, the
 * last one again once they run out. Every request is recorded. Nothing here reaches a real host.
 */
final class CSiteFake implements AutoCloseable {

    /** One scripted answer: status, Content-Type (null for none), body, and any extra headers. */
    record Reply(int status, String contentType, String body, Map<String, String> headers) {

        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, Map.of());
        }

        static Reply of(int status) {
            return new Reply(status, "application/json", "{}", Map.of());
        }
    }

    /** One request as it arrived: its method, path, lower-cased headers and body. */
    record Seen(String method, String path, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final Map<String, Deque<Reply>> routes = new ConcurrentHashMap<>();
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

    CSiteFake() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The stand-in's base URL: its loopback address and port. */
    String url() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    /** Queues {@code replies} for {@code method path}. */
    void route(String method, String path, Reply... replies) {
        routes.put(method + " " + path, new ArrayDeque<>(List.of(replies)));
    }

    /** The requests received, in order. */
    List<Seen> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    /** The requests received for {@code method path}. */
    List<Seen> seen(String method, String path) {
        return seen().stream().filter(s -> s.method().equals(method) && s.path().equals(path)).toList();
    }

    /** A loopback address with nothing listening: a connection to it is refused. */
    static String closedUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + socket.getLocalPort();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));
        String path = exchange.getRequestURI().getRawPath();
        seen.add(new Seen(exchange.getRequestMethod(), path, headers, body));

        Reply reply = next(exchange.getRequestMethod() + " " + path);
        if (reply.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        }
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        // A 204 or an empty reply carries no body; the JDK server refuses one.
        boolean bodiless = reply.status() == 204 || reply.status() == 304 || bytes.length == 0;
        exchange.sendResponseHeaders(reply.status(), bodiless ? -1 : bytes.length);
        if (!bodiless) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private synchronized Reply next(String key) {
        Deque<Reply> queue = routes.get(key);
        if (queue == null || queue.isEmpty()) {
            return Reply.json(404, "{}");
        }
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
