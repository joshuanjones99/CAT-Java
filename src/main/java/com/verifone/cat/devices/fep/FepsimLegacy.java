package com.verifone.cat.devices.fep;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pats.runner.handlers.configuration.BuypassV2;

/**
 * Petro Simulator Suite's FEP simulator (v2) answering Buypass, as PATS's packet actions see it. Ported from
 * the Python CAT's {@code petrotest.devices.fepsim_legacy}; it is the production binding of the configuration
 * family's {@link BuypassV2} seam.
 *
 * <p>When CAT's step 3 lets FEPSIM v2 serve Buypass in BUYPASS Host's place, the three PATS actions that
 * reach into BUYPASS Host's folder go to v2 instead, told so through {@link #ENV}:
 * <ul>
 *   <li>{@code UPDATE_CONFIGURATION}: v2's {@code buypass-ahs} rule set takes the {@code PayHostSim.prop}
 *       text, PUT to {@code /admin/fep/<fep>/<port>/legacy-config} ({@link #applyConfig}).</li>
 *   <li>{@code RESET_PACKETS}: DELETE {@code .../legacy-packets} ({@link #clearPackets}).</li>
 *   <li>{@code VALIDATE_PACKETS}: v2 writes the same file names to {@link #packets()}.</li>
 * </ul>
 *
 * <p>The routes are read from v2's {@code AdminRequestDispatcher} (build 461, javap, 2026-10-13); the two GETs
 * answered on the lab controller that day. UNVERIFIED AGAINST HARDWARE (Java port): the PUT and the DELETE
 * have not been sent to a live v2, and this port has spoken only to a loopback stand-in.
 *
 * @param admin   v2's admin base URL, without a trailing slash
 * @param fep     the FEP profile, {@code BUYPASS}
 * @param port    the listener's port
 * @param packets the instance's legacy packet folder
 */
public record FepsimLegacy(String admin, String fep, int port, Path packets) implements BuypassV2 {

    /**
     * Set by CAT for the case runner when v2 serves Buypass this run: a JSON object with {@code admin},
     * {@code fep}, {@code port} and {@code packets}.
     */
    public static final String ENV = "PETROTEST_FEPSIM_BUYPASS";

    /** Each admin call is a local round trip; v2 answered these in well under a second. */
    public static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(ADMIN_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public FepsimLegacy {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(fep, "fep");
        Objects.requireNonNull(packets, "packets");
        while (admin.endsWith("/")) {
            admin = admin.substring(0, admin.length() - 1);
        }
    }

    /** From {@link #ENV}'s JSON. */
    public static FepsimLegacy fromJson(String text) {
        JsonNode data;
        try {
            data = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(ENV + " is not JSON: " + e.getOriginalMessage(), e);
        }
        return new FepsimLegacy(required(data, "admin").asText(), required(data, "fep").asText(),
                Integer.parseInt(required(data, "port").asText()), Path.of(required(data, "packets").asText()));
    }

    private static JsonNode required(JsonNode data, String key) {
        JsonNode value = data == null ? null : data.get(key);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(ENV + " has no " + key);
        }
        return value;
    }

    /** {@link #ENV}'s JSON for this listener. */
    public String toJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("admin", admin);
        data.put("fep", fep);
        data.put("port", port);
        data.put("packets", packets.toString());
        try {
            return JSON.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The v2 listener serving Buypass this run, from {@link #ENV}, or empty when it is unset or blank. */
    public static Optional<FepsimLegacy> buypassFepsim(Map<String, String> environ) {
        String text = environ == null ? null : environ.get(ENV);
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(fromJson(text.strip()));
    }

    /** {@link #buypassFepsim(Map)} over the process's own environment. */
    public static Optional<FepsimLegacy> buypassFepsim() {
        return buypassFepsim(System.getenv());
    }

    String url(String action) {
        return admin + "/admin/fep/" + fep + "/" + port + "/" + action;
    }

    /** The legacy config last applied, or null when none has been (the catalogue answers). */
    @Override
    public String configText() {
        JsonNode value = call("GET", "legacy-config", null).get("configText");
        return value == null || value.isNull() ? null : value.asText();
    }

    /** {@code UPDATE_CONFIGURATION}'s file, as the rule set's input. */
    @Override
    public void applyConfig(String text) {
        call("PUT", "legacy-config", text == null ? "" : text);
    }

    /** {@code RESET_PACKETS}: v2 empties its own packet store. */
    public void clearPackets() {
        call("DELETE", "legacy-packets", null);
    }

    private JsonNode call(String method, String action, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(action))).timeout(ADMIN_TIMEOUT);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "text/plain; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> reply;
        try {
            reply = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DeviceUnreachable("FEP simulator at " + admin, method + " " + action + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceUnreachable("FEP simulator at " + admin, method + " " + action + ": interrupted", e);
        }
        String text = reply.body() == null ? "" : reply.body();
        if (reply.statusCode() >= 400) {
            throw new DeviceError("FEP simulator " + method + " " + action + ": HTTP " + reply.statusCode() + ": "
                    + text.substring(0, Math.min(200, text.length())));
        }
        try {
            JsonNode parsed = JSON.readTree(text);
            return parsed != null && parsed.isObject() ? parsed : JSON.createObjectNode();
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }
}
