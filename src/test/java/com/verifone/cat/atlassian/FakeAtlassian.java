package com.verifone.cat.atlassian;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for Confluence and Xray on loopback, answering canned replies and recording what
 * it was asked. It never reaches the live site.
 *
 * <p>Evidence about this repository only: it shows the client sends the right requests and maps
 * the replies, not that Atlassian answers this way. Fixture bodies are saved copies of real
 * replies taken on 2026-09-09 and 2026-09-18 (see the test resources).
 */
final class FakeAtlassian implements AutoCloseable {

    record Reply(int status, String body) {}

    /** One request as the fake received it. {@code query} is the raw query string, or null. */
    record Seen(String method, String path, String query, String authorization, String body) {}

    private final HttpServer server;
    private final Map<String, Function<Seen, Reply>> routes = new ConcurrentHashMap<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();

    FakeAtlassian() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Seen request = new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body);
            seen.add(request);
            Function<Seen, Reply> route = routes.get(request.method() + " " + request.path());
            Reply reply = route == null
                    ? new Reply(404, "{\"error\":\"no such route\"}")
                    : route.apply(request);
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
    FakeAtlassian on(String method, String path, int status, String body) {
        routes.put(method + " " + path, request -> new Reply(status, body));
        return this;
    }

    /** Answers {@code method path} with a reply computed from the request. */
    FakeAtlassian onRequest(String method, String path, Function<Seen, Reply> reply) {
        routes.put(method + " " + path, reply);
        return this;
    }

    /** The base URL, {@code http://127.0.0.1:<port>}. Xray's API is under {@code /api/v2}. */
    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** A test resource under {@code atlassian/}, read as UTF-8. */
    static String fixture(String name) {
        try (InputStream in = FakeAtlassian.class.getResourceAsStream("/atlassian/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes {@code text} to a file in {@code dir} and returns the path. */
    static Path write(Path dir, String name, String text) {
        try {
            Path file = dir.resolve(name);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
