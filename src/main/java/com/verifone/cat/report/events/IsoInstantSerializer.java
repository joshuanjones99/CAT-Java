package com.verifone.cat.report.events;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * Writes an {@link Instant} as ISO 8601 UTC text, {@code 2026-08-04T10:00:00Z}, as pydantic does.
 *
 * <p>Jackson without its java.time module would write a number, and this port has no module
 * dependency, so the timestamps are written here.
 */
public final class IsoInstantSerializer extends JsonSerializer<Instant> {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ISO_INSTANT;

    @Override
    public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeString(FORMAT.format(value));
    }

    /** The text a timestamp is written as, for the envelope and for run.ended. */
    public static String format(Instant value) {
        return FORMAT.format(value);
    }
}
