package com.verifone.cat.prefs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * This operator's web preferences: {@code ~/.cat/java/web-settings.json}, the browser app's
 * counterpart of the Python CAT's {@code ~/.cat/web-settings.json} (docs/web-ui.md, Settings).
 *
 * <p>CAT-Java does not share the Python CAT's file: the two formats are not proven identical, so
 * the Java port keeps its own under {@code ~/.cat/java/}, as {@code SavedChoices} does. The location
 * is a parameter everywhere; only {@link #defaultPath()} reaches the home directory, and
 * {@code $CAT_WEB_SETTINGS} moves the default, as it does in the Python CAT.
 *
 * <p>The file is a JSON object. {@link #update} keeps every other key it finds, so a key written
 * by the Python CAT (or a later version) survives a save from here.
 */
public final class WebSettings {

    /** The env override for the file's location: the Python CAT's name, so one variable moves both. */
    public static final String ENV_OVERRIDE = "CAT_WEB_SETTINGS";

    /** The file's name under {@code ~/.cat/java/}. */
    public static final String FILE_NAME = "web-settings.json";

    /** The key holding the Appearance choice, as the Python CAT names it. */
    public static final String APPEARANCE_KEY = "appearance";

    /**
     * The cookie that carries the Appearance choice to every page, so {@code cat.js} can apply it
     * as {@code data-theme} on {@code <html>}. The file is the record; the cookie is its copy in
     * this browser.
     */
    public static final String APPEARANCE_COOKIE = "cat_appearance";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private WebSettings() {}

    /** Light, dark or the system's choice. {@link #AUTO} is the default and what a missing file means. */
    public enum Appearance {
        AUTO("auto", "Match Windows"),
        LIGHT("light", "Light"),
        DARK("dark", "Dark");

        private final String value;
        private final String label;

        Appearance(String value, String label) {
            this.value = value;
            this.label = label;
        }

        /** The stored and submitted name: {@code auto}, {@code light} or {@code dark}. */
        public String value() {
            return value;
        }

        /** The words the Settings page shows. */
        public String label() {
            return label;
        }

        /** The choice {@code text} names, or {@code null} when it names none (case-sensitive, as stored). */
        public static Appearance parse(String text) {
            if (text == null) {
                return null;
            }
            for (Appearance choice : values()) {
                if (choice.value.equals(text.trim())) {
                    return choice;
                }
            }
            return null;
        }
    }

    /** {@code $CAT_WEB_SETTINGS}, else {@code ~/.cat/java/web-settings.json}. A blank variable counts as unset. */
    public static Path defaultPath(Map<String, String> env, String userHome) {
        String override = env.get(ENV_OVERRIDE);
        return override == null || override.isBlank()
                ? Path.of(userHome, ".cat", "java", FILE_NAME)
                : Path.of(override);
    }

    public static Path defaultPath() {
        return defaultPath(System.getenv(), System.getProperty("user.home"));
    }

    /** The whole object as saved; empty when the file is absent, unreadable or not an object. */
    public static Map<String, Object> read(Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            LinkedHashMap<String, Object> saved = MAPPER.readValue(file.toFile(), OBJECT);
            if (saved != null) {
                out.putAll(saved);
            }
        } catch (IOException | RuntimeException e) {
            // A file that will not read is no preferences: the defaults apply.
            out.clear();
        }
        return out;
    }

    /** The saved Appearance choice; {@link Appearance#AUTO} when none is saved or the value is not one. */
    public static Appearance appearance(Path file) {
        Object value = read(file).get(APPEARANCE_KEY);
        Appearance saved = value instanceof String text ? Appearance.parse(text) : null;
        return saved == null ? Appearance.AUTO : saved;
    }

    /**
     * Sets one key, keeping the rest of the file. Never throws: a read-only home must not break the
     * page, as the Python CAT's {@code save_settings} does.
     *
     * @return whether the file was written
     */
    public static boolean update(Path file, String key, Object value) {
        Map<String, Object> data = read(file);
        data.put(key, value);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(file.toFile(), data);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
