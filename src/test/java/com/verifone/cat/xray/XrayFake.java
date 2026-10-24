package com.verifone.cat.xray;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Xray's import endpoint and Jira's issue endpoints on loopback, recording every request. It never
 * reaches the live site. Its replies follow the Python CAT's tests: the same routes, statuses and
 * shapes, so the port is checked against what the Python was checked against.
 *
 * <p>Failure is injected by count: {@link #failImports} answers 503 that many times,
 * {@link #rateLimitImports} answers 429 with {@code Retry-After: 0}, and {@link #expireNextImport}
 * answers 401 once, so the client has to fetch a fresh token.
 */
final class XrayFake implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One request as the fake received it. */
    record Seen(String method, String path, String authorization, String body) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    final Set<String> rejectStatuses = ConcurrentHashMap.newKeySet();
    final AtomicInteger failImports = new AtomicInteger();
    final AtomicInteger rateLimitImports = new AtomicInteger();
    final AtomicBoolean expireNextImport = new AtomicBoolean();
    final AtomicInteger authenticates = new AtomicInteger();
    final AtomicInteger executions = new AtomicInteger();
    /** Xray refuses to authenticate, echoing the secret back as a badly-written site might. */
    final AtomicBoolean refuseAuthenticate = new AtomicBoolean();
    /** Jira serves every issue call as anonymous: 404 "does not exist", as the real site does. */
    final AtomicBoolean hideIssues = new AtomicBoolean();
    /** Jira rejects the Basic credentials on {@code /myself}. */
    final AtomicBoolean rejectMyself = new AtomicBoolean();

    private final HttpServer server;

    XrayFake() {
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
            seen.add(new Seen(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            Reply reply = route(exchange.getRequestMethod(), path, exchange.getRequestHeaders().getFirst("Authorization"), body);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
            }
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                exchange.close();
            }
        });
        server.start();
    }

    private record Reply(int status, String body, String retryAfter) {}

    private Reply route(String method, String path, String authorization, String body) {
        if (path.endsWith("/authenticate")) {
            if (refuseAuthenticate.get()) {
                return new Reply(401, "bad credentials for id=id secret=secret", null);
            }
            return new Reply(200, "\"bearer-" + authenticates.incrementAndGet() + "\"", null);
        }
        if (hideIssues.get() && path.contains("/rest/api/3/issue/")) {
            return new Reply(404, "{\"errorMessages\":[\"Issue does not exist or you do not have permission\"]}", null);
        }
        if (path.endsWith("/rest/api/3/myself") && rejectMyself.get()) {
            return new Reply(401, "{\"errorMessages\":[\"Client must be authenticated\"]}", null);
        }
        if (path.endsWith("/import/execution")) {
            if (authorization == null || !authorization.startsWith("Bearer bearer-")) {
                return new Reply(401, "{\"error\":\"no token\"}", null);
            }
            if (expireNextImport.getAndSet(false)) {
                return new Reply(401, "{\"error\":\"expired\"}", null);
            }
            if (rateLimitImports.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return new Reply(429, "slow down", "0");
            }
            if (failImports.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                return new Reply(503, "busy", null);
            }
            JsonNode parsed = parse(body);
            for (JsonNode test : parsed.path("tests")) {
                if (rejectStatuses.contains(test.path("status").asText())) {
                    return new Reply(400, "{\"error\":\"invalid status\"}", null);
                }
            }
            if (parsed.has("testExecutionKey")) {
                return new Reply(200, "{\"id\":\"1\",\"key\":\"" + parsed.get("testExecutionKey").asText() + "\"}", null);
            }
            return new Reply(200, "{\"id\":\"1\",\"key\":\"PETROCORE-" + (900000 + executions.incrementAndGet()) + "\"}", null);
        }
        if (path.endsWith("/transitions")) {
            if ("GET".equals(method)) {
                return new Reply(200, "{\"transitions\":[{\"id\":\"11\",\"name\":\"Start\"},"
                        + "{\"id\":\"31\",\"name\":\"Close\"}]}", null);
            }
            return new Reply(204, "", null);
        }
        if (path.endsWith("/comment")) {
            return new Reply(201, "{\"id\":\"5\"}", null);
        }
        if (path.endsWith("/rest/api/3/myself")) {
            return new Reply(200, "{\"accountId\":\"712020:abc\",\"displayName\":\"Tester T\"}", null);
        }
        if (path.contains("/rest/api/3/issue/") && "PUT".equals(method)) {
            return new Reply(204, "", null);
        }
        return new Reply(404, "{\"errorMessages\":[\"no route\"]}", null);
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The base URL, {@code http://127.0.0.1:<port>}. Xray's API is under {@code /api/v2}. */
    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The bodies of every import call, in order. */
    List<JsonNode> imports() {
        List<JsonNode> out = new ArrayList<>();
        for (Seen request : seen) {
            if (request.path().endsWith("/import/execution")) {
                out.add(parse(request.body()));
            }
        }
        return out;
    }

    /** Every status pushed for {@code key}, in order. */
    List<String> statuses(String key) {
        List<String> out = new ArrayList<>();
        for (JsonNode batch : imports()) {
            for (JsonNode test : batch.path("tests")) {
                if (key.equals(test.path("testKey").asText())) {
                    out.add(test.path("status").asText());
                }
            }
        }
        return out;
    }

    /** Requests whose path ends with {@code suffix} and whose method is {@code method}. */
    List<Seen> requests(String method, String suffix) {
        List<Seen> out = new ArrayList<>();
        for (Seen request : seen) {
            if (request.method().equals(method) && request.path().endsWith(suffix)) {
                out.add(request);
            }
        }
        return out;
    }

    /** Whether any request's body contains {@code text}. */
    boolean sawText(String text) {
        return seen.stream().anyMatch(r -> r.body() != null && r.body().contains(text));
    }

    /** A map literal, for building expected bodies. */
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
