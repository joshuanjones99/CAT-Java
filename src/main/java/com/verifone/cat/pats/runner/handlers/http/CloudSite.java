package com.verifone.cat.pats.runner.handlers.http;

import com.verifone.cat.pats.runner.PatsStepError;

/**
 * The C-Site cloud client: what {@code SEND_HTTP_REQUEST} with {@code device CSITE} sends through. The Python's
 * {@code petrotest.devices.csite} (OpenAM login, the OAuth device grant, the bearer token and its refresh) is not
 * ported to CAT-Java yet, so the orchestrator wires an implementation of this; with none wired the CSITE branch
 * fails its step by name.
 *
 * <p>An implementation sends JSON requests ({@code Content-Type} and {@code Accept} application/json), refreshes an
 * expired token, and retries a transport failure when {@code retry} is set.
 */
public interface CloudSite {

    /** The environment name ({@code dev}, {@code qat}, ...); {@code dev} drops the service prefix from endpoints. */
    String environment();

    /** Whether the endpoint's service prefix ({@code pcms/}) is dropped, as the rig file's settings say. */
    boolean stripServicePrefix();

    /**
     * One request.
     *
     * @param method   the HTTP verb as the table wrote it
     * @param endpoint the path, after the service prefix rule, with no query
     * @param body     the request body, or {@code null} for none
     * @param retry    whether a transport failure is retried
     * @return the answer, whatever its status
     * @throws PatsStepError when the request could not be made or the credentials are missing
     */
    HttpReply send(String method, String endpoint, String body, boolean retry);
}
