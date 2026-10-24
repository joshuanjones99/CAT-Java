package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.inits.TestActions.act;
import static com.verifone.cat.pats.runner.handlers.inits.TestActions.kase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;
import com.verifone.cat.pats.runner.handlers.inits.TestClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code CLOUD_HTTP_REQUEST} with a recording cloud client, over a {@code @TempDir} petroapps checkout. */
class CloudRequestTest {

    private static final String PAYLOADS = "AutomationTests/configurations/EndPoints/CLOUD/Payload";
    private static final String URL = "https://cloud.example.com/api/{service_id}/charges";

    @TempDir
    Path folder;

    private TestClock clock;
    private RecordingCloud cloud;
    private Path petroapps;

    @BeforeEach
    void setUp() throws IOException {
        clock = new TestClock();
        cloud = new RecordingCloud();
        petroapps = Files.createDirectories(folder.resolve("petroapps"));
    }

    private CaseRun run(RunnableAction action) throws Exception {
        ConfigurationContext context = ConfigFixtures.context(folder, clock, RegisterPos.UNBOUND,
                StuckCloseRecovery.UNBOUND, cloud);
        return ConfigFixtures.caseRun(context, ConfigFixtures.noFixtures(), kase("PETROCORE-70", action), Set.of());
    }

    private static RunnableAction request(String payload, String method, String url) {
        return act(0, "CLOUD_HTTP_REQUEST", "SELECT_PAYLOAD_FILE", payload, "HTTP_METHOD", method,
                "CONFIGURATION_URL", url);
    }

    @Test
    void theRequestCarriesThePayloadTheServiceIdAndTheJsonHeaders() throws Exception {
        ConfigFixtures.write(petroapps.resolve(PAYLOADS).resolve("Charge.json"), "{\"amount\":1}");

        run(request("Charge.json", "POST", URL)).run();

        assertEquals("POST", cloud.method);
        assertEquals("https://cloud.example.com/api/SVC-1/charges", cloud.endpoint);
        assertEquals("{\"amount\":1}", cloud.body);
        Map<String, String> headers = new LinkedHashMap<>(cloud.headers);
        assertEquals("SVC-1", headers.get("X-ServiceId"));
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals("application/json", headers.get("Accept"));
    }

    @Test
    void onlyTheLiteralServiceIdTokenIsReplaced() throws Exception {
        ConfigFixtures.write(petroapps.resolve(PAYLOADS).resolve("Charge.json"), "{}");

        run(request("Charge.json", "POST", "https://cloud.example.com/api/{SERVICEID}/charges")).run();

        assertEquals("https://cloud.example.com/api/{SERVICEID}/charges", cloud.endpoint);
    }

    @Test
    void noServiceIdFailsTheStepBeforeAnyRequest() throws Exception {
        ConfigFixtures.write(petroapps.resolve(PAYLOADS).resolve("Charge.json"), "{}");
        cloud.serviceId = Optional.empty();

        PatsStepError error = assertThrows(PatsStepError.class, run(request("Charge.json", "POST", URL))::run);

        assertTrue(error.getMessage().contains("force-fetching service id"), error.getMessage());
        assertNull(cloud.method);
    }

    @Test
    void aTransportErrorFailsTheStep() throws Exception {
        ConfigFixtures.write(petroapps.resolve(PAYLOADS).resolve("Charge.json"), "{}");
        cloud.transportFails = true;

        PatsStepError error = assertThrows(PatsStepError.class, run(request("Charge.json", "POST", URL))::run);

        assertTrue(error.getMessage().contains("Error occurred while sending request"), error.getMessage());
    }

    @Test
    void aMissingPayloadFailsTheStepNamingIt() throws Exception {
        PatsStepError error = assertThrows(PatsStepError.class,
                run(request("Missing.json", "POST", URL))::run);

        assertTrue(error.getMessage().contains("cloud payload Missing.json"), error.getMessage());
    }

    @Test
    void aPayloadThatIsNotTextFailsTheStep() throws Exception {
        Files.createDirectories(petroapps.resolve(PAYLOADS));
        Files.write(petroapps.resolve(PAYLOADS).resolve("Binary.bin"), new byte[] {(byte) 0xff, (byte) 0xfe});

        PatsStepError error = assertThrows(PatsStepError.class, run(request("Binary.bin", "POST", URL))::run);

        assertTrue(error.getMessage().contains("not UTF-8"), error.getMessage());
    }

    @Test
    void anUnboundCloudClientIsAStepFailureNeverASilentPass() throws Exception {
        ConfigFixtures.write(petroapps.resolve(PAYLOADS).resolve("Charge.json"), "{}");
        ConfigurationContext context = ConfigFixtures.context(folder, clock, RegisterPos.UNBOUND,
                StuckCloseRecovery.UNBOUND, CloudSender.UNBOUND);
        CaseRun run = ConfigFixtures.caseRun(context, ConfigFixtures.noFixtures(),
                kase("PETROCORE-71", request("Charge.json", "POST", URL)), Set.of());

        PatsStepError error = assertThrows(PatsStepError.class, run::run);

        assertInstanceOf(DeviceUnavailable.class, error.getCause());
    }

    /** A cloud client in memory: it records what it was asked to send. */
    static final class RecordingCloud implements CloudSender {
        Optional<String> serviceId = Optional.of("SVC-1");
        boolean transportFails;
        String method;
        String endpoint;
        Map<String, String> headers;
        String body;

        @Override
        public Optional<String> serviceId(CaseRun run) {
            return serviceId;
        }

        @Override
        public int send(CaseRun run, String method, String endpoint, Map<String, String> headers, String body)
                throws CloudRequestException {
            if (transportFails) {
                throw new CloudRequestException("the connection timed out");
            }
            this.method = method;
            this.endpoint = endpoint;
            this.headers = headers;
            this.body = body;
            return 200;
        }
    }
}
