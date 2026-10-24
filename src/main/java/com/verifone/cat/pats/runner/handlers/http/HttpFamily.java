package com.verifone.cat.pats.runner.handlers.http;

import java.util.Objects;

import com.verifone.cat.pats.runner.ActionRegistry;

/**
 * The HTTP family's registration: PATS's BDD-only {@code SEND_HTTP_REQUEST} and {@code VALIDATE_PAYLOAD}. The
 * configuration family's {@code CLOUD_HTTP_REQUEST} is not here (the Python keeps it in {@code configuration.py}).
 */
public final class HttpFamily {

    private HttpFamily() {
    }

    /**
     * Registers the HTTP family's handlers.
     *
     * @throws IllegalStateException when one of the names already has a handler
     */
    public static void register(ActionRegistry registry, HttpContext context) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        registry.register("SEND_HTTP_REQUEST", new SendHttpRequest(context));
        registry.register("VALIDATE_PAYLOAD", new ValidatePayload(context));
    }
}
