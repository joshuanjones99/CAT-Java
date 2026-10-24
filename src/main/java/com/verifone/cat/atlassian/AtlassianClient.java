package com.verifone.cat.atlassian;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.atlassian.AtlassianCredentials.Jira;
import com.verifone.cat.atlassian.AtlassianCredentials.Xray;

/**
 * Read-only HTTP access to Confluence and Xray Cloud, the Java counterpart of the Python CAT's
 * {@code cat.atlassian.get_json} and {@code cat.atlassian.xray_graphql}.
 *
 * <p>Two kinds of call. {@link #getJson} is a Confluence REST GET with HTTP Basic auth (email and
 * token). {@link #xrayGraphql} authenticates to Xray with a client id and secret, then sends one
 * GraphQL query with the bearer token it was given. Nothing here writes to Atlassian.
 *
 * <p>The credential suppliers are called before any request is made, so a machine with no
 * credentials file fails with a setup message and never times out against the site. The
 * credentials are never logged, never put in an exception message, and never written to disk.
 *
 * <p>Redirects are not followed. Python's {@code httpx} follows them and strips Basic auth on a
 * cross-origin hop; Java's client does not strip it, so a redirect here is reported as a status
 * error rather than followed with the token attached.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests run against a loopback fake, never the live site.
 */
public final class AtlassianClient {

    /** The Atlassian site both surfaces live on, as the Python CAT's {@code SITE}. */
    public static final URI SITE = URI.create("https://verifone.atlassian.net");

    /** Xray Cloud's API, as the Python CAT's {@code XRAY_API}. */
    public static final URI XRAY_API = URI.create("https://xray.cloud.getxray.app/api/v2");

