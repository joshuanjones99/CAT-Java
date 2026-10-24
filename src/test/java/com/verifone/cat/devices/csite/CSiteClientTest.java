package com.verifone.cat.devices.csite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;
import com.verifone.cat.devices.DeviceUnreachable;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CSiteClient} against a loopback stand-in, ported from the Python {@code tests/unit/test_pats_http.py}'s CSITE
 * cases and the {@code petrotest.devices.csite} behaviour. A fake-backed pass is evidence about this repository, not
 * about the cloud.
 */
class CSiteClientTest {

    private static final String USER = "tester@example.invalid";
    private static final String PASSWORD = "not-a-real-one";
    private static final String JSON_HEADERS_CONTENT = "application/json";
    private static final Map<String, String> JSON = Map.of("Accept", JSON_HEADERS_CONTENT,
            "Content-Type", JSON_HEADERS_CONTENT);
    private static final String CFUEL = "/oidc/pcms/v1/sites/1234567/ctrlcmd/cfuelinit";

    private CSiteFake fake;
    private List<Duration> pauses;

    @BeforeEach
    void setUp() throws IOException {
        fake = new CSiteFake();
        pauses = new ArrayList<>();
        loginRoutes(fake);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    /** OpenAM, the device grant and the token, answering as a healthy cloud does; the token changes on each grant. */
    static void loginRoutes(CSiteFake fake) {
        fake.route("POST", "/am/json/authenticate", CSiteFake.Reply.json(200, "{\"tokenId\": \"S\"}"));
        fake.route("POST", "/am/oauth2/device/code",
                CSiteFake.Reply.json(200, "{\"device_code\": \"D\", \"user_code\": \"U\"}"));
        fake.route("POST", "/am/oauth2/device/user",
                new CSiteFake.Reply(200, "text/html", "Device authorized", Map.of()));
        fake.route("POST", "/am/oauth2/access_token",
                CSiteFake.Reply.json(200, "{\"access_token\": \"t1\", \"token_type\": \"Bearer\"}"),
                CSiteFake.Reply.json(200, "{\"access_token\": \"t2\", \"token_type\": \"Bearer\"}"),
                CSiteFake.Reply.json(200, "{\"access_token\": \"t3\", \"token_type\": \"Bearer\"}"));
    }

    /** The Commander's endpoint settings for the stand-in, with C-Site's API at {@code apiBase}. */
    Map<String, String> app(String apiBase) {
        return Map.of(
                "profile", "qat",
                "csite.base.url.qat", apiBase,
                "cloud.auth.id.url.qat", fake.url() + "/am/json/authenticate",
                "cloud.access.token.url.qat", fake.url() + "/am/oauth2/access_token");
    }

    static Map<String, String> environ(String user, String password) {
        Map<String, String> environ = new HashMap<>();
        environ.put("CAT_CSITE_ENV", "qat");
        environ.put("CAT_CSITE_USERNAME", user);
        environ.put("CAT_CSITE_PASSWORD", password);
        return environ;
    }

    CSiteClient client(String apiBase) {
        return client(apiBase, environ(USER, PASSWORD), Map.of());
    }

    CSiteClient client(String apiBase, Map<String, String> environ, Map<String, String> extra) {
        Map<String, String> app = new HashMap<>(app(apiBase));
        app.putAll(extra);
        return new CSiteClient(CSiteSettings.fromEnvironment(environ, app), HttpClient.newHttpClient(), pauses::add);
    }

    // -- token and refresh -------------------------------------------------------------------

    @Test
    void theTokenIsFetchedThroughOpenAMAndTheDeviceGrantAndRefreshedOn401() {
        fake.route("POST", CFUEL,
                CSiteFake.Reply.of(401),                                        // an expired token
                CSiteFake.Reply.json(200, "{\"transactionId\": \"T9\"}"));
        CSiteClient client = client(fake.url() + "/oidc");

        CSiteResponse answer = client.send("POST", "pcms/v1/sites/1234567/ctrlcmd/cfuelinit", JSON,
                "{\"site\": \"1234567\"}", true);

        assertEquals(200, answer.status());
        List<String> sent = fake.seen("POST", CFUEL).stream().map(s -> s.headers().get("authorization")).toList();
        assertEquals(List.of("Bearer t1", "Bearer t2"), sent);
        assertEquals("{\"site\": \"1234567\"}", fake.seen("POST", CFUEL).get(0).body());
        CSiteFake.Seen login = fake.seen("POST", "/am/json/authenticate").get(0);
        assertEquals(USER, login.headers().get("x-openam-username"));
        String approval = fake.seen("POST", "/am/oauth2/device/user").get(0).body();
        assertTrue(approval.contains("user_code=U") && approval.contains("decision=allow")
                && approval.contains("csrf=S"), approval);
        assertEquals(List.of(CSiteClient.RETRY_PAUSE), pauses);
    }

    @Test
    void theTokenIsKeptBetweenRequests() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", CSiteFake.Reply.json(200, "{}"));
        CSiteClient client = client(fake.url() + "/oidc");

        client.send("GET", "pcms/v1/config/1/tips", JSON, null, true);
        client.send("GET", "pcms/v1/config/1/tips", JSON, null, true);

        assertEquals(1, fake.seen("POST", "/am/oauth2/access_token").size());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void aRefusedOpenAMLoginIsReportedWithoutThePassword() {
        fake.route("POST", "/am/json/authenticate", CSiteFake.Reply.of(401));
        CSiteClient client = client(fake.url() + "/oidc");

        DeviceError error = assertThrows(DeviceError.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, true));

        assertTrue(error.getMessage().contains("OpenAM authenticate answered HTTP 401"), error.getMessage());
        assertFalse(error.getMessage().contains(PASSWORD), error.getMessage());
    }

