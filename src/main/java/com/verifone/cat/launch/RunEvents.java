package com.verifone.cat.launch;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.report.NdjsonWriter;
import com.verifone.cat.report.ReportPaths;
import com.verifone.cat.report.events.CatEvent;
import com.verifone.cat.report.events.Envelope;
import com.verifone.cat.report.events.EventEmitter;
import com.verifone.cat.report.events.RunLogWriter;

/**
 * One run's event sink: {@code events.ndjson} and {@code run.log} in the run's folder, written from
 * the same event with the same timestamp.
 *
 * <p>Every event the launcher and its steps produce goes through {@link #emit}. The events carry the
 * launcher's own worker name, {@link #WORKER}, as the Python CAT's {@code SESSION_WORKER} does.
 * A failed write costs the file, not the run: the first failure is logged and writing stops.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
final class RunEvents implements Closeable {

    /** The worker name on every event the launcher itself writes (the Python CAT's {@code controller}). */
    static final String WORKER = "controller";

    private static final Logger LOG = LoggerFactory.getLogger(RunEvents.class);

    private final EventEmitter emitter;
    private final RunLogWriter runLog;
    private boolean failed;

    private RunEvents(EventEmitter emitter, RunLogWriter runLog) {
        this.emitter = emitter;
        this.runLog = runLog;
    }

    /** Opens the run's folder's two files. Creates the folder; the events file is created now. */
    static RunEvents open(Path runFolder, Clock clock) throws IOException {
        Files.createDirectories(runFolder);
        NdjsonWriter writer = new NdjsonWriter(runFolder.resolve(ReportPaths.EVENTS_FILE));
        EventEmitter emitter = new EventEmitter(writer, WORKER, clock);
        RunLogWriter runLog = new RunLogWriter(runFolder.resolve(RunLogWriter.RUN_LOG));
        return new RunEvents(emitter, runLog);
    }

    /** Stamps and writes one event, and gives it to the run log. Never throws. */
    synchronized void emit(CatEvent event) {
        try {
            Envelope envelope = emitter.emit(event);
            runLog.emit(envelope.at(), event);
        } catch (IOException | RuntimeException e) {
            if (!failed) {
                failed = true;
                LOG.warn("events.ndjson could not be written: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** Closes both files. Idempotent. */
    @Override
    public synchronized void close() {
        try {
            emitter.close();
        } catch (IOException e) {
            LOG.debug("events.ndjson close failed: {}", e.getMessage());
        }
        runLog.close();
    }
}
