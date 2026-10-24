package com.verifone.cat.plans;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The on-disk snapshot format: {@code {"fetched_at": ..., "rows": [...]}}, the Python CAT's
 * cache format, so a cache written by either reads in the other.
 *
 * <p>Reading is strict on the rows, as the Python reader is: a row with a field this version
 * does not know is a cache miss, not a partly-read picker. A cache written by a different
 * build must not fill the picker with rows whose meaning has moved.
 */
final class SnapshotFiles {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /** A snapshot's rows and when they were written. */
    record Stored<T>(Instant fetchedAt, List<T> rows) {}

    private SnapshotFiles() {}

    /** The stored rows, or empty for any unreadable document. Never throws. */
    static <T> Optional<Stored<T>> read(InputStream in, Class<T> type) {
        try {
            return parse(MAPPER.readTree(in), type);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static <T> Optional<Stored<T>> read(Path file, Class<T> type) {
        try {
            return parse(MAPPER.readTree(Files.readAllBytes(file)), type);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static <T> Optional<Stored<T>> parse(JsonNode root, Class<T> type) throws IOException {
        JsonNode stamp = root.get("fetched_at");
        JsonNode rows = root.get("rows");
        if (stamp == null || !stamp.isTextual() || rows == null || !rows.isArray()) {
            return Optional.empty();
        }
        Instant fetchedAt = OffsetDateTime.parse(stamp.asText()).toInstant();
        List<T> out = new ArrayList<>();
        for (JsonNode row : rows) {
            out.add(MAPPER.treeToValue(row, type));
        }
        return Optional.of(new Stored<>(fetchedAt, List.copyOf(out)));
    }

    static <T> void write(Path file, List<T> rows, Instant fetchedAt) throws IOException {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("fetched_at", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC).toString());
        ArrayNode array = MAPPER.valueToTree(rows);
        document.set("rows", array);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                StandardCharsets.UTF_8);
    }
}
