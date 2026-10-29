package com.verifone.cat.devices.csite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnreachable;
import com.verifone.cat.pats.runner.handlers.devices.DevicePauses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * PATS's HTTP client for the Verifone cloud ("CSITE"): its token, its retries and its requests. Ported from the Python
 * {@code petrotest.devices.csite}, which is the specification.
 *
 * <ul>
 *   <li><b>Token</b> (PATS's {@code CsiteTokenFetcher}): OpenAM's authenticate, the OAuth device-code grant approved with
 *       that session, then the bearer token. Kept per environment and user, and fetched again on a 401.</li>
 *   <li><b>Retries</b> (PATS's {@code RetryExecutor}): up to {@code cloudRetry} attempts; a 401 refreshes the token,
 *       and 401, 406, 429 and 503 are tried again after {@link #RETRY_PAUSE}. A transport failure is retried when the
 *       caller asks. Any other status is the answer, a 4xx or 5xx included.</li>
 *   <li><b>Redirects</b> are not followed, so the token never leaves the host it was sent to.</li>
 * </ul>
 *
 * <p>Errors map to the device exceptions: a transport failure is {@link DeviceUnreachable}; an answer this client cannot
 * read is {@link DeviceProtocolError}; a missing credential, a refused setting or a non-success status is a plain
 * {@link DeviceError}. No message carries the password or the token.
 *
 * <p>The credentials come only from {@link CSiteSettings}, which reads them from the {@link #ENV_VARS} environment
 * variables. Nothing here is hard-coded.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only (a loopback stand-in for OpenAM, the device grant and the CSITE
 * API). The endpoints are passed in; {@link CommanderCloudConfig} reads them off the rig's Commander (the Python's
 * {@code commander_cloud_config}, wave 16).
 */
public final class CSiteClient {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.devices.csite");

    /** PATS's {@code cloudcredentials.prop} key to the environment variable CAT-Java reads it from. */
    public static final Map<String, String> ENV_VARS = Map.of(
            "cfg_env", "CAT_CSITE_ENV",
            "csite.username", "CAT_CSITE_USERNAME",
            "csite.password", "CAT_CSITE_PASSWORD",
            "csite.strip.service.prefix", "CAT_CSITE_STRIP_SERVICE_PREFIX",
            "isDisableCsiteSSLVerification", "CAT_CSITE_DISABLE_SSL_VERIFICATION",
            "cloud.auth.mode", "CAT_CSITE_AUTH_MODE",
            "csite.auth.type", "CAT_CSITE_AUTH_TYPE");

    /** {@code RetryExecutor}'s retried statuses, and the pause between attempts. */
    static final Set<Integer> RETRY_STATUSES = Set.of(401, 406, 429, 503);
    static final Duration RETRY_PAUSE = Duration.ofSeconds(5);

    /** OpenAM's API version header ({@code CsiteTokenFetcher}). */
    static final String OPENAM_VERSION = "resource=2.0, protocol=1.0";
    /** The texts a device approval accepts. */
    static final List<String> APPROVED = List.of("Device authorized", "User verification successful");
    /** The JSON headers every request carries. */
    static final Map<String, String> ACCEPT_JSON = Map.of("Accept", "application/json");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WHAT = "C-Site";

    private final CSiteSettings settings;
    private final HttpClient http;
    private final Consumer<Duration> pause;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    /**
     * A client over {@code http}, pausing between retries with {@code pause} (a test records the pauses).
     *
     * @param settings the login and endpoints
     * @param http     the HTTP client; it should not follow redirects
     * @param pause    the pause between retries
     */
    public CSiteClient(CSiteSettings settings, HttpClient http, Consumer<Duration> pause) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = Objects.requireNonNull(http, "http");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * A client with the production pause, and a connect timeout and redirect policy from the settings.
     */
    public static CSiteClient of(CSiteSettings settings) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.readTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new CSiteClient(settings, http, DevicePauses.REAL::pause);
    }

    /** The settings this client was built with. */
    public CSiteSettings settings() {
        return settings;
    }

    /**
     * One request, retried as PATS retries it.
     *
     * @param method  the HTTP verb
     * @param endpoint the path after the base URL, with or without its leading {@code /}
     * @param headers the request headers (the bearer token is added here)
     * @param body    the request body, or {@code null} for none (a GET or HEAD never sends one)
     * @param retry   whether a transport failure is retried
     * @return the answer, whatever its status
     * @throws DeviceUnreachable  when the request could not be made, after its attempts
     * @throws DeviceError        when the token could not be fetched or the request is refused
     * @throws DeviceProtocolError when a file answer, which this port does not read, comes back
     */
    public CSiteResponse send(String method, String endpoint, Map<String, String> headers, String body,
            boolean retry) {
        String url = finalUrl(settings.baseUrl(), endpoint);
        String verb = method.toUpperCase(Locale.ROOT);
        byte[] content = verb.equals("GET") || verb.equals("HEAD") || body == null
                ? null : body.getBytes(StandardCharsets.UTF_8);
        int attempts = settings.attempts();
        boolean refresh = false;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String token = token(refresh);
            refresh = false;
            HttpRequest request = request(verb, url, headers, token, content);
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (!retry || attempt >= attempts) {
                    throw new DeviceUnreachable(WHAT, verb + " " + url + ": " + e.getClass().getSimpleName()
                            + ": " + e.getMessage(), e);
                }
                LOG.warn("{} {} attempt {}: {}; trying again", verb, url, attempt, e.getClass().getSimpleName());
                pause.accept(RETRY_PAUSE);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DeviceUnreachable(WHAT, verb + " " + url + ": interrupted", e);
            }
            int status = response.statusCode();
            if (!retry || !RETRY_STATUSES.contains(status)) {
                return answer(response, url);
            }
            if (attempt >= attempts) {
                break;
            }
            LOG.warn("{} {} attempt {}: HTTP {}; trying again", verb, url, attempt, status);
            refresh = status == 401;
            pause.accept(RETRY_PAUSE);
        }
        // RetryExecutor's quirk: a retryable status on the last attempt leaves its loop.
        throw new DeviceProtocolError(WHAT, "retry loop terminated unexpectedly");
    }

    // -- token -------------------------------------------------------------------------------

    /** The bearer token for this environment and user: kept, or fetched again when {@code refresh}. */
    String token(boolean refresh) {
        String key = settings.env() + "\u0000" + settings.username();
        if (refresh || !tokens.containsKey(key)) {
            tokens.put(key, fetchToken());
        }
        return tokens.get(key);
    }

    /**
     * {@code CsiteTokenFetcher.fetchToken}: OpenAM session, device code, approval, token.
     *
     * @throws DeviceError when a credential is missing, or any step of the grant answers wrongly
     */
    String fetchToken() {
        if (settings.username().isBlank() || settings.password().isBlank()) {
            throw new DeviceError("No CSITE credentials configured for role default: set $"
                    + ENV_VARS.get("csite.username") + " and $" + ENV_VARS.get("csite.password")
                    + " (PATS's cloudcredentials.prop csite.username / csite.password)");
        }
        String authUrl = settings.appSetting("cloud.auth.id.url", null);
        String tokenUrl = settings.appSetting("cloud.access.token.url", null);
        String clientId = settings.appSetting("cloud.petro.client.id", "PetroClient");
        String scope = settings.appSetting("cloud.petro.scope", "write");
        URI auth = URI.create(authUrl);
        String oauth = auth.getScheme() + "://" + auth.getRawAuthority() + "/am/oauth2";
        try {
            HttpResponse<byte[]> session = post(authUrl, Map.of(
                    "X-OpenAM-Username", settings.username(),
                    "X-OpenAM-Password", settings.password(),
                    "Accept-API-Version", OPENAM_VERSION,
                    "Content-Type", "application/json"), "{}");
            if (session.statusCode() != 200) {
                throw new DeviceError("OpenAM authenticate answered HTTP " + session.statusCode());
            }
            String tokenId = JSON.readTree(session.body()).get("tokenId").asText();

            JsonNode code = JSON.readTree(text(post(oauth + "/device/code", Map.of(), form(Map.of(
                    "response_type", "device_code", "client_id", clientId, "scope", scope)))));
            HttpResponse<byte[]> approval = post(oauth + "/device/user", Map.of("Accept", "text/html"), form(Map.of(
                    "user_code", code.get("user_code").asText(), "decision", "allow", "csrf", tokenId)));
            String page = text(approval);
            if (approval.statusCode() != 200 || APPROVED.stream().noneMatch(page::contains)) {
                throw new DeviceError("device approval answered HTTP " + approval.statusCode());
            }
            HttpResponse<byte[]> grant = post(tokenUrl, Map.of(), form(Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", code.get("device_code").asText(),
                    "client_id", clientId)));
            JsonNode body = grant.statusCode() == 200 ? JSON.readTree(grant.body()) : JSON.createObjectNode();
            JsonNode access = body.get("access_token");
            JsonNode type = body.get("token_type");
            if (access == null || access.asText().isEmpty()
                    || type == null || !type.asText("").toLowerCase(Locale.ROOT).equals("bearer")) {
                throw new DeviceError("the token request answered HTTP " + grant.statusCode());
            }
            return access.asText();
        } catch (IOException e) {
            throw new DeviceError("Failed to generate CSITE access token: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceError("Failed to generate CSITE access token: interrupted", e);
        } catch (RuntimeException e) {
            // A missing field or an unreadable answer: the grant's own message, without any credential.
            throw new DeviceError("Failed to generate CSITE access token: " + e.getMessage(), e);
        }
    }

    // -- requests ----------------------------------------------------------------------------

    private HttpResponse<byte[]> post(String url, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(settings.readTimeoutMillis()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        if (!headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest request(String verb, String url, Map<String, String> headers, String token, byte[] content) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(settings.readTimeoutMillis()));
        Map<String, String> sent = new LinkedHashMap<>(headers);
        sent.put("Authorization", "Bearer " + token);
        sent.forEach(builder::header);
        switch (verb) {
            case "GET" -> builder.GET();
            case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            default -> builder.method(verb, content == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(content));
        }
        return builder.build();
    }

    private static CSiteResponse answer(HttpResponse<byte[]> response, String url) {
        Optional<String> disposition = response.headers().firstValue("content-disposition");
        if (disposition.isPresent() && disposition.get().toLowerCase(Locale.ROOT).contains("filename=")) {
            throw new DeviceProtocolError(WHAT, url + " answered a file (" + disposition.get()
                    + "); file responses are not ported");
        }
        Optional<String> contentType = response.headers().firstValue("content-type");
        // RequestManager.readHTTPResponse: with no Content-Type the payload is "" unread.
        byte[] body = contentType.isPresent() ? response.body() : new byte[0];
        return new CSiteResponse(response.statusCode(), contentType.orElse(null), body, url);
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** {@code application/x-www-form-urlencoded}, in the map's order. */
    static String form(Map<String, String> fields) {
        List<String> pairs = new ArrayList<>();
        fields.forEach((key, value) -> pairs.add(encode(key) + "=" + encode(value)));
        return String.join("&", pairs);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    // -- URLs --------------------------------------------------------------------------------

    /**
     * {@code UrlBuilder.buildFinalUrl}: the base's scheme and host, its path plus {@code endpoint} (given a leading
     * {@code /}), quoted as Python's {@code urllib.parse.quote} quotes it (a query is dropped).
     *
     * @throws DeviceError when the endpoint is empty
     */
    public static String finalUrl(String base, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new DeviceError("Endpoint cannot be empty");
        }
        String tail = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        URI parts = URI.create(base);
        String rawPath = parts.getRawPath() == null ? "" : parts.getRawPath();
        return parts.getScheme() + "://" + parts.getRawAuthority() + quote(rawPath + tail);
    }

    /** Python's {@code quote(text, safe="/@:;=+$,!~*'()&-._")}: UTF-8 bytes, percent-encoded outside the safe set. */
    static String quote(String text) {
        String safe = "/@:;=+$,!~*'()&-._";
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c < 128 && (Character.isLetterOrDigit(c) || safe.indexOf(c) >= 0)) {
                out.append(c);
            } else {
                out.append(String.format(Locale.ROOT, "%%%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    // -- the account -------------------------------------------------------------------------

    /**
     * The {@code CAT_CSITE_*} variables for a Commander Connect account (the Config tab's lab, e.g. {@code QAT}): nothing
     * when {@code environ} already names a C-Site user, or the account is incomplete. The environment wins, so an
     * operator can still point C-Site elsewhere.
     */
    public static Map<String, String> accountEnv(String lab, String email, String password,
            Map<String, String> environ) {
        if (!environ.getOrDefault(ENV_VARS.get("csite.username"), "").isBlank()) {
            return Map.of();
        }
        if (lab.isBlank() || email.isBlank() || password.isEmpty()) {
            return Map.of();
        }
        return Map.of(
                ENV_VARS.get("cfg_env"), lab.strip().toLowerCase(Locale.ROOT),
                ENV_VARS.get("csite.username"), email.strip(),
                ENV_VARS.get("csite.password"), password);
    }
}
