package com.verifone.cat.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * The one default-configured JSON mapper. An {@link ObjectMapper} is thread-safe once configured and costly to
 * build (each holds its own serializer caches), so every class that needs plain JSON shares this one.
 *
 * <p><b>Never configure {@link #MAPPER}</b> (no {@code enable}, {@code configure}, {@code registerModule},
 * {@code setPropertyNamingStrategy}): that would change every user. A class that needs different settings builds
 * its own with {@code Json.MAPPER.copy()...} or asks for a writer ({@link #PRETTY}).
 */
public final class Json {

    /** Jackson's defaults, shared. Read-only by convention; see the class comment. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@link #MAPPER} writing with Jackson's default indentation ({@code INDENT_OUTPUT}). */
    public static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private Json() {}
}
