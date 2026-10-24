package com.verifone.cat.pats.runner.handlers.posui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verifone.cat.posshim.PosShimClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pos-shim stand-in on the loopback address: it answers the screen, the supported keys and the key presses the
 * {@link PosShimClient} asks for, and records every key it is sent. It is the register's shim, not the register:
 * the screen is whatever the test sets.
 *
 * <p>Evidence about this repository only.
 */
final class PosUiFakeShim implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The keys the fake shim names as buildable. */
    static final List<String> NAMED_KEYS = List.of(
            "ENTER", "EXIT", "LOG_OUT", "KEY_SCO_CASHIER_ENTRY", "VOID_TICKET");

    private final HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private volatile Map<String, Object> overlay;
    private volatile List<String> review = List.of();

    PosUiFakeShim() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The client the family reaches the shim through. */
    PosShimClient client() {
        InetSocketAddress bound = server.getAddress();
        return new PosShimClient(bound.getAddress().getHostAddress(), bound.getPort(), "test", Duration.ofSeconds(5));
    }

    /** Sets the overlay the shim reports: its title and lines, or none. */
    void overlay(String title, String line1, String line2, String line3) {
        overlay = Map.of("title", title, "line1", line1, "line2", line2, "line3", line3, "ok_only", true,
                "cancel_only", false, "ok_and_cancel", false, "edit_box", false, "auto_hide", false);
    }

    /** Sets the review menu the shim reports. */
    void review(List<String> items) {
        review = List.copyOf(items);
    }

    /** Every key or payload the shim was sent, in order: {@code keys:NAME} or {@code enter:DATA}. */
    synchronized List<String> calls() {
        return List.copyOf(calls);
    }

    private synchronized void record(String call) {
        calls.add(call);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String reply;
        if ("GET".equals(method) && "/pos/screen".equals(path)) {
            reply = JSON.writeValueAsString(screen());
        } else if ("GET".equals(method) && "/pos/keys".equals(path)) {
            reply = JSON.writeValueAsString(Map.of("named_keys", NAMED_KEYS, "rejected_names", List.of(),
                    "notes", List.of()));
        } else if ("POST".equals(method) && "/pos/keys".equals(path)) {
            Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            List<?> keys = (List<?>) body.get("keys");
            for (Object key : keys) {
                record("keys:" + key);
            }
            reply = JSON.writeValueAsString(Map.of("client", "test", "requested", keys, "pressed", List.of(),
                    "all_processed", true));
        } else if ("POST".equals(method) && "/pos/keys/enter".equals(path)) {
            Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            record("enter:" + body.get("data"));
            reply = JSON.writeValueAsString(Map.of("key", "ENTER", "processed", true));
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Map<String, Object> screen() {
        Map<String, Object> screen = new java.util.LinkedHashMap<>();
        screen.put("client", "test");
        screen.put("state_present", true);
        screen.put("idle", null);
        screen.put("idle_basis", null);
        screen.put("overlay_present", overlay != null);
        screen.put("transaction_active", null);
        screen.put("overlay", overlay);
        screen.put("overlay_review", review);
        screen.put("express_panel", List.of());
        screen.put("totals", Map.of());
        screen.put("last_change_age_s", null);
        return screen;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
