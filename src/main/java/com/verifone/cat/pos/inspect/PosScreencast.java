package com.verifone.cat.pos.inspect;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.core.Json;
import com.verifone.cat.pos.driver.PosDriver;

/**
 * The register's screen, live, and the operator's mouse and keys on it: what {@code edge://inspect}'s screencast
 * does, over the register's own DevTools endpoint (the rig's {@code cdp_port}, through the CDP relay).
 *
 * <p>It attaches to the page holding the POS UI frame, as {@link PosDriver} picks it, else the first page; sends a
 * screenshot at once so the screen shows before anything on it moves; then streams {@code Page.screencastFrame}
 * frames, each acknowledged after the viewer has taken it (so a slow viewer slows the stream rather than queueing
 * it). Input is {@code Input.dispatchMouseEvent} / {@code Input.dispatchKeyEvent}, in CSS pixels of the page's
 * viewport, as DevTools sends them.
 *
 * <p>Only reads the screen and sends input. It never navigates, reloads, emulates or closes the register's page;
 * {@link #close} stops this connection's screencast and disconnects.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Checked only against a local headless Chromium. Not known yet: the
 * register's pushed Chromium answering {@code Page.startScreencast} through the relay, and whether the POS acts on
 * a CDP mouse press as on a finger (the recorder's notes: a finger may arrive as {@code touchend} then a click).
 */
public final class PosScreencast implements AutoCloseable {

    /** JPEG quality of the frames; DevTools' own screencast uses 80. */
    static final int QUALITY = 80;

    /** How long {@link #close} waits for the page to stop the screencast. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(1);

    private static final Set<String> MOUSE_TYPES = Set.of("mousePressed", "mouseReleased", "mouseMoved");
    private static final Set<String> BUTTONS = Set.of("none", "left", "middle", "right");
    private static final Set<String> KEY_TYPES = Set.of("keyDown", "keyUp", "rawKeyDown", "char");

    /** One page the register's browser lists at {@code /json/list}. */
    public record Target(String id, String type, String title, String url, String parentId, URI webSocketUrl) {}

    /** One picture of the screen: a JPEG, and the page's viewport in CSS pixels that it shows. */
    public record Frame(String jpegBase64, double width, double height) {}

    /** Where frames go. Called on the socket's thread, one at a time. */
    public interface Viewer {
        void frame(Frame frame);

        /** The screencast ended: the page went away or the connection dropped. Called at most once. */
        void closed(String why);
    }

    private final CdpSocket cdp;
    private final Target target;
    private final OnceViewer viewer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PosScreencast(CdpSocket cdp, Target target, OnceViewer viewer) {
        this.cdp = cdp;
        this.target = target;
        this.viewer = viewer;
    }

    /**
     * Attaches to the register's POS page at {@code cdpUrl} ({@code http://host:port}) and starts the screencast.
     * The first frame (a screenshot) has been handed to {@code viewer} when this returns.
     *
     * @throws InspectError when the endpoint, a page or the screencast is not there
     */
    public static PosScreencast start(String cdpUrl, Duration timeout, Viewer viewer) {
        URI endpoint = endpoint(cdpUrl);
        Target picked = pick(targets(endpoint, timeout), timeout);
        OnceViewer once = new OnceViewer(viewer);
        Relay relay = new Relay(once);
        CdpSocket socket = CdpSocket.open(picked.webSocketUrl(), timeout, relay, once::closed);
        PosScreencast cast = new PosScreencast(socket, picked, once);
        relay.cast = cast;
        try {
            cast.begin(once, timeout);
        } catch (RuntimeException e) {
            cast.close();
            throw e;
        }
        return cast;
    }

    /** The page this screencast shows. */
    public Target target() {
        return target;
    }

