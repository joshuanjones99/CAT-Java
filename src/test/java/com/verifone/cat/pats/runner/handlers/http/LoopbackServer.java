package com.verifone.cat.pats.runner.handlers.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in HTTP server on the loopback address, for the HTTP steps' tests. It records every request and answers
 * each with what its {@link Handler} returns. This is evidence about the port's wire behaviour, not about a Commander
 * or a cloud.
 */
public final class LoopbackServer implements AutoCloseable {

    /** One request as the server read it. {@code query} is the URL's query, decoded; {@code body} is UTF-8. */
    public record Seen(String method, String path, Map<String, String> query, Map<String, String> headers,
            String body) {
    }

    /** One answer: a status, a Content-Type (null for none), and a UTF-8 body. */
    public record Answer(int status, String contentType, String body) {
    }

    /** Answers one request. */
    @FunctionalInterface
    public interface Handler {
        Answer answer(Seen seen);
    }

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Handler handler;

    public LoopbackServer(Handler handler) {
        this.handler = handler;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind the loopback address", e);
        }
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            Seen request = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    parseQuery(exchange.getRequestURI().getRawQuery()), headers, body);
            seen.add(request);
            Answer answer = this.handler.answer(request);
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            if (answer.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", answer.contentType());
            }
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    /** The host and port a PATS table's Commander address takes, as {@code PdkShim.onPort} wants them. */
    public String host() {
        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Every request so far, in arrival order. */
    public List<Seen> seen() {
        return List.copyOf(seen);
    }

    /** Replaces the answers from now on. */
    public void answer(Handler next) {
        this.handler = next;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** {@code a=1&b=2} as a map, values decoded; a key with no value maps to "". */
    static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            out.put(key, kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return out;
    }
}
