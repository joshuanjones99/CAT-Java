package com.verifone.cat.pats.runner.handlers.configuration;

import com.verifone.cat.devices.DeviceUnavailable;
import com.verifone.cat.pats.runner.CaseRun;

import java.util.Map;
import java.util.Optional;

/**
 * The C-Site cloud request a {@code CLOUD_HTTP_REQUEST} makes, through the client {@code SEND_HTTP_REQUEST}
 * uses (the Python's {@code petrotest.devices.csite}), with the cloud settle wait around it (the Python's
 * {@code cloud_settle.mark}, {@code release} and {@code wait_applied}). Neither the C-Site client nor the
 * settle family is ported yet, so the production binding is {@link #UNBOUND} until they are.
 */
public interface CloudSender {

    /**
     * The service ID the Commander's {@code vsupportinfo} names, read once per case (the Python's
     * {@code http._populate_service_id} and its {@code scenario_context}), or empty when none can be read.
     */
    Optional<String> serviceId(CaseRun run) throws Exception;

    /**
     * Sends one request and waits as the cloud settle does. The reply's status is returned; a status is never
     * a failure in itself.
     *
     * @throws CloudRequestException when the request could not be sent or answered
     */
    int send(CaseRun run, String method, String endpoint, Map<String, String> headers, String body)
            throws CloudRequestException;

    /** Refuses every call: the cloud client is not ported. */
    CloudSender UNBOUND = new CloudSender() {
        @Override
        public Optional<String> serviceId(CaseRun run) {
            throw new DeviceUnavailable("csite", "the C-Site cloud client is not ported to CAT-Java yet");
        }

        @Override
        public int send(CaseRun run, String method, String endpoint, Map<String, String> headers, String body) {
            throw new DeviceUnavailable("csite", "the C-Site cloud client is not ported to CAT-Java yet");
        }
    };
}
