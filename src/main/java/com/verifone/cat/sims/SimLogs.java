package com.verifone.cat.sims;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where a simulator's lines come from: its stdout pipe, and its {@code log:} file.
 *
 * <p>An adopted simulator has no pipe, so its file log is the only log there is. A simulator
 * that writes to a file (log4j, Petro) is followed from the file's end, so lines from before
 * this start are not replayed.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
final class SimLogs {

    /** How often a file tail looks for new bytes. A bounded poll; the wait ends at {@code stop}. */
    static final Duration POLL = Duration.ofMillis(250);

    /** How long stopping a tail waits for its thread to let go of the file. */
    static final Duration JOIN_WAIT = Duration.ofSeconds(5);

    private SimLogs() {
    }

    /**
     * Starts a daemon thread that reads the child's stdout to its end, feeding each line to
     * {@code events}. Sets {@code tripped} the first time a line contains {@code trip} (a non-empty
     * trip). Reads to the end whatever a line holds, so the child never blocks on a full pipe.
     */
    static Thread pump(Process process, String name, Events events, String trip, AtomicBoolean tripped) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!trip.isEmpty() && line.contains(trip)) {
                        tripped.set(true);
                    }
                    try {
                        events.log(name, line.stripTrailing());
                    } catch (RuntimeException ignored) {
                        // A callback that fails must not stop the drain (the child would block).
                    }
                }
            } catch (IOException ignored) {
                // The child's pipe closed: it has exited or been stopped.
            }
        }, "sims-pump-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Follows {@code path} from its current end on a daemon thread, feeding new lines to
     * {@code events}. The returned handle ends the follow.
     */
    static Runnable tail(Path path, String name, Events events) {
        long start = 0;
        try {
            start = Files.size(path);
        } catch (IOException ignored) {
            // Not written yet: follow from zero.
        }
        CountDownLatch stop = new CountDownLatch(1);
        long from = start;
        Thread thread = new Thread(() -> follow(path, name, events, stop, from), "sims-tail-" + name);
        thread.setDaemon(true);
        thread.start();
        return () -> {
            stop.countDown();
            // Wait, bounded, for the follow to let go of the file: a caller that deletes the
            // folder next must not race a tail still holding it open.
            try {
                thread.join(JOIN_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void follow(Path path, String name, Events events, CountDownLatch stop, long from) {
        long pos = from;
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        while (stop.getCount() > 0) {
            long size;
            try {
                size = Files.size(path);
            } catch (IOException e) {
                size = pos;
            }
            if (size < pos) {
                pos = 0;
                pending.reset();
            }
            if (size > pos) {
                try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                    channel.position(pos);
                    ByteBuffer buffer = ByteBuffer.allocate((int) (size - pos));
                    while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                        // read until the window is full or the file ends
                    }
                    pos += buffer.position();
                    pending.write(buffer.array(), 0, buffer.position());
                } catch (IOException ignored) {
                    // Gone or locked for a moment: the next pass tries again.
                }
                emitLines(pending, name, events);
            }
            try {
                if (stop.await(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Emits every complete line in {@code pending} and keeps the unfinished tail. */
    private static void emitLines(ByteArrayOutputStream pending, String name, Events events) {
        byte[] bytes = pending.toByteArray();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                String line = new String(bytes, start, i - start, StandardCharsets.UTF_8);
                try {
                    events.log(name, line.stripTrailing());
                } catch (RuntimeException ignored) {
                    // A failing callback must not end the follow.
                }
                start = i + 1;
            }
        }
        pending.reset();
        pending.write(bytes, start, bytes.length - start);
    }
}
