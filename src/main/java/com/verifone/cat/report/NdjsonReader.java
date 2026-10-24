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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NdjsonReader() {}

    /**
     * Calls {@code consumer} with each event object in {@code file}, in file order.
     *
     * @throws IOException when the file cannot be read as UTF-8
     */
    public static void forEach(Path file, Consumer<JsonNode> consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode event = parse(line.strip());
                if (event != null) {
                    consumer.accept(event);
                }
            }
        }
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
