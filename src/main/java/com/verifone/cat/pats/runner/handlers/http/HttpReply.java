package com.verifone.cat.pats.runner.handlers.http;

/**
 * An HTTP answer as the PATS steps read it: the status, the Content-Type header (null when the reply has none), and
 * the body (empty when there is no Content-Type, as PATS's {@code RequestManager} leaves it).
 *
 * @param status      the HTTP status code
 * @param contentType the Content-Type header, or {@code null}
 * @param body        the body's bytes
 */
public record HttpReply(int status, String contentType, byte[] body) {

    public HttpReply {
        body = body == null ? new byte[0] : body;
    }
}
