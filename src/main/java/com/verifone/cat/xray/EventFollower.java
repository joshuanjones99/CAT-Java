package com.verifone.cat.xray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Follows a run's {@code events.ndjson} as the controller's tee appends to it. Port of the Python
 * CAT's {@code cat.xray.tail.follow}.
 *
 * <p>The reader remembers its byte offset and, on each tick, reads what has been added, so it sees
 * every event shortly after CAT writes it. A line is {@code log.line} by its tail and is dropped
 * without parsing (they are most of a real file). The last line of the file may be half-written; the
 * unterminated remainder is kept and completed on the next read. A file that does not exist yet is
 * waited for.
 *
 * <p>Ticks run on a {@link ScheduledExecutorService} at the poll interval, so no thread sleeps. The
 * stop rule is asked on each tick with no new bytes, with the idle time so far: a truthy answer
 * reads once more and then ends. {@code cancel}, when set, ends the follow at the next tick.
 */
public final class EventFollower {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] LOG_LINE_TAIL = "\"type\": \"log.line\"}".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> SKIPPED_TYPES = Set.of("log.line");

    private final Path path;
    private final DoublePredicate stop;
    private final double pollSeconds;
    private final AtomicBoolean cancel;
    private final Consumer<JsonNode> consumer;

    private long offset;
    private byte[] remainder = new byte[0];
    private double idle;
    private boolean finishing;

    /**
     * @param path        the {@code events.ndjson} to follow
     * @param stop        asked on each tick with no new bytes, with the idle seconds so far
     * @param poll        the tick interval
     * @param cancel      when set, the follow ends at the next tick
     * @param consumer    receives each event object, in file order, on the scheduler's thread
     */
    public EventFollower(Path path, DoublePredicate stop, Duration poll, AtomicBoolean cancel,
            Consumer<JsonNode> consumer) {
        this.path = path;
        this.stop = stop;
        this.pollSeconds = poll.toMillis() / 1000.0;
        this.cancel = cancel;
        this.consumer = consumer;
    }

    /**
     * Starts following on {@code scheduler}. The future completes when the follow ends, or fails
     * with the consumer's exception. After it completes the scheduled task does nothing more; the
     * caller shuts the scheduler down.
     */
    public CompletableFuture<Void> start(ScheduledExecutorService scheduler) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        long periodMillis = Math.max(1, Math.round(pollSeconds * 1000));
        scheduler.scheduleWithFixedDelay(() -> {
            if (done.isDone()) {
                return;
            }
            try {
                if (tick()) {
                    done.complete(null);
                }
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        }, 0, periodMillis, TimeUnit.MILLISECONDS);
        return done;
    }

    /** One read; true when the follow is over. */
    boolean tick() {
        if (cancel.get()) {
            return true;
        }
        byte[] chunk = readFrom(offset);
        if (chunk.length > 0) {
            offset += chunk.length;
            idle = 0.0;
            feed(chunk);
            // Once stopping, the next read ends the follow; that read takes what landed meanwhile.
            return false;
        }
        if (finishing) {
            if (remainder.length > 0) {
                emit(remainder);
                remainder = new byte[0];
            }
            return true;
        }
        if (stop.test(idle)) {
            finishing = true;
            return false;
        }
        idle += pollSeconds;
        return false;
    }

    /** Splits the new bytes into lines; the unterminated tail is kept for the next read. */
    private void feed(byte[] chunk) {
        byte[] data = concat(remainder, chunk);
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                emit(Arrays.copyOfRange(data, start, i));
                start = i + 1;
            }
        }
        remainder = Arrays.copyOfRange(data, start, data.length);
    }

    private void emit(byte[] line) {
        JsonNode event = parse(line);
        if (event != null) {
            consumer.accept(event);
        }
    }

    /** The event object a line holds, or null for a blank line, a log line, or anything else. */
    static JsonNode parse(byte[] line) {
        int end = line.length;
        while (end > 0 && isSpace(line[end - 1])) {
            end--;
        }
        int begin = 0;
        while (begin < end && isSpace(line[begin])) {
            begin++;
        }
        if (begin == end) {
            return null;
        }
        byte[] stripped = Arrays.copyOfRange(line, begin, end);
        if (endsWith(stripped, LOG_LINE_TAIL)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(new String(stripped, StandardCharsets.UTF_8));
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode type = node.get("type");
            if (type != null && SKIPPED_TYPES.contains(type.asText())) {
                return null;
            }
            return node;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private byte[] readFrom(long from) {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            if (length <= from) {
                return new byte[0];
            }
            byte[] out = new byte[(int) (length - from)];
            file.seek(from);
            file.readFully(out);
            return out;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    private static boolean endsWith(byte[] data, byte[] tail) {
        if (data.length < tail.length) {
            return false;
        }
        return Arrays.equals(data, data.length - tail.length, data.length, tail, 0, tail.length);
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + tail.length);
        out.write(head, 0, head.length);
        out.write(tail, 0, tail.length);
        return out.toByteArray();
    }
}
