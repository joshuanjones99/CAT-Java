package com.verifone.cat.authoring;

import java.util.Collection;
import java.util.Map;

/**
 * {@code json.dumps} with its defaults: {@code ", "} and {@code ": "} between items, and every character outside printable
 * ASCII escaped as a backslash-u escape of four hex digits. A publish plan shows these strings, so they are written as the Python writes them.
 *
 * <p>Handles {@link Map}, {@link Collection}, {@link String}, {@link Number}, {@link Boolean} and {@code null}; anything
 * else is its {@code toString()} as a string.
 */
final class PyJson {

    private PyJson() {}

    /** {@code value} as {@code json.dumps} writes it. */
    static String dumps(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(": ");
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            quote(value.toString(), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