    /** A mouse press, release or move at ({@code x}, {@code y}) in CSS pixels. Unknown types are ignored. */
    public void mouse(String type, double x, double y, String button, int buttons, int clickCount, int modifiers) {
        if (!MOUSE_TYPES.contains(type)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        params.put("button", BUTTONS.contains(button) ? button : "none");
        params.put("buttons", Math.max(0, buttons));
        params.put("clickCount", Math.max(0, clickCount));
        params.put("modifiers", Math.max(0, modifiers));
        input("Input.dispatchMouseEvent", params);
    }

    /** A wheel turn at ({@code x}, {@code y}): how the page scrolls. */
    public void wheel(double x, double y, double deltaX, double deltaY, int modifiers) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "mouseWheel");
        params.put("x", x);
        params.put("y", y);
        params.put("deltaX", deltaX);
        params.put("deltaY", deltaY);
        params.put("modifiers", Math.max(0, modifiers));
        input("Input.dispatchMouseEvent", params);
    }

    /**
     * A key event, as DevTools sends one: {@code keyDown} with {@code text} for a key that types, {@code rawKeyDown}
     * for one that does not, then {@code keyUp}. Unknown types are ignored.
     */
    public void key(String type, String key, String code, String text, int keyCode, int modifiers) {
        if (!KEY_TYPES.contains(type)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", type);
        params.put("modifiers", Math.max(0, modifiers));
        if (key != null && !key.isEmpty()) {
            params.put("key", key);
        }
        if (code != null && !code.isEmpty()) {
            params.put("code", code);
        }
        if (text != null && !text.isEmpty() && !"keyUp".equals(type) && !"rawKeyDown".equals(type)) {
            params.put("text", text);
            params.put("unmodifiedText", text);
        }
        if (keyCode > 0) {
            params.put("windowsVirtualKeyCode", keyCode);
            params.put("nativeVirtualKeyCode", keyCode);
        }
        input("Input.dispatchKeyEvent", params);
    }

    public boolean isOpen() {
        return !closed.get() && cdp.open();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            viewer.silence(); // closed by its owner, who needs no telling
            if (cdp.open()) {
                try {
                    cdp.call("Page.stopScreencast", Map.of(), STOP_WAIT);
                } catch (InspectError e) {
                    // closing anyway: the page ends a screencast whose socket goes
                }
            }
            cdp.close();
        }
    }

    private void input(String method, Map<String, Object> params) {
        if (!closed.get()) {
            cdp.send(method, params); // in order, after the ones before; a refused event changes nothing
        }
    }

    private void begin(Viewer viewer, Duration timeout) {
        cdp.call("Page.enable", Map.of(), timeout);
        double[] size = viewport(cdp.call("Page.getLayoutMetrics", Map.of(), timeout));
        JsonNode shot = cdp.call("Page.captureScreenshot", Map.of("format", "jpeg", "quality", QUALITY), timeout);
        String data = shot.path("data").asText("");
        if (!data.isEmpty() && size[0] > 0 && size[1] > 0) {
            viewer.frame(new Frame(data, size[0], size[1]));
        }
        cdp.call("Page.startScreencast", Map.of("format", "jpeg", "quality", QUALITY, "everyNthFrame", 1), timeout);
    }

    /** A screencast frame: to the viewer, then acknowledged, so the page sends the next. */
    private void frame(JsonNode params, Viewer viewer) {
        JsonNode meta = params.path("metadata");
        String data = params.path("data").asText("");
        if (!data.isEmpty()) {
            viewer.frame(new Frame(data, meta.path("deviceWidth").asDouble(), meta.path("deviceHeight").asDouble()));
        }
        if (!closed.get()) {
            cdp.send("Page.screencastFrameAck", Map.of("sessionId", params.path("sessionId").asInt()));
        }
    }

    /** The CSS viewport: {@code cssVisualViewport} where the browser has it, else the older {@code layoutViewport}. */
    static double[] viewport(JsonNode metrics) {
        JsonNode css = metrics.path("cssVisualViewport");
        if (css.has("clientWidth")) {
            return new double[] {css.path("clientWidth").asDouble(), css.path("clientHeight").asDouble()};
        }
        JsonNode layout = metrics.path("layoutViewport");
        return new double[] {layout.path("clientWidth").asDouble(), layout.path("clientHeight").asDouble()};
    }

    /** {@code http://host:port}, as {@link com.verifone.cat.pos.driver.RegisterEndpoint#cdpUrl} makes it. */
    static URI endpoint(String cdpUrl) {
        if (cdpUrl == null || cdpUrl.isBlank()) {
            throw new InspectError("no register: start CAT with --rig <name> to see its screen");
        }
        URI uri = URI.create(cdpUrl.strip());
        if (uri.getHost() == null || uri.getPort() <= 0) {
            throw new InspectError(cdpUrl + ": not an http://host:port DevTools endpoint");
        }
        return uri;
    }

    /**
     * The browser's targets, from {@code /json/list}. Each socket URL is pointed back at {@code endpoint}. Over HTTP/1.1,
     * as {@code BrowserOverride}'s probe: the JDK's default asks to upgrade to HTTP/2, and a register's stock browser
     * (Chrome 67, Reg101 on 2026-11-10) answers that request with 404 while it answers HTTP/1.1 with the list.
     */
    static List<Target> targets(URI endpoint, Duration timeout) {
        URI list = endpoint.resolve("/json/list");
        HttpResponse<String> reply;
        try {
            reply = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build().send(
                    HttpRequest.newBuilder(list).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new InspectError(endpoint + ": no DevTools endpoint answering (" + CdpSocket.firstLine(e)
                    + "). Is the register up, and the browser override with its CDP relay provisioned?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InspectError(endpoint + ": interrupted");
        }
        if (reply.statusCode() != 200) {
            throw new InspectError(list + ": HTTP " + reply.statusCode());
        }
        return parseTargets(reply.body(), endpoint);
    }

    static List<Target> parseTargets(String body, URI endpoint) {
        JsonNode array;
        try {
            array = Json.MAPPER.readTree(body);
        } catch (IOException e) {
            throw new InspectError(endpoint + "/json/list: not JSON");
        }
        List<Target> out = new ArrayList<>();
        for (JsonNode t : array) {
            String ws = t.path("webSocketDebuggerUrl").asText("");
            out.add(new Target(t.path("id").asText(""), t.path("type").asText(""), t.path("title").asText(""),
                    t.path("url").asText(""), t.path("parentId").asText(""), ws.isEmpty() ? null : socketUrl(ws, endpoint)));
        }
        return out;
    }

    /**
     * The page's socket at the address CAT reached, not the one the browser names: behind the register's relay the
     * browser may name its own loopback port, which is not reachable from the controller.
     */
    static URI socketUrl(String webSocketDebuggerUrl, URI endpoint) {
        URI named = URI.create(webSocketDebuggerUrl);
        return URI.create("ws://" + endpoint.getHost() + ":" + endpoint.getPort() + named.getRawPath());
    }

    /** The pages that can be shown: type {@code page} with a socket. */
    static List<Target> pages(List<Target> targets) {
        List<Target> out = new ArrayList<>();
        for (Target t : targets) {
            if ("page".equals(t.type()) && t.webSocketUrl() != null) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The page to show: the one holding the POS UI frame, else the first page. With one page there is nothing to
     * choose; with more, an out-of-process POS UI frame names its parent, else each page's frame tree is read.
     */
    static Target pick(List<Target> all, Duration timeout) {
        List<Target> pages = pages(all);
        if (pages.isEmpty()) {
            throw new InspectError("the register's browser lists no page");
        }
        if (pages.size() == 1) {
            return pages.get(0);
        }
        for (Target t : all) {
            if ("iframe".equals(t.type()) && t.url().contains(PosDriver.POS_UI_FRAME)) {
                for (Target page : pages) {
                    if (page.id().equals(t.parentId())) {
                        return page;
                    }
                }
            }
        }
        for (Target page : pages) {
            if (holdsPosUi(page, timeout)) {
                return page;
            }
        }
        return pages.get(0);
    }

    /** Whether the page's frame tree holds the POS UI frame, read over a socket of its own. */
    private static boolean holdsPosUi(Target page, Duration timeout) {
        try (CdpSocket probe = CdpSocket.open(page.webSocketUrl(), timeout, (method, params) -> { }, why -> { })) {
            return anyFrameUrl(probe.call("Page.getFrameTree", Map.of(), timeout).path("frameTree"));
        } catch (InspectError e) {
            return false;
        }
    }

    static boolean anyFrameUrl(JsonNode tree) {
        if (tree.path("frame").path("url").asText("").contains(PosDriver.POS_UI_FRAME)) {
            return true;
        }
        for (JsonNode child : tree.path("childFrames")) {
            if (anyFrameUrl(child)) {
                return true;
            }
        }
        return false;
    }

    /** Routes a socket's events to the screencast once it exists. */
    private static final class Relay implements java.util.function.BiConsumer<String, JsonNode> {
        private final Viewer viewer;
        private volatile PosScreencast cast;

        Relay(Viewer viewer) {
            this.viewer = viewer;
        }

        @Override
        public void accept(String method, JsonNode params) {
            PosScreencast current = cast;
            if (current == null) {
                return;
            }
            if ("Page.screencastFrame".equals(method)) {
                current.frame(params, viewer);
            } else if ("Inspector.detached".equals(method)) {
                viewer.closed("DevTools detached the page: " + params.path("reason").asText("no reason given"));
            }
        }
    }

    /** {@link Viewer#closed} once, whichever socket or event says so first. */
    private static final class OnceViewer implements Viewer {
        private final Viewer inner;
        private final AtomicBoolean done = new AtomicBoolean();

        OnceViewer(Viewer inner) {
            this.inner = inner;
        }

        @Override
        public void frame(Frame frame) {
            if (!done.get()) {
                inner.frame(frame);
            }
        }

        @Override
        public void closed(String why) {
            if (done.compareAndSet(false, true)) {
                inner.closed(why);
            }
        }

        void silence() {
            done.set(true);
        }
    }
}
