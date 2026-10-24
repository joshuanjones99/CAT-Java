package com.verifone.cat.atlassian;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.atlassian.AtlassianCredentials.Jira;
import com.verifone.cat.atlassian.AtlassianCredentials.Xray;

/**
 * The HTTP client over a loopback fake: request shape, the error contract and the credential
 * handling. Port of the Python CAT's {@code TestGetJson} in {@code test_cat_atlassian.py}, with
 * the Xray path from {@code xray_graphql}. Offline by construction: nothing here is evidence
 * about Atlassian itself.
 */
class AtlassianClientTest {

    private static final Jira JIRA = new Jira("tester@verifone.com", "not-a-real-token");
    private static final Xray XRAY = new Xray("an-id", "not-a-real-secret");

    private FakeAtlassian fake;

    @BeforeEach
    void start() {
        fake = new FakeAtlassian();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private AtlassianClient client(Jira jira, Xray xray) {
        return new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"),
                () -> jira, () -> xray, Duration.ofSeconds(5));
    }

    @Test
    void aGetSendsBasicAuthAndTheQueryAndReturnsTheDecodedBody() throws Exception {
        fake.on("GET", "/wiki/api/v2/pages/183272339", 200, "{\"id\":\"183272339\"}");
        JsonNode body = client(JIRA, XRAY).getJson("/wiki/api/v2/pages/183272339",
                Map.of("body-format", "storage"));

        assertEquals("183272339", body.get("id").asText());
        FakeAtlassian.Seen seen = fake.seen.get(0);
        assertEquals("body-format=storage", seen.query());
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "tester@verifone.com:not-a-real-token".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, seen.authorization());
    }

    @Test
    void aNon2xxCarriesTheStatusAndATruncatedBodyButNeverTheToken() {
        String body = "denied ".repeat(100);
        fake.on("GET", "/rest/api/3/myself", 401, body);
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> client(JIRA, XRAY).getJson("/rest/api/3/myself", Map.of()));
        assertTrue(thrown.getMessage().contains("401"));
        assertTrue(thrown.getMessage().length() < body.length());
        assertFalse(thrown.getMessage().contains("not-a-real-token"));
    }

    @Test
    void aTransportFailureIsUnavailable() {
        AtlassianClient unreachable = new AtlassianClient(
                java.net.URI.create("http://127.0.0.1:1"), java.net.URI.create("http://127.0.0.1:1/api/v2"),
                () -> JIRA, () -> XRAY, Duration.ofSeconds(2));
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> unreachable.getJson("/rest/api/3/myself", Map.of()));
        assertFalse(thrown.getMessage().contains("not-a-real-token"));
    }

    @Test
    void aNonJsonBodyIsUnavailable() {
        fake.on("GET", "/rest/api/3/myself", 200, "<html>login</html>");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> client(JIRA, XRAY).getJson("/rest/api/3/myself", Map.of()));
        assertTrue(thrown.getMessage().contains("non-JSON"));
    }

    @Test
    void anEmptyBodyIsNotJsonEither() {
        fake.on("GET", "/rest/api/3/myself", 200, "");
        assertThrows(AtlassianUnavailable.class,
                () -> client(JIRA, XRAY).getJson("/rest/api/3/myself", Map.of()));
    }

    @Test
    void aRedirectIsReportedNotFollowedWithTheTokenAttached() {
        fake.on("GET", "/rest/api/3/myself", 302, "");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> client(JIRA, XRAY).getJson("/rest/api/3/myself", Map.of()));
        assertTrue(thrown.getMessage().contains("302"));
    }

    @Test
    void absentCredentialsFailBeforeAnyRequestIsMade() {
        AtlassianClient noCredentials = new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"),
                () -> { throw new AtlassianUnavailable("no credentials — run the 'jira_api_token' setup step"); },
                () -> XRAY, Duration.ofSeconds(5));
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> noCredentials.getJson("/rest/api/3/myself", Map.of()));
        assertTrue(thrown.getMessage().contains("jira_api_token"));
        assertEquals(0, fake.seen.size());
    }

    @Test
    void xrayAuthenticatesThenSendsTheQueryWithTheBearerToken() throws Exception {
        fake.on("POST", "/api/v2/authenticate", 200, "\"a-token\"");
        fake.on("POST", "/api/v2/graphql", 200, "{\"data\":{\"ok\":true}}");
        JsonNode data = client(JIRA, XRAY).xrayGraphql("query { ok }", Map.of("start", 0));

        assertTrue(data.get("ok").asBoolean());
        FakeAtlassian.Seen auth = fake.seen.get(0);
        JsonNode authBody = new ObjectMapper().readTree(auth.body());
        assertEquals("an-id", authBody.get("client_id").asText());
        assertEquals("not-a-real-secret", authBody.get("client_secret").asText());
        assertEquals(null, auth.authorization());
        assertEquals("Bearer a-token", fake.seen.get(1).authorization());
        JsonNode graph = new ObjectMapper().readTree(fake.seen.get(1).body());
        assertEquals(0, graph.get("variables").get("start").asInt());
    }

    @Test
    void xrayGraphqlErrorsAreUnavailableNotAnEmptyPlan() {
        fake.on("POST", "/api/v2/authenticate", 200, "\"a-token\"");
        fake.on("POST", "/api/v2/graphql", 200, "{\"errors\":[{\"message\":\"bad query\"}]}");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> client(JIRA, XRAY).xrayGraphql("query", Map.of()));
        assertTrue(thrown.getMessage().contains("bad query"));
    }

    @Test
    void aRejectedXrayAuthenticateNamesTheStatusOnly() {
        fake.on("POST", "/api/v2/authenticate", 401, "{\"secret\":\"echoed\"}");
        AtlassianUnavailable thrown = assertThrows(AtlassianUnavailable.class,
                () -> client(JIRA, XRAY).xrayGraphql("query", Map.of()));
        assertEquals("Xray authenticate -> HTTP 401", thrown.getMessage());
    }

    @Test
    void absentXrayCredentialsFailBeforeAnyRequestIsMade() {
        AtlassianClient noXray = new AtlassianClient(fake.base(), fake.base().resolve("/api/v2"),
                () -> JIRA, () -> { throw new AtlassianUnavailable("no xray pair"); }, Duration.ofSeconds(5));
        assertThrows(AtlassianUnavailable.class, () -> noXray.xrayGraphql("query", Map.of()));
        assertEquals(0, fake.seen.size());
    }
}
