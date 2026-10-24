package com.verifone.cat.devices.csite;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.pats.runner.PatsStepError;
import com.verifone.cat.pats.runner.handlers.http.CloudSite;
import com.verifone.cat.pats.runner.handlers.http.HttpReply;

import java.util.Map;
import java.util.Objects;

/**
 * The HTTP family's {@link CloudSite} seam, implemented over {@link CSiteClient}: what {@code SEND_HTTP_REQUEST} with
 * {@code device CSITE} sends through.
 *
 * <p>Every request carries JSON {@code Accept} and {@code Content-Type} headers, as the seam's contract has it. A
 * device failure ({@link DeviceError} and its subclasses) becomes a {@link PatsStepError} naming what failed, which is
 * how the step fails by name; the message never carries the password or the token.
 *
 * <p>The environment and endpoints come from {@link CSiteSettings}: the caller passes the Commander's cloud settings
 * ({@code app}), since the Python's SSH read of them is not ported.
 */
public final class CSiteCloud implements CloudSite {

    private static final Map<String, String> JSON_HEADERS = Map.of(
            "Accept", "application/json", "Content-Type", "application/json");

    private final CSiteClient client;

    /** An adapter over {@code client}. */
    public CSiteCloud(CSiteClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * An adapter over the settings read from {@code environ} and the Commander's endpoint settings {@code app}.
     *
     * @throws DeviceError when the settings name a mode or setting this port refuses
     */
    public static CSiteCloud of(Map<String, String> environ, Map<String, String> app) {
        return new CSiteCloud(CSiteClient.of(CSiteSettings.fromEnvironment(environ, app)));
    }

    @Override
    public String environment() {
        return client.settings().env();
    }

    @Override
    public boolean stripServicePrefix() {
        return client.settings().stripServicePrefix();
    }

    @Override
    public HttpReply send(String method, String endpoint, String body, boolean retry) {
        try {
            CSiteResponse answer = client.send(method, endpoint, JSON_HEADERS, body, retry);
            return new HttpReply(answer.status(), answer.contentType(), answer.body());
        } catch (DeviceError e) {
            throw new PatsStepError(e.getMessage(), e);
        }
    }
}
