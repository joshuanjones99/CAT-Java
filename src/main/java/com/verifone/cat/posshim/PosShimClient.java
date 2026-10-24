package com.verifone.cat.posshim;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.posshim.ShimReplies.Health;
import com.verifone.cat.posshim.ShimReplies.KeyPressResult;
import com.verifone.cat.posshim.ShimReplies.Screen;
import com.verifone.cat.posshim.ShimReplies.SupportedKeys;

/**
 * The register's pos-shim over HTTP: the Java counterpart of the Python CAT's
 * {@code petrotest.devices.pos_shim.PosShimClient}.
 *
 * <p>pos-shim runs on the register beside the POS. The POS pushes its screen state into
 * it over RMI, and the shim serves that state as JSON on port 8801 (the Python CAT's
 * {@code bridge/pos-shim/README.md}). So nothing here speaks RMI or loads a Topaz class:
 * the Topaz RMI stack stays on the register, on its own Java 17.
 *
 * <p>Read path: exercised against the lab register on 2026-10-24 (see README.md).
 * {@link #pressKeys}: UNVERIFIED AGAINST HARDWARE from this client.
 */
public final class PosShimClient {

    /** The shim's HTTP port; the Python CAT's {@code DEFAULT_SHIM_PORT}. */
    public static final int DEFAULT_SHIM_PORT = 8801;

    /** The longest the POS may take to process one pressed key: the shim's {@code sendToken}, 75 x 200 ms. */
    static final Duration KEY_PROCESSING = Duration.ofSeconds(15);

    /** 503 codes that mean the shim is up and the POS is not talking to it. */
    private static final Set<String> RIG_FAULT_CODES = Set.of("no-clients", "no-state", "client-not-drivable");

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final URI base;
    private final String registerHost;
    private final String clientHost;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param registerHost the register the shim runs on, from the rig file, never a literal
     * @param shimPort     the shim's port, normally {@link #DEFAULT_SHIM_PORT}
     * @param clientHost   which POS to ask about, or {@code null} to let the shim resolve it;
     *                     it does so when exactly one POS is connected, and refuses otherwise
     * @param timeout      per-request timeout; no call here is unbounded
     */
    public PosShimClient(String registerHost, int shimPort, String clientHost, Duration timeout) {
        this.registerHost = registerHost;
        this.base = URI.create("http://" + registerHost + ":" + shimPort);
        this.clientHost = clientHost;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public URI baseUri() {
        return base;
    }

    public Health health() {
        return get("/health", Health.class);
    }

    public Screen screen() {
        return get("/pos/screen", Screen.class);
    }

    public SupportedKeys supportedKeys() {
        return get("/pos/keys", SupportedKeys.class);
    }

    /**
     * Presses POS keys by name ({@code "YES"}, {@code "VOID_TICKET"}) or raw {@code 0xNN} code.
     *
     * <p>This drives the live POS. The timeout grows with the number of keys, because the
     * shim waits for the POS to acknowledge each one.
     */
    public KeyPressResult pressKeys(List<String> keys) {
        Duration keyTimeout = timeout.plus(KEY_PROCESSING.multipliedBy(keys.size()));
        return request("POST", "/pos/keys", Map.of("keys", keys), keyTimeout, KeyPressResult.class);
    }

    private <T> T get(String path, Class<T> type) {
        return request("GET", path, null, timeout, type);
    }

    /**
     * Calls the shim and translates the reply into the {@code devices} exceptions.
     *
     * <p>The mapping is the point of this method, and each arm tells whoever reads the
     * report something different:
     * <ul>
     *   <li>transport failure: {@link DeviceUnreachable}, the shim is not there;</li>
     *   <li>501: {@link DeviceUnavailable}, the shim is there and this surface has no source;</li>
     *   <li>503 with a rig code: {@link DeviceUnreachable}, the POS is not talking to the shim;</li>
     *   <li>any other 4xx: {@link DeviceProtocolError}, the two sides disagree about the contract.</li>
     * </ul>
     */
    private <T> T request(String method, String path, Object body, Duration callTimeout, Class<T> type) {
        String call = method + " " + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path + query()))
                .timeout(callTimeout)
                .header("Accept", "application/json");
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(body == null ? Map.of() : body)));
        } else {
            builder.GET();
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DeviceUnreachable("POS shim at " + base,
                    e.getClass().getSimpleName() + " calling " + call + ": " + e.getMessage()
                            + ". Is petrotest-pos-shim.sh started on the register?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceError("Interrupted calling " + call + " on the POS shim at " + base, e);
        }

        int status = response.statusCode();
        if (status == 200) {
            try {
                return JSON.readValue(response.body(), type);
            } catch (JsonProcessingException e) {
                throw new DeviceProtocolError(call, "reply did not match " + type.getSimpleName()
                        + ": " + e.getOriginalMessage(), e);
            }
        }

        String[] error = errorOf(response);
        String code = error[0];
        String detail = error[1];
        if (status == 501) {
            throw new DeviceUnavailable("POS shim " + call, detail);
        }
        if (RIG_FAULT_CODES.contains(code)) {
            throw new DeviceUnreachable("POS at " + registerHost + " (via the shim)", detail);
        }
        if (status >= 400 && status < 500) {
            throw new DeviceProtocolError(call, code + ": " + detail);
        }
        throw new DeviceError("POS shim " + call + " returned " + status + ": " + detail);
    }

    private String query() {
        if (clientHost == null || clientHost.isEmpty()) {
            return "";
        }
        return "?client=" + URLEncoder.encode(clientHost, StandardCharsets.UTF_8);
    }

    /**
     * {@code {code, detail}} out of a shim error body, tolerating a body that is not one.
     *
     * <p>A shim error is always {@code {"error": code, "detail": text}}, but a proxy or a
     * crash can put something else on the wire, and losing the status code behind a
     * parse error would be the wrong trade.
     */
    private static String[] errorOf(HttpResponse<String> response) {
        String text = response.body() == null ? "" : response.body();
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            String shown = text.isEmpty() ? "HTTP " + response.statusCode() : text.substring(0, Math.min(300, text.length()));
            return new String[] {"non-json", shown};
        }
        String code = node.path("error").asText("unknown");
        String detail = node.path("detail").asText("");
        return new String[] {code, detail.isEmpty() ? "no detail given" : detail};
    }

    private static String toJson(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode request body: " + e.getOriginalMessage(), e);
        }
    }
}
