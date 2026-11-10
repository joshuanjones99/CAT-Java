package com.verifone.cat.report;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.core.Json;

/**
 * Reads an {@code events.ndjson} one event at a time, the way the Python CAT's
 * {@code petrotest.report.ndjson.iter_events} does.
 *
 * <p>Blank lines and lines that are not a JSON object are skipped rather than failing the
 * report: a run that was killed mid-line still has a readable report for every event
 * before it. The stream is read line by line, so memory does not grow with the run; a full
 * Cadence run's stream is hundreds of megabytes.
 */
public final class NdjsonReader {

    private static final ObjectMapper MAPPER = Json.MAPPER;
    private static final String JAVA_LOG_LINE = "{\"type\":\"log.line\",";
    private static final String PYTHON_LOG_LINE = "\"type\": \"log.line\"}";

    private NdjsonReader() {}

    /**
     * Calls {@code consumer} with each event object in {@code file}, in file order.
     *
     * @throws IOException when the file cannot be read as UTF-8
     */
    public static void forEach(Path file, Consumer<JsonNode> consumer) throws IOException {
        forEach(file, consumer, true);
    }

    /**
     * {@link #forEach} without the {@code log.line} events, for a reader that folds a run and shows no log. They
     * are most of a real stream's lines, and each is recognised by its text and never parsed.
     *
     * @throws IOException when the file cannot be read as UTF-8
     */
    public static void forEachExceptLogs(Path file, Consumer<JsonNode> consumer) throws IOException {
        forEach(file, consumer, false);
    }

    private static void forEach(Path file, Consumer<JsonNode> consumer, boolean logs) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.strip();
                if (!logs && isLogLine(text)) {
                    continue;
                }
                JsonNode event = parse(text);
                if (event != null) {
                    consumer.accept(event);
                }
            }
        }
    }

    /**
     * Whether a stripped line is a {@code log.line} event as either writer spells one: {@link NdjsonWriter} puts
     * {@code type} first with no spaces, the Python CAT puts it last with them. Any other spelling is not
     * recognised here and is parsed like every other event.
     */
    public static boolean isLogLine(String line) {
        return line.startsWith(JAVA_LOG_LINE) || line.endsWith(PYTHON_LOG_LINE);
    }

    /** The object a line holds, or {@code null} for a blank line, a malformed one or one that is not an object. */
    static JsonNode parse(String line) {
        if (line.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
