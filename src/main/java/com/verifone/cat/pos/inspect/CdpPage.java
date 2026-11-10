package com.verifone.cat.pos.inspect;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.core.Json;
import com.verifone.cat.core.Waits;
import com.verifone.cat.pos.Locator;

/**
 * One register page over raw CDP: whether a {@link Locator}'s element is showing, and a tap on it. For a browser
 * Playwright cannot drive: the register's stock browser (Chrome 67, Reg101 on 2026-11-10, no browser override), whose
 * DevTools {@link PosScreencast} already reads. No Python equivalent (the Python's Chrome-67 shim was retired).
 *
 * <p>An element is found in the page with {@code document.getElementById} (an ID locator, each literal in turn) or
 * {@code document.evaluate} (an XPath one), and is showing when it has a box and is not hidden. A tap is a left mouse
 * press and release at the element's centre ({@code Input.dispatchMouseEvent}), as the POS page's screen sends a click:
 * a real input event, so the page's own click handlers run. A query made while the page navigates (no execution
 * context) is "not showing", never a fault.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): the queries were checked read-only on Reg101's pairing page (its
 * {@code BUTTON#vfi_pairdevice_lbl}, a plain {@code onclick}); a tap has not been sent to a register yet.
 */
public final class CdpPage implements AutoCloseable {

    /** How often {@link #tap} and {@link #expectVisible} look again. */
    static final Duration POLL = Duration.ofMillis(250);

    private final CdpSocket cdp;
    private final PosScreencast.Target target;
    private final Duration callTimeout;

    private CdpPage(CdpSocket cdp, PosScreencast.Target target, Duration callTimeout) {
        this.cdp = cdp;
        this.target = target;
        this.callTimeout = callTimeout;
    }

    /** The register's page at {@code cdpUrl} ({@code http://host:port}): its POS page, else its first page. */
    public static CdpPage open(String cdpUrl, Duration timeout) {
        URI endpoint = PosScreencast.endpoint(cdpUrl);
        PosScreencast.Target picked = PosScreencast.pick(PosScreencast.targets(endpoint, timeout), timeout);
        CdpSocket socket = CdpSocket.open(picked.webSocketUrl(), timeout, (method, params) -> { }, why -> { });
        return new CdpPage(socket, picked, timeout);
    }

    /**
     * The first of {@code ports} on {@code host} that serves DevTools' target list (over HTTP/1.1, as
     * {@link PosScreencast#targets} asks), as {@code http://host:port}; empty when none does.
     */
    public static Optional<String> devToolsAt(String host, List<Integer> ports, Duration timeout) {
        for (int port : ports) {
            String url = "http://" + host + ":" + port;
            try {
                PosScreencast.targets(PosScreencast.endpoint(url), timeout);
                return Optional.of(url);
            } catch (InspectError e) {
                // Not DevTools on this port: the next.
            }
        }
        return Optional.empty();
    }

    /** The page this handle reads. */
    public PosScreencast.Target target() {
        return target;
    }

    /** Whether the socket is still up: false once the register's browser went away (a reboot). */
    public boolean connected() {
        return cdp.open();
    }

    /** Whether {@code locator}'s element is in the page and showing now. */
    public boolean isShowing(Locator locator) {
        return box(locator).isPresent();
    }

    /** Waits up to {@code timeout} for {@code locator}'s element to show. */
    public void expectVisible(Locator locator, Duration timeout) {
        if (Waits.until(() -> box(locator), timeout, POLL).isEmpty()) {
            throw new InspectError(locator.name() + " did not show within " + timeout.toSeconds() + " s");
        }
    }

    /** Waits up to {@code timeout} for {@code locator}'s element to show, then taps its centre. */
    public void tap(Locator locator, Duration timeout) {
        double[] centre = Waits.until(() -> box(locator), timeout, POLL).orElseThrow(
                () -> new InspectError(locator.name() + " did not show within " + timeout.toSeconds()
                        + " s, so it was not tapped"));
        for (String type : List.of("mousePressed", "mouseReleased")) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", type);
            event.put("x", centre[0]);
            event.put("y", centre[1]);
            event.put("button", "left");
            event.put("clickCount", 1);
            cdp.call("Input.dispatchMouseEvent", event, callTimeout);
        }
    }

    /** The element's centre, when it is in the page and showing; empty otherwise, or while the page navigates. */
    Optional<double[]> box(Locator locator) {
        JsonNode result;
        try {
            result = cdp.call("Runtime.evaluate", Map.of("expression", finder(locator), "returnByValue", true),
                    callTimeout);
        } catch (InspectError e) {
            if (!cdp.open()) {
                throw e; // the browser went away: not "not yet"
            }
            return Optional.empty(); // no execution context mid-navigation
        }
        JsonNode value = result.path("result").path("value");
        if (result.has("exceptionDetails") || !value.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new double[] {value.path("x").asDouble(), value.path("y").asDouble()});
    }

    /**
     * The page-side lookup: the first of the locator's literals that names a showing element, as {@code {x, y}} (its
     * centre in CSS pixels), else null.
     */
    static String finder(Locator locator) {
        String literals;
        try {
            literals = Json.MAPPER.writeValueAsString(locator.literals());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        boolean xpath = locator.kind() == Locator.Kind.XPATH;
        return "(function(literals){"
                + "for (var i = 0; i < literals.length; i++) {"
                + "var el = " + (xpath
                        ? "document.evaluate(literals[i], document, null, 9, null).singleNodeValue;"
                        : "document.getElementById(literals[i]);")
                + "if (!el || !el.getBoundingClientRect) continue;"
                + "var r = el.getBoundingClientRect();"
                + "var s = window.getComputedStyle(el);"
                + "if (r.width <= 0 || r.height <= 0 || s.visibility === 'hidden' || s.display === 'none') continue;"
                + "return {x: r.left + r.width / 2, y: r.top + r.height / 2};"
                + "}"
                + "return null;"
                + "})(" + literals + ")";
    }

    @Override
    public void close() {
        cdp.close();
    }
}
