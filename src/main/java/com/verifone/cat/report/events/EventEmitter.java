package com.verifone.cat.report.events;

import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.report.NdjsonWriter;

/**
 * Stamps each {@link CatEvent} with its envelope and writes it as one line of {@code events.ndjson}.
 *
 * <p>The Python CAT's {@code cat.telemetry.sink._ndjson_line} and {@code FileTransport}: the line is
 * the envelope ({@code v}, {@code at}, {@code seq}, {@code worker}), the record's own fields, and a
 * {@code type} that names the record. {@code seq} counts up from 0 for this emitter, so events from
 * one emitter order by it. The writer flushes each line ({@link NdjsonWriter}), so a reader sees
 * every event written so far.
 *
 * <p>Only the file transport is here. The Python CAT's socket transport to the controller's event
 * server, and its drop counter, are not ported.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class EventEmitter implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NdjsonWriter writer;
    private final String worker;
    private final Clock clock;
    private long seq;

    /** An emitter stamped with the system UTC clock. */
    public EventEmitter(NdjsonWriter writer, String worker) {
        this(writer, worker, Clock.systemUTC());
    }

    /**
     * @param writer the file the lines go to; this emitter does not own it until {@link #close()}
     * @param worker the envelope's {@code worker}: {@code session}, or an xdist worker name
     * @param clock  where {@code at} comes from; tests pass a fixed clock
     */
    public EventEmitter(NdjsonWriter writer, String worker, Clock clock) {
        if (worker == null || worker.isBlank()) {
            throw new IllegalArgumentException("worker is required");
        }
        this.writer = writer;
        this.worker = worker;
        this.clock = clock;
    }

    /**
     * Stamps and writes {@code event}, and returns the envelope it was written with, so a caller
     * handing the same event to another consumer (the run log) uses the same {@code at}.
     *
     * @throws IOException when the line cannot be written
     */
    public synchronized Envelope emit(CatEvent event) throws IOException {
        Envelope envelope = new Envelope(Envelope.SCHEMA_VERSION, clock.instant(), seq++, worker);
        writer.write(toLine(event, envelope));
        return envelope;
    }

    /**
     * The line an event is written as: {@code type}, the envelope, then the record's fields. Public
     * so a test can check the wire shape without a file.
     */
    public static ObjectNode toLine(CatEvent event, Envelope envelope) {
        ObjectNode line = MAPPER.createObjectNode();
        line.put("type", event.type().wire());
        line.put("v", envelope.v());
        line.put("at", IsoInstantSerializer.format(envelope.at()));
        line.put("seq", envelope.seq());
        line.put("worker", envelope.worker());
        ObjectNode body = MAPPER.valueToTree(event);
        line.setAll(body);
        return line;
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
