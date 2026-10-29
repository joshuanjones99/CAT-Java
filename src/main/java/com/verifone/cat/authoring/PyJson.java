package com.verifone.cat.authoring;

import java.util.Collection;
import java.util.Map;

/**
 * {@code json.dumps} as the Python writes it, so a file or a plan Java writes reads byte for byte as the Python's.
 *
 * <p>{@link #dumps(Object)} is {@code json.dumps} with its defaults: {@code ", "} and {@code ": "} between items, and every
 * character outside printable ASCII escaped as a backslash-u escape of four hex digits. {@link #dumps(Object, boolean)}
 * with {@code ensureAscii} false is {@code json.dumps(..., ensure_ascii=False)}: only the control characters are escaped.
 * {@link #indented} is {@code json.dumps(..., indent=n, ensure_ascii=...)}: {@code ","} at a line end, {@code ": "}
 * between a key and its value, and an empty object or list as {@code {}} or {@code []}. {@link #compact} is
 * {@code json.dumps(..., separators=(",", ":"))}.
 *
 * <p>Handles {@link Map}, {@link Collection}, {@link String}, {@link Number}, {@link Boolean} and {@code null}; anything
 * else is its {@code toString()} as a string.
 */
public final class PyJson {

    private PyJson() {}

    /** {@code value} as {@code json.dumps} writes it. */
    public static String dumps(Object value) {
        return dumps(value, true);
    }

    /** {@code value} as {@code json.dumps(value, ensure_ascii=ensureAscii)} writes it. */
    public static String dumps(Object value, boolean ensureAscii) {
        StringBuilder out = new StringBuilder();
        write(value, out, ensureAscii, -1, 0, ", ", ": ");
        return out.toString();
    }

    /** {@code value} as {@code json.dumps(value, indent=indent, ensure_ascii=ensureAscii)} writes it. */
    public static String indented(Object value, int indent, boolean ensureAscii) {
        StringBuilder out = new StringBuilder();
        write(value, out, ensureAscii, indent, 0, ",", ": ");
        return out.toString();
    }

    /** {@code value} as {@code json.dumps(value, separators=(",", ":"))} writes it: no spaces at all. */
    public static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, true, -1, 0, ",", ":");
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, boolean ascii, int indent, int level, String item,
            String key) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out, ascii);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(item);
                }
                first = false;
                newline(out, indent, level + 1);
                quote(String.valueOf(entry.getKey()), out, ascii);
                out.append(key);
                write(entry.getValue(), out, ascii, indent, level + 1, item, key);
            }
            newline(out, indent, level);
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            if (items.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            boolean first = true;
            for (Object element : items) {
                if (!first) {
                    out.append(item);
                }
                first = false;
                newline(out, indent, level + 1);
                write(element, out, ascii, indent, level + 1, item, key);
            }
            newline(out, indent, level);
            out.append(']');
        } else {
            quote(value.toString(), out, ascii);
        }
    }

    private static void newline(StringBuilder out, int indent, int level) {
        if (indent >= 0) {
            out.append('\n').append(" ".repeat(indent * level));
        }
    }

    private static void quote(String text, StringBuilder out, boolean ascii) {
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
                    if (c < 0x20 || (ascii && c > 0x7e)) {
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
