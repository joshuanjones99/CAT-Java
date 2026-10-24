package com.verifone.cat.report.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes {@code run.ended} into a run's folder, holding the time the run was over.
 *
 * <p>The Python CAT's {@code cat.launch.steps.cleanup._mark_run_ended}: it runs from the launch's
 * {@code finally}, after the event stream is closed, so {@code events.ndjson} is then the whole
 * stream. The Xray sidecar stops on this file when the stream never said the run was over. The run
 * folder is not zipped or stripped here or anywhere (CLAUDE.md, "Run output is kept whole and
 * unzipped").
 *
 * <p>Never throws: it runs in a {@code finally}, and a failure here must not replace the run's own
 * outcome or exception.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class RunEnded {

    /** The marker file's name, beside {@code events.ndjson}. */
    public static final String RUN_ENDED_FILE = "run.ended";

    private static final Logger LOG = LoggerFactory.getLogger(RunEnded.class);

    private RunEnded() {}

    /**
     * Writes the marker into {@code runFolder} as {@code now} in UTC, microsecond precision and
     * {@code +00:00}, as Python's {@code datetime.isoformat()} writes it. Does nothing (and logs) when
     * the folder does not exist or cannot be written.
     */
    public static void mark(Path runFolder, Instant now) {
        if (!Files.isDirectory(runFolder)) {
            LOG.warn("no run directory to mark ended: {} does not exist", runFolder);
            return;
        }
        try {
            Files.writeString(
                    runFolder.resolve(RUN_ENDED_FILE),
                    isoFormat(now) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not mark {} ended: {}: {}", runFolder, e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }

    /** {@code 2026-10-24T12:00:00.123456+00:00}: UTC, microseconds, the Python spelling. */
    static String isoFormat(Instant now) {
        OffsetDateTime utc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        return utc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "+00:00";
    }
}
