package com.verifone.cat.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.core.Json;
import com.verifone.cat.pos.inspect.InspectError;
import com.verifone.cat.pos.inspect.PosScreencast;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.websocket.WsContext;

/**
 * The POS page's socket: one {@link PosScreencast} of the register per open page. Frames go to the page as
 * {@code {"type":"frame","w":..,"h":..,"data":"<jpeg base64>"}}; the page sends back {@code mouse}, {@code wheel}
 * and {@code key} messages, in CSS pixels of the register's page, which go to the register as DevTools input.
 *
 * <p>Other messages to the page: {@code attached} (the register page's title and URL), {@code error} (could not
 * attach: the page says why and tries again) and {@code closed} (the register's page went away).
 *
 * <p>A WebSocket is not covered by the browser's same-origin rule, so the upgrade is refused unless its
 * {@code Origin} is this server's own: another site open in the operator's browser cannot reach the register
 * through CAT. (A request with no {@code Origin} is not from a browser page.)
 */
final class PosScreenRoutes {

    static final String PATH = "/pos/screen";

    /** How long attaching to the register (its target list, its socket, the first frame) may take. */
    static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(8);

    /** Server pings keep a socket open while the register's screen does not change. */
    private static final long PING_SECONDS = 15;

    private PosScreenRoutes() {}

    /** {@code cdpUrl}: the register's DevTools endpoint, or null when CAT has no register. */
    static void register(Javalin app, String cdpUrl) {
        Map<String, PosScreencast> casts = new ConcurrentHashMap<>();
        app.wsBeforeUpgrade(PATH, PosScreenRoutes::sameOrigin);
        app.ws(PATH, ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings(PING_SECONDS, TimeUnit.SECONDS);
                Thread attach = new Thread(() -> attach(ctx, cdpUrl, casts), "cat-pos-screen-attach");
                attach.setDaemon(true);
                attach.start();
            });
            ws.onMessage(ctx -> {
                PosScreencast cast = casts.get(ctx.sessionId());
                if (cast != null) {
                    input(cast, ctx.message());
                }
            });
            ws.onClose(ctx -> stop(casts.remove(ctx.sessionId())));
            ws.onError(ctx -> stop(casts.remove(ctx.sessionId())));
        });
    }

    static void sameOrigin(Context ctx) {
        String origin = ctx.header("Origin");
        if (origin == null) {
            return;
        }
        String host = ctx.header("Host");
        if (host == null || !origin.equalsIgnoreCase("http://" + host)) {
            throw new ForbiddenResponse("The POS screen opens from the CAT-Java page only.");
        }
    }

    private static void attach(WsContext ctx, String cdpUrl, Map<String, PosScreencast> casts) {
        PosScreencast cast;
        try {
            cast = PosScreencast.start(cdpUrl, ATTACH_TIMEOUT, new SocketViewer(ctx));
        } catch (InspectError e) {
            send(ctx, Map.of("type", "error", "text", e.getMessage()));
            if (ctx.session.isOpen()) {
                ctx.closeSession(); // the page tries again
            }
            return;
        }
        if (!ctx.session.isOpen()) {
            cast.close(); // the page left while this attached
            return;
        }
        casts.put(ctx.sessionId(), cast);
        Map<String, Object> attached = new LinkedHashMap<>();
        attached.put("type", "attached");
        attached.put("title", cast.target().title());
        attached.put("url", cast.target().url());
        send(ctx, attached);
    }

    private static void stop(PosScreencast cast) {
        if (cast != null) {
            cast.close();
        }
    }

    /** One message from the page, to the register. Anything malformed is dropped. */
    static void input(PosScreencast cast, String message) {
        JsonNode m;
        try {
            m = Json.MAPPER.readTree(message);
        } catch (JsonProcessingException e) {
            return;
        }
        int modifiers = m.path("modifiers").asInt(0);
        switch (m.path("t").asText("")) {
            case "mouse" -> cast.mouse(m.path("type").asText(""), m.path("x").asDouble(), m.path("y").asDouble(),
                    m.path("button").asText("none"), m.path("buttons").asInt(0), m.path("clickCount").asInt(0),
                    modifiers);
            case "wheel" -> cast.wheel(m.path("x").asDouble(), m.path("y").asDouble(), m.path("deltaX").asDouble(),
                    m.path("deltaY").asDouble(), modifiers);
            case "key" -> cast.key(m.path("type").asText(""), m.path("key").asText(""), m.path("code").asText(""),
                    m.path("text").asText(""), m.path("keyCode").asInt(0), modifiers);
            default -> { } // the page's keep-alive, or nothing CAT knows
        }
    }

    /**
     * Sends one message, if the page is still there. Jetty refuses a second blocking send while one is under way,
     * so sends to one page are taken one at a time.
     */
    private static void send(WsContext ctx, Map<String, ?> message) {
        synchronized (ctx.session) {
            if (!ctx.session.isOpen()) {
                return;
            }
            try {
                ctx.send(Json.MAPPER.writeValueAsString(message));
            } catch (JsonProcessingException | RuntimeException e) {
                // the page went while this was sent: its close stops the screencast
            }
        }
    }

    /** The register's frames, to one page. */
    private static final class SocketViewer implements PosScreencast.Viewer {
        private final WsContext ctx;

        SocketViewer(WsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void frame(PosScreencast.Frame frame) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "frame");
            message.put("w", frame.width());
            message.put("h", frame.height());
            message.put("data", frame.jpegBase64());
            send(ctx, message);
        }

        @Override
        public void closed(String why) {
            send(ctx, Map.of("type", "closed", "text", why));
            if (ctx.session.isOpen()) {
                ctx.closeSession();
            }
        }
    }
}