    /** The longest body excerpt an error message carries, as the Python's {@code [:200]}. */
    static final int EXCERPT = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI site;
    private final URI xrayApi;
    private final Supplier<Jira> jira;
    private final Supplier<Xray> xray;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param site    the Atlassian site, e.g. {@link #SITE}; a loopback URL in tests
     * @param xrayApi Xray's API base, e.g. {@link #XRAY_API}
     * @param jira    the email and token, read when a Confluence call is made
     * @param xray    the Xray client id and secret, read when an Xray call is made
     * @param timeout per-request timeout; no call here is unbounded
     */
    public AtlassianClient(URI site, URI xrayApi, Supplier<Jira> jira, Supplier<Xray> xray,
            Duration timeout) {
        this.site = stripTrailingSlash(site);
        this.xrayApi = stripTrailingSlash(xrayApi);
        this.jira = jira;
        this.xray = xray;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** A client against the real site, reading the credentials file at {@code file} on each call. */
    public static AtlassianClient forCredentialsFile(java.nio.file.Path file, Duration timeout) {
        return new AtlassianClient(SITE, XRAY_API,
                () -> AtlassianCredentials.loadJira(file),
                () -> AtlassianCredentials.loadXray(file),
                timeout);
    }

    /**
     * GET {@code path} under the site with Basic auth and return the decoded JSON body.
     *
     * @throws AtlassianUnavailable on missing credentials, a transport error, a non-2xx status
     *     (with the status and the first 200 characters of the body), or a body that is not JSON
     */
    public JsonNode getJson(String path, Map<String, String> query) {
        Jira credentials = jira.get();
        String url = site + "/" + path.replaceFirst("^/+", "") + queryString(query);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", basic(credentials))
                .GET()
                .build();
        HttpResponse<String> response = send(request, "GET " + path);
        requireSuccess(response, "GET " + path);
        return parse(response.body(), "GET " + path);
    }

    /**
     * Authenticates to Xray, runs {@code query} with {@code variables}, and returns the reply's
     * {@code data} object (an empty object when it has none).
     *
     * @throws AtlassianUnavailable on missing credentials, a transport error, a non-2xx status,
     *     a non-JSON body, or GraphQL {@code errors}
     */
    public JsonNode xrayGraphql(String query, Map<String, Object> variables) {
        String base = xrayApi.toString();
        String bearer = xrayAuthenticate();

        Map<String, Object> graphBody = new LinkedHashMap<>();
        graphBody.put("query", query);
        graphBody.put("variables", variables == null ? Map.of() : variables);
        HttpResponse<String> response = send(
                post(base + "/graphql", graphBody, "Bearer " + bearer), "Xray graphql");
        if (!isSuccess(response)) {
            throw new AtlassianUnavailable("Xray graphql -> HTTP " + response.statusCode() + ": "
                    + excerpt(response.body()));
        }
        JsonNode body = parseOrNull(response.body());
        if (body == null) {
            throw new AtlassianUnavailable("Xray graphql -> non-JSON body: " + excerpt(response.body()));
        }
        JsonNode errors = body.get("errors");
        if (truthy(errors)) {
            throw new AtlassianUnavailable("Xray graphql -> errors: " + excerpt(errors.toString()));
        }
        JsonNode data = body.get("data");
        return truthy(data) ? data : JSON.createObjectNode();
    }

    /**
     * One reply, read but not judged: the status and body as the site sent them. A caller that
     * retries, or reports by status, uses this rather than the exception-raising calls above.
     *
     * @param status     the HTTP status
     * @param body       the body text, possibly empty
     * @param retryAfter the {@code Retry-After} header, or {@code null} when none was sent
     */
    public record RawReply(int status, String body, String retryAfter) {

        /** Whether the status is 2xx. */
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /**
     * Signs in to Xray with the Xray client pair and returns the bearer token.
     *
     * @throws AtlassianUnavailable on missing credentials, a transport error, a non-2xx status,
     *     or a reply that is not a token; the message never carries the secret
     */
    public String xrayAuthenticate() {
        Xray credentials = xray.get();
        Map<String, Object> authBody = new LinkedHashMap<>();
        authBody.put("client_id", credentials.clientId());
        authBody.put("client_secret", credentials.clientSecret());
        HttpResponse<String> auth = send(post(xrayApi + "/authenticate", authBody, null), "Xray authenticate");
        if (!isSuccess(auth)) {
            throw new AtlassianUnavailable("Xray authenticate -> HTTP " + auth.statusCode());
        }
        JsonNode token = parseOrNull(auth.body());
        if (token == null || !token.isTextual() || token.asText().isEmpty()) {
            throw new AtlassianUnavailable("Xray authenticate -> non-JSON token");
        }
        return token.asText();
    }

    /**
     * One Xray REST call under the API base ({@code path} is relative to it), sent with the
     * bearer token the caller holds. The reply is returned unjudged; only a transport error throws.
     *
     * @param method the HTTP method, e.g. {@code POST}
     * @param body   the JSON body, or {@code null} for none
     */
    public RawReply xrayRequest(String method, String path, String bearer, Object body) {
        HttpRequest request = restRequest(xrayApi + "/" + trimLeadingSlashes(path), method, body,
                "Bearer " + bearer);
        return raw(request, method + " " + path);
    }

    /**
     * One Jira REST call under the site ({@code path} is relative to it), with Basic auth from the
     * Jira credentials. The reply is returned unjudged; only a transport error throws.
     *
     * @param method the HTTP method, e.g. {@code PUT}
     * @param body   the JSON body, or {@code null} for none
     */
    public RawReply jiraRequest(String method, String path, Object body) {
        Jira credentials = jira.get();
        HttpRequest request = restRequest(site + "/" + trimLeadingSlashes(path), method, body,
                basic(credentials));
        return raw(request, method + " " + path);
    }

    private HttpRequest restRequest(String url, String method, Object body, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", authorization);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            try {
                builder.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            } catch (JsonProcessingException e) {
                throw new AtlassianUnavailable("could not encode the request body", e);
            }
        }
        return builder.build();
    }

    private RawReply raw(HttpRequest request, String call) {
        HttpResponse<String> response = send(request, call);
        return new RawReply(response.statusCode(), response.body(),
                response.headers().firstValue("Retry-After").orElse(null));
    }

    private static String trimLeadingSlashes(String path) {
        return path.replaceFirst("^/+", "");
    }

    private HttpRequest post(String url, Map<String, Object> body, String authorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        try {
            return builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        } catch (JsonProcessingException e) {
            throw new AtlassianUnavailable("could not encode the request body", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String call) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The message names the call and the exception type only: never the URL's query or
            // any header, which is where the credentials are.
            throw new AtlassianUnavailable(call + " failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlassianUnavailable(call + " interrupted", e);
        }
    }

    private static void requireSuccess(HttpResponse<String> response, String call) {
        if (!isSuccess(response)) {
            throw new AtlassianUnavailable(call + " -> HTTP " + response.statusCode() + ": "
                    + excerpt(response.body()));
        }
    }

    private static JsonNode parse(String body, String call) {
        JsonNode node = parseOrNull(body);
        if (node == null) {
            throw new AtlassianUnavailable(call + " -> non-JSON body: " + excerpt(body));
        }
        return node;
    }

    /** The parsed body, or null when it is not JSON. */
    private static JsonNode parseOrNull(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            // An empty body reads as MissingNode, which is not a reply: it is not JSON either.
            return node == null || node.isMissingNode() ? null : node;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static boolean isSuccess(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    /** Basic auth from the email and token; the value is built here and goes nowhere else. */
    private static String basic(Jira credentials) {
        String pair = credentials.email() + ":" + credentials.token();
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("?");
        for (Map.Entry<String, String> param : query.entrySet()) {
            if (out.length() > 1) {
                out.append('&');
            }
            out.append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** The first {@value #EXCERPT} characters of {@code text}, as the Python's {@code [:200]}. */
    static String excerpt(String text) {
        return text == null ? "" : text.substring(0, Math.min(EXCERPT, text.length()));
    }

    /** Python's truth test on a decoded JSON value: null, false, 0 and empty values are false. */
    static boolean truthy(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.doubleValue() != 0;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        if (node.isContainerNode()) {
            return node.size() > 0;
        }
        return true;
    }

    private static URI stripTrailingSlash(URI uri) {
        String text = uri.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return URI.create(text);
    }
}
