package com.verifone.cat.posshim;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for pos-shim on loopback, answering canned replies.
 *
 * <p>Evidence about this repository only: it shows the client parses the shim's
 * replies and maps its errors, not that any register answers this way. The canned
 * 200 bodies are real lab-shim replies captured on 2026-10-24.
 */
public final class FakeShim implements AutoCloseable {

    public record Reply(int status, String body) {}

    public record Seen(String method, String pathAndQuery, String body) {}

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    public final List<Seen> seen = new CopyOnWriteArrayList<>();

    public FakeShim() {
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
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            seen.add(new Seen(exchange.getRequestMethod(), query == null ? path : path + "?" + query, body));
            Reply reply = replies.getOrDefault(exchange.getRequestMethod() + " " + path,
                    new Reply(404, "{\"error\":\"not-found\",\"detail\":\"no such route\"}"));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public FakeShim on(String method, String path, int status, String body) {
        replies.put(method + " " + path, new Reply(status, body));
        return this;
    }

    public FakeShim onFixture(String method, String path, String fixture) {
        return on(method, path, 200, fixture(fixture));
    }

    public String host() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public static String fixture(String name) {
        try (InputStream in = FakeShim.class.getResourceAsStream("/shim/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
