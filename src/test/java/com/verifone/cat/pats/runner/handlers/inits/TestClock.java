package com.verifone.cat.pats.runner.handlers.inits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A fake clock for the waits: every pause advances it, so a wait ends in simulated time and no test waits in real
 * time. Lines can be scheduled into a file (Petro's log) at a simulated instant; they are appended on the first
 * pause that reaches it, so a poll sees them as a device's log would show them.
 *
 * <p>Test support only; it is not a production clock.
 */
public final class TestClock {

    private record Scheduled(long atNanos, Path file, String line) {
    }

    private long nanos = 1_000_000_000L;
    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();

    /** The clock's reading, in nanoseconds. */
    public long nanos() {
        return nanos;
    }

    /** The waits this clock drives: its pauses advance it and deliver scheduled lines. */
    public Waits waits() {
        return new Waits(this::nanos, this::pause);
    }

    /** Every pause taken, in order (a poll's interval counts as a pause). */
    public List<Duration> pauses() {
        return List.copyOf(pauses);
    }

    /** Advances the clock without a pause, as time passing on its own (a case's own work). */
    public void advance(Duration duration) {
        nanos += duration.toNanos();
        deliver();
    }

    /** Appends {@code line} to {@code file} once {@code after} has passed from now. */
    public void writeAfter(Duration after, Path file, String line) {
        scheduled.add(new Scheduled(nanos + after.toNanos(), file, line));
    }

    private void pause(Duration duration) {
        pauses.add(duration);
        advance(duration);
    }

    private void deliver() {
        Iterator<Scheduled> due = scheduled.iterator();
        while (due.hasNext()) {
            Scheduled line = due.next();
            if (line.atNanos() <= nanos) {
                append(line.file(), line.line());
                due.remove();
            }
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
