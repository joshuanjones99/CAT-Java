package com.verifone.cat.pats.runner.handlers.http;

import java.util.Optional;

import com.verifone.cat.pats.runner.PatsStepError;

/**
 * The files a case's HTTP steps read: the request payloads, the reference payloads, and the payload rule files. The
 * case reader (the Python's {@code CaseStore} and {@code Case.reference}) stays behind this seam, as the runner's
 * other handlers keep theirs.
 *
 * <p>Each lookup covers PATS's folder rules: a case's own copy first, then the shared folder (rules).
 */
public interface CaseFiles {

    /**
     * The bytes of the HTTP request payload {@code name} (PATS's {@code Payloads\HTTP}): the case's own copy, else
     * one identical copy another case of the same suite holds (PATS copies every case's payloads into one folder).
     *
     * @throws PatsStepError when no copy exists, or the copies in other cases differ, naming which
     */
    byte[] httpPayload(String name);

    /** The reference payload {@code name} the case holds (its {@code refPayload}), or empty. */
    Optional<byte[]> referencePayload(String name);

    /** The payload rule file {@code name}: the case's own copy, else the shared {@code Payloads} rules; empty if neither. */
    Optional<byte[]> payloadRule(String name);
}
