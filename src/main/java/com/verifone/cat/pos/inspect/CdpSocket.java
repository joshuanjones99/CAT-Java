package com.verifone.cat.pos.inspect;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.core.Json;

/**
 * One raw Chrome DevTools Protocol connection to one page target, over the JDK's own {@link WebSocket}: commands
 * with ids and their replies, and the events the page sends. No Playwright: Playwright for Java delivers events only
 * while one of its calls runs on its thread, and a screencast must take input while the page sends nothing.
 *
 * <p>Thread-safe. Sends are chained, because the JDK socket refuses a send while another is outstanding. Events are
 * delivered on the socket's listener thread, one at a time, in order.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class CdpSocket implements AutoCloseable {

    /** How long a close waits for its close frame to go before dropping the connection. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final WebSocket socket;
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Object sendLock = new Object();
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);

    private CdpSocket(WebSocket socket) {
        this.socket = socket;
    }

    /**
     * Opens the page's debugger socket. {@code onEvent} gets each event's method and params; {@code onClose} the
     * reason once, when the page or the connection goes away.
     */
    static CdpSocket open(URI webSocketUrl, Duration timeout, BiConsumer<String, JsonNode> onEvent,
            Consumer<String> onClose) {
        Reader reader = new Reader(onEvent, onClose);
        CompletableFuture<WebSocket> opening = HttpClient.newBuilder().connectTimeout(timeout).build()
                .newWebSocketBuilder().connectTimeout(timeout).buildAsync(webSocketUrl, reader);
        WebSocket socket;
        try {
            socket = opening.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            opening.cancel(true);
            throw new InspectError(webSocketUrl + ": no DevTools socket within " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            throw new InspectError(webSocketUrl + ": DevTools socket refused (" + firstLine(e.getCause()) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InspectError(webSocketUrl + ": interrupted while opening the DevTools socket");
        }
        CdpSocket cdp = new CdpSocket(socket);
        reader.owner = cdp;
        return cdp;
    }

    /** Sends one command; the future completes with its {@code result}, or fails with the page's error. */
    CompletableFuture<JsonNode> send(String method, Map<String, ?> params) {
        int id = ids.incrementAndGet();
        ObjectNode message = Json.MAPPER.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? Json.MAPPER.createObjectNode() : Json.MAPPER.valueToTree(params));
        CompletableFuture<JsonNode> reply = new CompletableFuture<>();
        pending.put(id, reply);
        String text;
        try {
            text = Json.MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            pending.remove(id);
            reply.completeExceptionally(e);
            return reply;
        }
        synchronized (sendLock) {
            lastSend = lastSend.handle((ok, failed) -> null)
                    .thenCompose(ignored -> socket.sendText(text, true))
                    .whenComplete((ok, failed) -> {
                        if (failed != null) {
                            fail(id, "send failed: " + firstLine(failed));
                        }
                    });
        }
        return reply;
    }

    /** {@link #send}, waited on for at most {@code timeout}. */
    JsonNode call(String method, Map<String, ?> params, Duration timeout) {
        try {
            return send(method, params).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new InspectError(method + ": no reply within " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            throw new InspectError(method + ": " + firstLine(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InspectError(method + ": interrupted");
        }
    }

    boolean open() {
        return !socket.isInputClosed() && !socket.isOutputClosed();
    }

    @Override
    public void close() {
        failAll("connection closed");
        if (socket.isOutputClosed()) {
            socket.abort();
            return;
        }
        synchronized (sendLock) {
            lastSend = lastSend.handle((ok, failed) -> null)
                    .thenCompose(ignored -> socket.sendClose(WebSocket.NORMAL_CLOSURE, ""))
                    .orTimeout(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((ok, failed) -> socket.abort());
        }
    }

    private void fail(int id, String why) {
        CompletableFuture<JsonNode> reply = pending.remove(id);
        if (reply != null) {
            reply.completeExceptionally(new InspectError(why));
        }
    }

    private void failAll(String why) {
        for (Integer id : pending.keySet()) {
            fail(id, why);
        }
    }

    private void received(String text, BiConsumer<String, JsonNode> onEvent) {
        JsonNode message;
        try {
            message = Json.MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            return; // not CDP: nothing to answer
        }
        if (message.has("id")) {
            CompletableFuture<JsonNode> reply = pending.remove(message.get("id").asInt());
            if (reply == null) {
                return;
            }
            JsonNode error = message.get("error");
            if (error != null) {
                reply.completeExceptionally(new InspectError(error.path("message").asText("CDP error")));
            } else {
                reply.complete(message.path("result"));
            }
        } else if (message.has("method")) {
            onEvent.accept(message.get("method").asText(), message.path("params"));
        }
    }

    static String firstLine(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String text = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** Joins a message's parts (a frame is often several) and hands each whole message on. */
    private static final class Reader implements WebSocket.Listener {
        private final BiConsumer<String, JsonNode> onEvent;
        private final Consumer<String> onClose;
        private final StringBuilder parts = new StringBuilder();
        private volatile CdpSocket owner;
        private boolean closed;

        Reader(BiConsumer<String, JsonNode> onEvent, Consumer<String> onClose) {
            this.onEvent = onEvent;
            this.onClose = onClose;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            parts.append(data);
            if (last) {
                String text = parts.toString();
                parts.setLength(0);
                CdpSocket cdp = owner;
                if (cdp != null) {
                    cdp.received(text, onEvent);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closing("the page closed its DevTools socket" + (reason == null || reason.isEmpty() ? "" : ": " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closing("DevTools socket failed: " + firstLine(error));
        }

        private synchronized void closing(String why) {
            if (closed) {
                return;
            }
            closed = true;
            CdpSocket cdp = owner;
            if (cdp != null) {
                cdp.failAll(why);
            }
            onClose.accept(why);
        }
    }
}
