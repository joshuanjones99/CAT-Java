package com.verifone.cat.runconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The Config tab's saved choices: {@code ~/.cat/java/last-config.json}.
 *
 * <p>The Python CAT keeps its own {@code ~/.cat/last-config.json} under the same keys. CAT-Java
 * does not share that file: until the two formats are proven identical, a write from one
 * could corrupt the other, so the Java port has its own file under {@code ~/.cat/java/}.
 *
 * <p>Read and written as a JSON object, keys in the order they were saved. The location is a
 * parameter everywhere: tests pass a {@code @TempDir}, and only {@link #defaultPath()} reaches
 * the home directory. {@code $CAT_SAVED_CONFIG} moves the default, as it does in the Python CAT.
 */
public final class SavedChoices {

    /** The Python CAT's env override for the file's location. */
    public static final String ENV_OVERRIDE = "CAT_SAVED_CONFIG";

    /**
     * Keys the Python CAT retired (rig owner, 2026-10-21 and earlier). A file saved before then
     * still holds them; they are dropped on load, so they are neither applied nor written back.
     */
    static final java.util.Set<String> RETIRED_KEYS = java.util.Set.of(
            "config-jacoco", "config-rig", "config-workers", "config-config-name", "config-pos-double",
            "config-emit-reports", "config-download", "config-branch", "config-solutions",
            "config-upgrade-sims", "config-connections", "config-simulators", "config-secondary-feps");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT =
            new TypeReference<>() {};

    private SavedChoices() {}

    /** The file's name under {@code ~/.cat/java/}. Not the Python CAT's {@code last-config.json}. */
    public static final String FILE_NAME = "last-config.json";

    /** {@code $CAT_SAVED_CONFIG}, else {@code ~/.cat/java/last-config.json}. */
    public static Path defaultPath(Map<String, String> env, String userHome) {
        String override = env.get(ENV_OVERRIDE);
        return override == null || override.isBlank()
                ? Path.of(userHome, ".cat", "java", FILE_NAME)
                : Path.of(override);
    }

    public static Path defaultPath() {
        return defaultPath(System.getenv(), System.getProperty("user.home"));
    }

    /** The saved object without retired keys; empty when the file is absent or not an object. */
    public static Map<String, Object> read(Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            LinkedHashMap<String, Object> saved = MAPPER.readValue(file.toFile(), OBJECT);
            if (saved != null) {
                saved.entrySet().stream()
                        .filter(entry -> !RETIRED_KEYS.contains(entry.getKey()))
                        .forEach(entry -> out.put(entry.getKey(), entry.getValue()));
            }
        } catch (IOException | RuntimeException e) {
            // A file that will not read is no choices; the form keeps its defaults.
            out.clear();
        }
        return out;
    }

    /**
     * Writes the choices. Never throws: a read-only home must not break the form, as the Python
     * CAT's {@code save_choices} does.
     *
     * @return whether the file was written
     */
    public static boolean write(Path file, Map<String, Object> choices) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(file.toFile(), choices);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
