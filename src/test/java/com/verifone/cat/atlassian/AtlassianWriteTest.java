package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.atlassian.AtlassianClient.RawReply;
import com.verifone.cat.atlassian.AtlassianCredentials.Jira;
import com.verifone.cat.atlassian.AtlassianCredentials.Xray;

/**
 * The write-side calls added for the Xray sidecar: {@link AtlassianClient#jiraRequest},
 * {@link AtlassianClient#xrayRequest} and {@link AtlassianClient#xrayAuthenticate}. Answered by
 * the loopback fake; evidence about this repository's requests, never about the live site.
 */
class AtlassianWriteTest {

    private static final Jira JIRA = new Jira("tester@verifone.com", "jira-token");
    private static final Xray XRAY = new Xray("xray-id", "xray-secret-value");

    private FakeAtlassian fake;
    private AtlassianClient client;

    @BeforeEach
    void start() {
        fake = new FakeAtlassian();
        client = new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"),
                () -> JIRA, () -> XRAY, Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void jiraRequestSendsBasicAuthAndTheJsonBodyAndReturnsTheStatusUnjudged() {
        fake.onRequest("PUT", "/rest/api/3/issue/PETROCORE-1", seen -> new FakeAtlassian.Reply(204, ""));

        RawReply reply = client.jiraRequest("PUT", "/rest/api/3/issue/PETROCORE-1",
                java.util.Map.of("fields", java.util.Map.of("summary", "x")));

        assertEquals(204, reply.status());
        assertTrue(reply.isSuccess());
        FakeAtlassian.Seen seen = fake.seen.get(fake.seen.size() - 1);
        assertEquals("PUT", seen.method());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                "tester@verifone.com:jira-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                seen.authorization());
        assertEquals("{\"fields\":{\"summary\":\"x\"}}", seen.body());
    }

    @Test
    void aJiraErrorStatusIsReturnedNotThrownAndKeepsRetryAfter() {
        fake.onRequest("POST", "/rest/api/3/issue/PETROCORE-1/comment",
                seen -> new FakeAtlassian.Reply(404, "{\"errorMessages\":[\"no\"]}"));

        RawReply reply = client.jiraRequest("POST", "/rest/api/3/issue/PETROCORE-1/comment",
                java.util.Map.of("body", "x"));

        assertEquals(404, reply.status());
        assertFalse(reply.isSuccess());
        assertTrue(reply.body().contains("errorMessages"));
        assertEquals(null, reply.retryAfter());
    }

    @Test
    void aGetWithNoBodySendsNoContentType() {
        fake.onRequest("GET", "/rest/api/3/myself", seen -> new FakeAtlassian.Reply(200, "{\"accountId\":\"a1\"}"));

        RawReply reply = client.jiraRequest("GET", "rest/api/3/myself", null);

        assertEquals(200, reply.status());
        assertEquals("", fake.seen.get(fake.seen.size() - 1).body());
    }

    @Test
    void xrayRequestSendsTheBearerTokenUnderTheApiBase() {
        fake.onRequest("POST", "/api/v2/import/execution",
                seen -> new FakeAtlassian.Reply(200, "{\"key\":\"PETROCORE-9\"}"));

        RawReply reply = client.xrayRequest("POST", "import/execution", "the-token", java.util.Map.of("info", "x"));

        assertTrue(reply.isSuccess());
        assertEquals("{\"key\":\"PETROCORE-9\"}", reply.body());
        FakeAtlassian.Seen seen = fake.seen.get(fake.seen.size() - 1);
        assertEquals("Bearer the-token", seen.authorization());
        assertEquals("{\"info\":\"x\"}", seen.body());
    }

    @Test
    void xrayRequestReturnsA400AndTheRetryAfterHeaderUnjudged() {
        fake.onRequest("POST", "/api/v2/import/execution",
                seen -> new FakeAtlassian.Reply(429, "slow down"));

        RawReply reply = client.xrayRequest("POST", "/import/execution", "t", java.util.Map.of());

        assertEquals(429, reply.status());
        assertEquals("slow down", reply.body());
    }

    @Test
    void xrayAuthenticateReturnsTheTokenAndSendsTheClientPair() {
        fake.onRequest("POST", "/api/v2/authenticate",
                seen -> new FakeAtlassian.Reply(200, "\"tok-123\""));

        assertEquals("tok-123", client.xrayAuthenticate());
        FakeAtlassian.Seen seen = fake.seen.get(fake.seen.size() - 1);
        assertTrue(seen.body().contains("\"client_id\":\"xray-id\""));
        assertTrue(seen.body().contains("\"client_secret\":\"xray-secret-value\""));
    }

    @Test
    void xrayAuthenticateRefusedNamesTheStatusAndNeverTheSecret() {
        fake.onRequest("POST", "/api/v2/authenticate",
                seen -> new FakeAtlassian.Reply(401, "bad xray-secret-value"));

        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class, client::xrayAuthenticate);

        assertTrue(thrown.getMessage().contains("HTTP 401"));
        assertFalse(thrown.getMessage().contains("xray-secret-value"));
    }

    @Test
    void aJiraRequestWithNoCredentialsFailsBeforeAnyRequest() {
        AtlassianClient noJira = new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"),
                () -> {
                    throw new AtlassianUnavailable("no credentials — run the 'jira_api_token' setup step");
                },
                () -> XRAY, Duration.ofSeconds(5));
        int before = fake.seen.size();

        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> noJira.jiraRequest("GET", "/rest/api/3/myself", null));

        assertTrue(thrown.getMessage().contains("jira_api_token"));
        assertEquals(before, fake.seen.size());
    }
}
