package com.verifone.cat.setup;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The Atlassian credentials file's text: the Python CAT's {@code cat.atlassian.read_properties} and
 * {@code credentials_text}, and the line-keeping merge of {@code JiraApiToken.apply_manual}.
 *
 * <p>Values are written with {@code \ = : # !} backslash-escaped, so a token holding a literal {@code =} reads back
 * the same through {@link Properties} (which the rest of CAT-Java reads the file with) and the Python's reader.
 */
final class JiraFile {

    private static final String ESCAPED = "\\=:#!";

    private JiraFile() {}

    /** Every {@code key=value} in {@code text}, as {@link Properties} reads it. A text that will not read is empty. */
    static Map<String, String> read(String text) {
        Properties values = new Properties();
        try {
            values.load(new StringReader(text == null ? "" : text));
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : values.stringPropertyNames()) {
            out.put(name, values.getProperty(name));
        }
        return out;
    }

    /**
     * {@code existing} with every line that sets one of {@code keys} dropped (the rest kept, trailing blank lines
     * trimmed), then the keys written in order: the file is shared with Monarch's format, and a line CAT does not own
     * is never lost.
     */
    static String merge(String existing, List<String> keys, Map<String, String> values) {
        List<String> kept = new ArrayList<>();
        for (String line : (existing == null ? "" : existing).split("\\R", -1)) {
            boolean owned = false;
            for (String key : read(line).keySet()) {
                if (keys.contains(key)) {
                    owned = true;
                    break;
                }
            }
            if (!owned) {
                kept.add(line);
            }
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) {
            kept.remove(kept.size() - 1);
        }
        StringBuilder out = new StringBuilder();
        for (String line : kept) {
            out.append(line).append('\n');
        }
        for (String key : keys) {
            out.append(key).append('=').append(escape(values.getOrDefault(key, ""))).append('\n');
        }
        return out.toString();
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (ESCAPED.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
