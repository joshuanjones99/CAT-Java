package com.verifone.cat.report;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Appends events to a run's {@code events.ndjson}: one JSON object per line, flushed after
 * every line.
 *
 * <p>The Python CAT writes this stream from {@code cat.report.events} (a socket fallback
 * and the launcher's tee), and the reports read it back. The format is the contract: a
 * line is a complete JSON object, so a reader that opens the file mid-run, or after a
 * crash, sees every event written so far and nothing half-written. The flush is what makes
 * that true, and it is why this writer is not buffered across events.
 *
 * <p>Append-only: an existing file is extended, never truncated, so a second process that
 * tees into the same run keeps the first one's events.
 *
 * <p>Serialisation is Jackson's, which writes non-ASCII as UTF-8 where Python's
 * {@code json.dumps} escapes it. Both are valid JSON and both readers parse either.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed only; no run has written through it yet.
 */
public final class NdjsonWriter implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Writer out;

    /**
     * Opens {@code file} for appending, creating it and its parent folders when absent.
     *
     * @throws IOException when the file cannot be created or opened
     */
    public NdjsonWriter(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.out = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    /**
     * Writes {@code event} as one line and flushes it. Callers supply the envelope fields
     * the Python CAT's {@code BaseEvent} carries ({@code v}, {@code at}, {@code seq},
     * {@code worker}); this writer adds none of its own.
     *
     * @throws IOException when the event cannot be serialised or the line cannot be written
     */
    public synchronized void write(Object event) throws IOException {
        out.write(MAPPER.writeValueAsString(event));
        out.write('\n');
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
