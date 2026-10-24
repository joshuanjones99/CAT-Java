package com.verifone.cat.devices.csite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.handlers.http.CloudSite;
import com.verifone.cat.pats.runner.handlers.http.HttpReply;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link CSiteCloud}: the HTTP family's CloudSite seam over the C-Site client, against the loopback stand-in. */
class CSiteCloudTest {

    private CSiteFake fake;
    private Map<String, String> app;

    @BeforeEach
    void setUp() throws IOException {
        fake = new CSiteFake();
        CSiteClientTest.loginRoutes(fake);
        app = new HashMap<>(Map.of(
                "profile", "qat",
                "csite.base.url.qat", fake.url() + "/oidc",
                "cloud.auth.id.url.qat", fake.url() + "/am/json/authenticate",
                "cloud.access.token.url.qat", fake.url() + "/am/oauth2/access_token"));
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void aRequestGoesThroughTheClientWithJsonHeadersAndComesBackAsAReply() {
        fake.route("POST", "/oidc/pcms/v1/sites/1/ctrlcmd/cfuelinit",
                CSiteFake.Reply.json(200, "{\"transactionId\": \"T9\"}"));
        CloudSite cloud = CSiteCloud.of(CSiteClientTest.environ("tester@example.invalid", "not-a-real-one"), app);

        HttpReply reply = cloud.send("POST", "pcms/v1/sites/1/ctrlcmd/cfuelinit", "{\"site\": \"1\"}", true);

        assertEquals(200, reply.status());
        assertEquals("application/json", reply.contentType());
        assertEquals("{\"transactionId\": \"T9\"}", new String(reply.body(), java.nio.charset.StandardCharsets.UTF_8));
        CSiteFake.Seen sent = fake.seen("POST", "/oidc/pcms/v1/sites/1/ctrlcmd/cfuelinit").get(0);
        assertEquals("application/json", sent.headers().get("accept"));
        assertEquals("application/json", sent.headers().get("content-type"));
        assertEquals("Bearer t1", sent.headers().get("authorization"));
        assertEquals("{\"site\": \"1\"}", sent.body());
    }

    @Test
    void theEnvironmentAndThePrefixRuleComeFromTheSettings() {
        app.put("csite.base.url", "unused");
        CSiteCloud cloud = CSiteCloud.of(
                Map.of("CAT_CSITE_ENV", "dev", "CAT_CSITE_STRIP_SERVICE_PREFIX", "true",
                        "CAT_CSITE_USERNAME", "u", "CAT_CSITE_PASSWORD", "p"),
                app);

        assertEquals("dev", cloud.environment());
        assertTrue(cloud.stripServicePrefix());
    }

    @Test
    void aTransportFailureFailsTheStepByName() throws IOException {
        app.put("csite.base.url.qat", CSiteFake.closedUrl() + "/oidc");
        CloudSite cloud = CSiteCloud.of(CSiteClientTest.environ("tester@example.invalid", "not-a-real-one"), app);

        PatsStepError error = assertThrows(PatsStepError.class,
                () -> cloud.send("GET", "pcms/v1/config/1/tips", null, false));

        assertTrue(error.getMessage().contains("unreachable"), error.getMessage());
        assertFalse(error.getMessage().contains("not-a-real-one"), error.getMessage());
    }

    @Test
    void aMissingLoginFailsTheStepNamingTheVariable() {
        CloudSite cloud = CSiteCloud.of(CSiteClientTest.environ("", ""), app);

        PatsStepError error = assertThrows(PatsStepError.class,
                () -> cloud.send("GET", "pcms/v1/config/1/tips", null, true));
        assertTrue(error.getMessage().contains("CAT_CSITE_USERNAME"), error.getMessage());
    }

    @Test
    void aRefusedSettingFailsWhenTheAdapterIsBuilt() {
        DeviceError error = assertThrows(DeviceError.class, () -> CSiteCloud.of(
                Map.of("CAT_CSITE_DISABLE_SSL_VERIFICATION", "true"), app));
        assertTrue(error.getMessage().contains("not ported"), error.getMessage());
    }

    @Test
    void theAdapterWrapsAnyClientForTheTestsHttpClient() {
        CSiteCloud cloud = new CSiteCloud(new CSiteClient(
                CSiteSettings.fromEnvironment(CSiteClientTest.environ("u", "p"), app),
                HttpClient.newHttpClient(), ignored -> { }));
        assertEquals("qat", cloud.environment());
    }
}