    @Test
    void aMissingPasswordNamesTheVariableAndNotTheLogin() {
        CSiteClient client = client(fake.url() + "/oidc", environ(USER, ""), Map.of());

        DeviceError error = assertThrows(DeviceError.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, true));

        assertTrue(error.getMessage().contains("CAT_CSITE_PASSWORD"), error.getMessage());
        assertTrue(fake.seen().isEmpty(), "no request leaves without credentials");
    }

    // -- retries -----------------------------------------------------------------------------

    @Test
    void aRetryableStatusIsTriedAgainAfterThePause() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips",
                CSiteFake.Reply.of(503), CSiteFake.Reply.json(200, "{\"ok\": true}"));
        CSiteClient client = client(fake.url() + "/oidc");

        CSiteResponse answer = client.send("GET", "pcms/v1/config/1/tips", JSON, null, true);

        assertEquals(200, answer.status());
        assertEquals(2, fake.seen("GET", "/oidc/pcms/v1/config/1/tips").size());
        assertEquals(List.of(CSiteClient.RETRY_PAUSE), pauses);
    }

    @Test
    void withoutRetryTheRetryableStatusIsTheAnswer() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", CSiteFake.Reply.of(503));
        CSiteClient client = client(fake.url() + "/oidc");

        CSiteResponse answer = client.send("GET", "pcms/v1/config/1/tips", JSON, null, false);

        assertEquals(503, answer.status());
        assertEquals(1, fake.seen("GET", "/oidc/pcms/v1/config/1/tips").size());
        assertTrue(pauses.isEmpty());
    }

    @Test
    void aClientErrorIsAnAnswerNotARetry() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", CSiteFake.Reply.json(404, "{\"missing\": true}"));
        CSiteClient client = client(fake.url() + "/oidc");

        CSiteResponse answer = client.send("GET", "pcms/v1/config/1/tips", JSON, null, true);

        assertEquals(404, answer.status());
        assertEquals("{\"missing\": true}", new String(answer.body(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, fake.seen("GET", "/oidc/pcms/v1/config/1/tips").size());
    }

    @Test
    void aRetryableStatusOnTheLastAttemptEndsTheLoopAsPATSDoes() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", CSiteFake.Reply.of(503));
        CSiteClient client = client(fake.url() + "/oidc");

        DeviceProtocolError error = assertThrows(DeviceProtocolError.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, true));

        assertEquals("C-Site: retry loop terminated unexpectedly", error.getMessage());
        assertEquals(3, fake.seen("GET", "/oidc/pcms/v1/config/1/tips").size());
        assertEquals(2, pauses.size());
    }

    @Test
    void aTransportFailureIsUnreachableAfterItsAttempts() throws IOException {
        CSiteClient client = client(CSiteFake.closedUrl() + "/oidc");

        assertThrows(DeviceUnreachable.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, true));
        assertEquals(2, pauses.size());
    }

    @Test
    void aTransportFailureWithoutRetryIsNotPaused() throws IOException {
        CSiteClient client = client(CSiteFake.closedUrl() + "/oidc");

        assertThrows(DeviceUnreachable.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, false));
        assertTrue(pauses.isEmpty());
    }

    @Test
    void theAttemptsComeFromCloudRetry() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", CSiteFake.Reply.of(429));
        CSiteClient client = client(fake.url() + "/oidc", environ(USER, PASSWORD), Map.of("cloudRetry", "5"));

        assertThrows(DeviceProtocolError.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, true));
        assertEquals(5, fake.seen("GET", "/oidc/pcms/v1/config/1/tips").size());
    }

    // -- answers -----------------------------------------------------------------------------

    @Test
    void aFileAnswerIsRefusedNotGuessed() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", new CSiteFake.Reply(200, "application/pdf", "%PDF",
                Map.of("Content-Disposition", "attachment; filename=receipt.pdf")));
        CSiteClient client = client(fake.url() + "/oidc");

        DeviceProtocolError error = assertThrows(DeviceProtocolError.class,
                () -> client.send("GET", "pcms/v1/config/1/tips", JSON, null, true));

        assertTrue(error.getMessage().contains("file responses are not ported"), error.getMessage());
    }

    @Test
    void aReplyWithNoContentTypeHasAnEmptyBody() {
        fake.route("DELETE", "/oidc/pcms/v1/config/1/tips/9", new CSiteFake.Reply(204, null, "", Map.of()));
        CSiteClient client = client(fake.url() + "/oidc");

        CSiteResponse answer = client.send("DELETE", "pcms/v1/config/1/tips/9", JSON, null, true);

        assertEquals(204, answer.status());
        assertNull(answer.contentType());
        assertEquals(0, answer.body().length);
    }

    @Test
    void aGetSendsNoBodyEvenWhenOneIsGiven() {
        fake.route("GET", "/oidc/pcms/v1/config/1/tips", CSiteFake.Reply.json(200, "{}"));
        CSiteClient client = client(fake.url() + "/oidc");

        client.send("GET", "pcms/v1/config/1/tips", JSON, "ignored", true);

        assertEquals("", fake.seen("GET", "/oidc/pcms/v1/config/1/tips").get(0).body());
    }

    // -- URLs --------------------------------------------------------------------------------

    @Test
    void theFinalUrlKeepsTheBasePathAndQuotesTheEndpointAsPythonDoes() {
        String base = "https://gsc.example.invalid/oidc";
        assertEquals("https://gsc.example.invalid/oidc/pcms/v1/sites/1/ctrlcmd/cfuelinit",
                CSiteClient.finalUrl(base, "pcms/v1/sites/1/ctrlcmd/cfuelinit"));
        assertEquals("https://gsc.example.invalid/oidc/v1/x",
                CSiteClient.finalUrl(base, "/v1/x"));
        assertEquals("https://gsc.example.invalid/oidc/a%20b/%C3%A9%3F",
                CSiteClient.finalUrl(base, "a b/é?"));
    }

    @Test
    void anEmptyEndpointIsRefused() {
        DeviceError error = assertThrows(DeviceError.class,
                () -> CSiteClient.finalUrl("https://gsc.example.invalid/oidc", "  "));
        assertEquals("Endpoint cannot be empty", error.getMessage());
    }

    // -- settings ----------------------------------------------------------------------------

    @Test
    void theLegacyPkceFlowAndBasicAuthAreRefusedNotGuessed() {
        DeviceError legacy = assertThrows(DeviceError.class, () -> CSiteSettings.fromEnvironment(
                Map.of("CAT_CSITE_AUTH_MODE", "legacy"), app(fake.url())));
        assertTrue(legacy.getMessage().contains("PKCE"), legacy.getMessage());

        DeviceError basic = assertThrows(DeviceError.class, () -> CSiteSettings.fromEnvironment(
                Map.of("CAT_CSITE_AUTH_TYPE", "basic"), app(fake.url())));
        assertTrue(basic.getMessage().contains("basic"), basic.getMessage());
    }

    @Test
    void disablingSSLVerificationIsRefusedNotHonouredSilently() {
        DeviceError error = assertThrows(DeviceError.class, () -> CSiteSettings.fromEnvironment(
                Map.of("CAT_CSITE_DISABLE_SSL_VERIFICATION", "true"), app(fake.url())));
        assertTrue(error.getMessage().contains("always checked"), error.getMessage());
    }

    @Test
    void theEnvironmentDefaultsToTheCommandersLabAndTheBaseUrlFallsBack() {
        Map<String, String> app = Map.of("profile", "qat", "csite.base.url", " https://gsc.example.invalid/oidc ");

        CSiteSettings settings = CSiteSettings.fromEnvironment(Map.of(), app);

        assertEquals("qat", settings.env());
        assertEquals("https://gsc.example.invalid/oidc", settings.baseUrl());
        assertEquals(CSiteSettings.DEFAULT_ATTEMPTS, settings.attempts());
        assertEquals(CSiteSettings.DEFAULT_READ_TIMEOUT_MS, settings.readTimeoutMillis());
    }

    @Test
    void theSettingsReadTheirRetryAndTimeoutFromTheCommander() {
        Map<String, String> app = Map.of("profile", "qat", "csite.base.url", "https://gsc.example.invalid/oidc",
                "cloudRetry", "4", "cloud.http.read.timeout", "2500");

        CSiteSettings settings = CSiteSettings.fromEnvironment(Map.of("CAT_CSITE_STRIP_SERVICE_PREFIX", "TRUE"), app);

        assertEquals(4, settings.attempts());
        assertEquals(2500L, settings.readTimeoutMillis());
        assertTrue(settings.stripServicePrefix());
    }

    @Test
    void aBaseUrlNamingNoLabFailsNamingTheCommander() {
        CSiteSettings settings = CSiteSettings.fromEnvironment(Map.of("CAT_CSITE_ENV", "dev"),
                Map.of("profile", "prod"));

        DeviceError error = assertThrows(DeviceError.class, settings::baseUrl);
        assertTrue(error.getMessage().contains("onboarded to prod"), error.getMessage());
    }

    @Test
    void theSettingsNeverPrintTheirCredentials() {
        CSiteSettings settings = CSiteSettings.fromEnvironment(environ(USER, PASSWORD), app(fake.url()));

        assertFalse(settings.toString().contains(PASSWORD));
        assertFalse(settings.toString().contains(USER));
        assertDoesNotThrow(settings::toString);
    }

    @Test
    void theAccountBecomesTheCSiteVariablesOnlyWhenNoUserIsSet() {
        Map<String, String> env = CSiteClient.accountEnv(" QAT ", " tester@example.invalid ", PASSWORD, Map.of());
        assertEquals(Map.of("CAT_CSITE_ENV", "qat", "CAT_CSITE_USERNAME", "tester@example.invalid",
                "CAT_CSITE_PASSWORD", PASSWORD), env);

        assertTrue(CSiteClient.accountEnv("QAT", USER, PASSWORD, Map.of("CAT_CSITE_USERNAME", "someone")).isEmpty());
        assertTrue(CSiteClient.accountEnv("QAT", "", PASSWORD, Map.of()).isEmpty());
        assertTrue(CSiteClient.accountEnv("QAT", USER, "", Map.of()).isEmpty());
    }
}
