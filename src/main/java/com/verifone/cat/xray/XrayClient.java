package com.verifone.cat.xray;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianClient.RawReply;
import com.verifone.cat.atlassian.AtlassianUnavailable;

/**
 * The two write surfaces the Xray reporter uses: Xray Cloud's result import, and Jira's issue
 * endpoints. Port of the Python CAT's {@code cat.xray.client}.
 *
 * <p>Calls go through {@link AtlassianClient}, which owns the credentials and the HTTP plumbing;
 * this class decides what a reply means: a bearer token kept for 23 hours and refreshed on a 401,
 * a bounded retry on 429 and 5xx (three times, a {@code Retry-After} honoured up to two minutes),
 * and Jira's anonymous-404 check. Every failure is an {@link AtlassianUnavailable} that names the
 * call and the status, never a credential.
 *
 * <p>Requests sent are: {@code POST import/execution} (Xray, creates the execution or merges
 * statuses fifty at a time), {@code GET/PUT rest/api/3/issue/<key>} (reporter, description),
 * {@code POST .../comment}, {@code GET myself}, and the transitions by name. Run against the live
 * site once, on 2026-10-07, in the Python CAT; this port has only met a loopback fake.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the tests run against a loopback fake, never the live site.
 */
public final class XrayClient {

    private static final Logger LOG = LoggerFactory.getLogger(XrayClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tests per import call, PATS's {@code TestExecutionHelper.LIMIT}. */
    public static final int BATCH_SIZE = 50;

    /** How long a bearer token is trusted before a fresh one is fetched (Xray's last 24 hours). */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(23);

    /** Retries on a 429 or 5xx. */
    static final int RETRIES = 3;

    /** The longest {@code Retry-After} wait honoured. */
    static final double RETRY_AFTER_MAX_SECONDS = 120.0;

    /** A Jira identity: the account id and the display name {@code GET myself} returns. */
    public record Myself(String accountId, String displayName) {}

    private final AtlassianClient atlassian;
    private final Duration retryWait;
    private final CountDownLatch cancel;
    private String token = "";
    private long tokenAtNanos;

    /**
     * @param atlassian the HTTP client that holds the credentials
     * @param retryWait the wait before a retry when the site sends no {@code Retry-After};
     *                  tests pass zero
     * @param cancel    counted down to end a retry wait early when the sidecar is told to stop
     */
    public XrayClient(AtlassianClient atlassian, Duration retryWait, CountDownLatch cancel) {
        this.atlassian = atlassian;
        this.retryWait = retryWait;
        this.cancel = cancel == null ? new CountDownLatch(1) : cancel;
    }

    // ------------------------------------------------------------------ Xray

    /**
     * {@code POST import/execution} with an Xray-JSON payload; returns the execution key. A payload
     * with {@code info} creates the execution; one with {@code testExecutionKey} updates it.
     *
     * @throws AtlassianUnavailable on any failure, with the status and a body excerpt
     */
    public String importExecution(Map<String, Object> payload) {
        RawReply response = send(true, "POST", "import/execution", payload,
                "Xray import/execution", true);
        JsonNode body = parseOrNull(response.body());
        if (body == null) {
            throw new AtlassianUnavailable("Xray import/execution -> non-JSON body: "
                    + excerpt(response.body(), 200));
        }
        String key = textOf(body, "key");
        if (key.isEmpty()) {
            key = textOf(body.path("testExecIssue"), "key");
        }
        if (key.isEmpty()) {
            throw new AtlassianUnavailable("Xray import/execution -> no execution key in: "
                    + excerpt(body.toString(), 200));
        }
        return key;
    }

    // ------------------------------------------------------------------ Jira

    /** The Jira token's own user: {@code GET rest/api/3/myself}. */
    public Myself myself() {
        RawReply response = send(false, "GET", "rest/api/3/myself", null, "Jira myself", false);
        JsonNode body = parseOrNull(response.body());
        if (body == null) {
            throw new AtlassianUnavailable("Jira myself -> non-JSON body");
        }
        String account = textOf(body, "accountId");
        if (account.isEmpty()) {
            throw new AtlassianUnavailable("Jira myself -> no accountId in the response");
        }
        String name = textOf(body, "displayName");
        return new Myself(account, name.isEmpty() ? account : name);
    }

    /** Sets the execution's reporter (Jira's creator stays the Xray key's account). */
    public void setReporter(String issueKey, String accountId) {
        send(false, "PUT", "rest/api/3/issue/" + issueKey,
                Map.of("fields", Map.of("reporter", Map.of("accountId", accountId))),
                "Jira reporter " + issueKey, false);
    }

    /** Sets the issue's description to an ADF document. */
    public void setDescription(String issueKey, JsonNode adf) {
        send(false, "PUT", "rest/api/3/issue/" + issueKey, Map.of("fields", Map.of("description", adf)),
                "Jira describe " + issueKey, false);
    }

    /** Adds a comment with an ADF body. */
    public void addComment(String issueKey, JsonNode adf) {
        send(false, "POST", "rest/api/3/issue/" + issueKey + "/comment", Map.of("body", adf),
                "Jira comment " + issueKey, false);
    }

    /**
     * Applies the workflow transition called {@code name}, if the issue offers it.
     *
     * @return whether it was applied; a workflow without that transition is not an error
     */
    public boolean transition(String issueKey, String name) {
        String path = "rest/api/3/issue/" + issueKey + "/transitions";
        RawReply response = send(false, "GET", path, null, "Jira transitions " + issueKey, false);
        JsonNode body = parseOrNull(response.body());
        if (body == null) {
            throw new AtlassianUnavailable("Jira transitions -> non-JSON body");
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (JsonNode transition : body.path("transitions")) {
            if (textOf(transition, "name").toLowerCase(Locale.ROOT).equals(wanted)) {
                send(false, "POST", path, Map.of("transition", Map.of("id", textOf(transition, "id"))),
                        "Jira transition " + issueKey + " -> " + name, false);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ plumbing

    /** A bearer token for Xray: the cached one, or a fresh one when stale or asked for. */
    private String bearer(boolean refresh) {
        long now = System.nanoTime();
        if (!token.isEmpty() && !refresh && now - tokenAtNanos < TOKEN_LIFETIME.toNanos()) {
            return token;
        }
        token = atlassian.xrayAuthenticate();
        tokenAtNanos = now;
        return token;
    }

    /**
     * One call, retried on 429 and 5xx, with a fresh Xray token once on a 401 when allowed.
     *
     * @return the 2xx reply
     * @throws AtlassianUnavailable on a final non-2xx, with the status and an excerpt
     */
    private RawReply send(boolean xray, String method, String path, Object body, String what,
            boolean retryUnauthorized) {
        int attempt = 0;
        boolean refresh = false;
        boolean mayRefresh = retryUnauthorized;
        RawReply response;
        while (true) {
            response = xray
                    ? atlassian.xrayRequest(method, path, bearer(refresh), body)
                    : atlassian.jiraRequest(method, path, body);
            refresh = false;
            if (response.isSuccess()) {
                return response;
            }
            if (response.status() == 401 && xray && mayRefresh) {
                mayRefresh = false;
                refresh = true;
                continue;
            }
            if (response.status() == 429 || response.status() >= 500) {
                attempt++;
                if (attempt <= RETRIES) {
                    Duration wait = retryAfter(response.retryAfter());
                    LOG.warn("{} -> HTTP {}; retrying in {}s", what, response.status(), wait.toSeconds());
                    if (awaitCancel(wait)) {
                        break;
                    }
                    continue;
                }
            }
            break;
        }
        if (response.status() == 404 && !xray && jiraRejects()) {
            // Jira serves a request whose Basic credentials it rejects as an anonymous one, and an
            // anonymous caller cannot see a PETROCORE issue: the 404 says "does not exist" about an
            // issue that does. Run 168e643ede21 ended that way (HANDOFF.md section 1.127 in the Python).
            throw new AtlassianUnavailable(what + " -> HTTP 404, but the cause is the Jira credentials: "
                    + "Jira answers 'GET /myself' with 401, so it served this request as anonymous. "
                    + "Re-enter the Atlassian API token: press s in CAT.");
        }
        throw new AtlassianUnavailable(what + " -> HTTP " + response.status() + ": "
                + excerpt(response.body(), 300));
    }

    /** Whether Jira answers {@code GET myself} with a 401; any other outcome, a failed call included, is false. */
    private boolean jiraRejects() {
        try {
            return atlassian.jiraRequest("GET", "rest/api/3/myself", null).status() == 401;
        } catch (AtlassianUnavailable e) {
            return false;
        }
    }

    /** Waits up to {@code wait}; true when the sidecar was told to stop in the meantime. */
    private boolean awaitCancel(Duration wait) {
        try {
            return cancel.await(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** The wait before a retry: the {@code Retry-After} header, capped, else the default. */
    Duration retryAfter(String header) {
        if (header == null || header.isBlank()) {
            return retryWait;
        }
        try {
            double seconds = Math.min(Double.parseDouble(header.strip()), RETRY_AFTER_MAX_SECONDS);
            return Duration.ofMillis(Math.round(seconds * 1000));
        } catch (NumberFormatException e) {
            return retryWait;
        }
    }

    private static JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** The first {@code max} characters of a body, as the Python's {@code [:n]}. */
    static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.substring(0, Math.min(max, text.length()));
    }
}
