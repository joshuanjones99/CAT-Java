package com.verifone.cat.authoring;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A stand-in for Jira and Confluence on loopback, answering canned replies and recording what it was asked. A shared copy
 * of the atlassian package's fake, kept here because that one is package-private. It never reaches the live site.
 *
 * <p>Evidence about this repository only: it shows the client sends the right requests and maps the replies.
 */
final class FakeSite implements AutoCloseable {

    record Reply(int status, String body) {}

    /** One request as the fake received it. */
    record Seen(String method, String path, String query, String authorization) {}

    private final HttpServer server;
    private final Map<String, Function<Seen, Reply>> routes = new ConcurrentHashMap<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();

    FakeSite() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            Seen request = new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            seen.add(request);
            Function<Seen, Reply> route = routes.get(request.method() + " " + request.path());
            Reply reply = route == null ? new Reply(404, "{\"error\":\"no such route\"}") : route.apply(request);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** Answers {@code method path} with a constant reply. */
    FakeSite on(String method, String path, int status, String body) {
        routes.put(method + " " + path, request -> new Reply(status, body));
        return this;
    }

    /** The base URL on loopback. */
    URI base() {
        return URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
