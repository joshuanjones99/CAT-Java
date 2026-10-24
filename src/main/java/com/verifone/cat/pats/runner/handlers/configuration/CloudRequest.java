package com.verifone.cat.pats.runner.handlers.configuration;

import static com.verifone.cat.pats.runner.handlers.configuration.ActionFields.require;

import com.verifone.cat.pats.runner.CaseRun;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.RunnableAction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code CLOUD_HTTP_REQUEST}: {@code CloudActionHandler.executeAction}, as the deployed jar does it. The body is the
 * case's {@code CLOUD/Payload/<file>} from petroapps; the headers are JSON {@code Content-Type} and {@code Accept}
 * and the {@code X-ServiceId} of the Commander's {@code vsupportinfo}; the literal {@code {service_id}} in the URL is
 * replaced and nothing else. The reply's status is only logged: the step fails on a transport error, a missing
 * payload or no service ID, never on a status.
 *
 * <p>The send, its settle wait and the service ID live behind {@link CloudSender}. Not ported: the {@code X-Scope}
 * header from the {@code entityId} cloud property, which the lab's PATS does not set (the Python's note).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: no CLOUD_HTTP_REQUEST has been sent from CAT-Java.
 */
final class CloudRequest {

    private static final Logger LOG = LoggerFactory.getLogger("com.verifone.cat.pats.runner");

    /** The cloud payloads, relative to the petroapps checkout ({@code CloudActionHelper.buildPayloadFilePath}). */
    static final String CLOUD_PAYLOADS = "AutomationTests/configurations/EndPoints/CLOUD/Payload";

    /** {@code http._JSON_HEADERS}. */
    static final Map<String, String> JSON_HEADERS = Map.of(
            "Content-Type", "application/json",
            "Accept", "application/json");

    private static final String SERVICE_ID_TOKEN = "{service_id}";

    private final ConfigurationContext context;

    CloudRequest(ConfigurationContext context) {
        this.context = context;
    }

    void request(CaseRun run, RunnableAction action) throws Exception {
        String payloadName = require(action, "SELECT_PAYLOAD_FILE");
        String method = require(action, "HTTP_METHOD");
        String endpoint = require(action, "CONFIGURATION_URL");
        String body = payload(payloadName);
        String serviceId = context.cloud().serviceId(run)
                .orElseThrow(() -> new PatsStepError("Error in force-fetching service id (the Commander's vsupportinfo)"));
        endpoint = endpoint.replace(SERVICE_ID_TOKEN, serviceId);
        Map<String, String> headers = new LinkedHashMap<>(JSON_HEADERS);
        headers.put("X-ServiceId", serviceId);
        int status;
        try {
            status = context.cloud().send(run, method, endpoint, headers, body);
        } catch (CloudRequestException e) {
            throw new PatsStepError("Error occurred while sending request: " + e.getMessage(), e);
        }
        LOG.info("{}: CLOUD_HTTP_REQUEST {} {} answered HTTP {}", run.kase().key(), method, endpoint, status);
    }

    /** The payload file as text: UTF-8, strictly, so a payload that is not text fails the step as PATS's does. */
    private String payload(String name) {
        byte[] bytes;
        try {
            bytes = context.petroapps().shared(CLOUD_PAYLOADS, name);
        } catch (IOException e) {
            throw new PatsStepError("cloud payload " + name + ": " + e.getMessage(), e);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new PatsStepError("cloud payload " + name + ": not UTF-8 text", e);
        }
    }
}
