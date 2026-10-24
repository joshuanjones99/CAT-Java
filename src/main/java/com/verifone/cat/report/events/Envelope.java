package com.verifone.cat.report.events;

import java.time.Instant;

/**
 * The four fields every event line carries beside its own: the Python CAT's {@code BaseEvent}.
 *
 * <p>{@code v} is the schema version (1). {@code at} is UTC; {@code seq} orders events from one
 * emitter; {@code worker} is the xdist worker name, or {@code session} for the main process.
 */
public record Envelope(int v, Instant at, long seq, String worker) {

    /** The schema version every line is written with. */
    public static final int SCHEMA_VERSION = 1;

    public Envelope {
        if (at == null) {
            throw new IllegalArgumentException("at is required");
        }
        if (worker == null) {
            throw new IllegalArgumentException("worker is required");
        }
    }
}
