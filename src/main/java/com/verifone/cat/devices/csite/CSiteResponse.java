package com.verifone.cat.devices.csite;

import java.util.Objects;

/**
 * One C-Site answer, whatever its status, as PATS's {@code RequestManager} leaves it: the status, the Content-Type
 * header ({@code null} when the reply has none), and the body (empty when there is no Content-Type).
 *
 * @param status      the HTTP status code
 * @param contentType the Content-Type header, or {@code null}
 * @param body        the body's bytes
 * @param url         the URL requested, for a message
 */
public record CSiteResponse(int status, String contentType, byte[] body, String url) {

    public CSiteResponse {
        Objects.requireNonNull(url, "url");
        body = body == null ? new byte[0] : body.clone();
    }

    /** A copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }
}
