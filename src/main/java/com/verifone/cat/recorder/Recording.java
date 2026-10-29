package com.verifone.cat.recorder;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.validate.PyRepr;

/**
 * A recording file, read. Port of {@code petrotest.recorder.format.Recording} and {@code read_recording}.
 *
 * <p>Line types, in file order (CAT_RESULTS_AND_GENERATE.md 3.4):
 * <ul>
 *   <li>{@code header}: exactly one, first: {@code format}, {@code version}, {@code started_at}, {@code source}
 *       ({@code kind} {@code rig}, {@code cdp} or {@code fake_pos}; never an address), {@code catalogue},
 *       {@code initial_screen}, {@code captured}, {@code not_captured}. A file written before 2026-10-08 says
 *       {@code pos_double}, the fake POS's old name, and is read as {@code fake_pos}.</li>
 *   <li>{@code tap}: one per observed press: {@code seq}, {@code at}, {@code event}, {@code trusted}, {@code frame},
 *       {@code element} ({@code id}, {@code container_id}, {@code tag}, {@code texts}, {@code text}), {@code prompt},
 *       {@code redacted}, {@code map} (a {@link Mapping}) and, when delivered at stop, {@code late}. A redacted tap has
 *       its id, texts, text and slot values blanked: the cashier PIN stays out of files.</li>
 *   <li>{@code inferred}: reserved, never written by version 1.</li>
 *   <li>{@code baseline}: reserved; version 1's recorder never writes one ({@code kind}, {@code content},
 *       {@code approved}).</li>
 *   <li>{@code footer}: last, when the recorder stopped cleanly. A file with no footer was cut off, and is read, not
 *       refused.</li>
 * </ul>
 *
 * <p>Lines are kept as the JSON objects they are ({@code Map}s in file key order), as the Python keeps its dicts.
 */
public final class Recording {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private final Path path;
    private final Map<String, Object> header;
    private final List<Map<String, Object>> taps = new ArrayList<>();
    private final List<Map<String, Object>> baselines = new ArrayList<>();
    private final List<Map<String, Object>> inferred = new ArrayList<>();
    private Map<String, Object> footer;

    public Recording(Path path, Map<String, Object> header) {
        this.path = path;
        this.header = header;
    }

    public Path path() {
        return path;
    }

    public Map<String, Object> header() {
        return header;
    }

    public List<Map<String, Object>> taps() {
        return taps;
    }

    /** The {@code baseline} lines (none from a version-1 recorder). */
    public List<Map<String, Object>> baselines() {
        return baselines;
    }

    /** The {@code inferred} lines: reserved in version 1, kept so the generator can refuse them loudly. */
    public List<Map<String, Object>> inferred() {
        return inferred;
    }

    /** The footer, or null when the recording was cut off. */
    public Map<String, Object> footer() {
        return footer;
    }

    void footer(Map<String, Object> line) {
        footer = line;
    }

    /** {@code source.kind}, or the empty string. */
    public String sourceKind() {
        Object kind = map(header.get("source")).get("kind");
        return kind == null ? "" : PyRepr.str(kind);
    }

    /** {@code value} as a map, or an empty one when it is not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
    }

    /**
     * Reads {@code path}.
     *
     * @throws RecordingException when it is empty, not this format or version, or a line is malformed
     * @throws IOException        when it cannot be read
     */
    public static Recording read(Path path) throws IOException {
        Recording recording = null;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            int number = 0;
            while ((raw = reader.readLine()) != null) {
                number++;
                if (raw.strip().isEmpty()) {
                    continue;
                }
                Map<String, Object> line = parse(path, number, raw);
                if (recording == null) {
                    recording = start(path, line);
                    continue;
                }
                String kind = PyRepr.str(line.get("type"));
                if (recording.footer != null) {
                    throw new RecordingException(path + ":" + number + ": a line after the footer");
                }
                switch (kind) {
                    case "tap" -> recording.taps.add(line);
                    case "baseline" -> recording.baselines.add(line);
                    case "inferred" -> recording.inferred.add(line);
                    case "footer" -> recording.footer = line;
                    default -> throw new RecordingException(
                            path + ":" + number + ": unknown line type " + PyRepr.repr(line.get("type")));
                }
            }
        }
        if (recording == null) {
            throw new RecordingException(path + ": empty");
        }
        return recording;
    }

    private static Recording start(Path path, Map<String, Object> header) {
        if (!"header".equals(header.get("type")) || !RecordingFormat.FORMAT.equals(header.get("format"))) {
            throw new RecordingException(path + ": not a " + RecordingFormat.FORMAT
                    + " file (first line is not its header)");
        }
        Object version = header.get("version");
        boolean known = version instanceof Number n && !(version instanceof Boolean)
                && n.doubleValue() == RecordingFormat.VERSION;
        if (!known) {
            throw new RecordingException(path + ": version " + PyRepr.repr(version) + "; this reader knows "
                    + RecordingFormat.VERSION + " only");
        }
        Object source = header.get("source");
        if (source instanceof Map<?, ?> && RecordingFormat.LEGACY_FAKE_POS_KIND.equals(((Map<?, ?>) source).get("kind"))) {
            map(source).put("kind", "fake_pos");
        }
        return new Recording(path, header);
    }

    private static Map<String, Object> parse(Path path, int number, String raw) {
        Object value;
        try {
            value = JSON.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            throw new RecordingException(path + ":" + number + ": not JSON (" + e.getOriginalMessage() + ")", e);
        }
        if (!(value instanceof Map<?, ?>) || !((Map<?, ?>) value).containsKey("type")) {
            throw new RecordingException(path + ":" + number + ": a line must be an object with a 'type'");
        }
        return JSON.convertValue(value, OBJECT);
    }
}
