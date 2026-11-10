package com.verifone.petrotest.posshim;

import java.util.Collection;
import java.util.Map;

/**
 * A minimal JSON writer.
 *
 * <p>The shim runs on the register's JRE with only the Topaz classpath available
 * (DESIGN.md §6.1 pushes two jars, not a dependency tree), so pulling in Jackson
 * would mean either shading it into our jar or relying on whichever version
 * {@code /home/topaz/lib} happens to carry that build. Both are worse than the
 * fifty lines below: everything this shim emits is built from strings, booleans,
 * numbers, lists and maps, and that is the whole of what this class encodes.
 *
 * <p>Not a general-purpose library. It does no pretty-printing, keeps insertion
 * order (callers pass {@link java.util.LinkedHashMap}), and rejects nothing —
 * an unknown type is written as its {@code toString()} in quotes rather than
 * throwing, because a shim that 500s while reporting POS state is less useful
 * than one that reports the field as text.
 */
final class Json {

    private Json() {}

    static String encode(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(out, s);
        } else if (value instanceof Boolean b) {
            out.append(b.booleanValue());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            // Infinity/NaN are not JSON. Emitting null keeps the document
            // parseable rather than producing a body httpx cannot decode.
            double d = ((Number) value).doubleValue();
            out.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value instanceof Object[] items) {
            out.append('[');
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, items[i]);
            }
            out.append(']');
        } else {
            string(out, String.valueOf(value));
        }
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Topaz panel text is operator-facing and can carry control
                    // characters from the display layer; escape anything below
                    // 0x20 rather than emitting a raw byte that breaks parsing.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Reads a flat JSON object body into a map of string values.
     *
     * <p>Deliberately not a full parser: the only bodies this shim accepts are
     * the ones its own Python client sends — {@code {"keys": ["CLEAR"]}} and
     * {@code {"cashier": "1", "password": "123"}} — so the grammar handled here
     * is objects of strings, numbers, booleans and flat string arrays. Anything
     * else raises, and the caller answers 400. A hand-rolled parser that
     * silently accepted more than it understood would be the worse failure.
     */
    static Map<String, Object> decodeObject(String body) {
        return new MiniParser(body).parseObject();
    }

    static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    private static final class MiniParser {
        private final String src;
        private int pos;

        MiniParser(String src) {
            this.src = src == null ? "" : src;
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        private Object parseValue() {
            skipWhitespace();
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == '[') {
                pos++;
                java.util.List<Object> items = new java.util.ArrayList<>();
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return items;
                }
                while (true) {
                    items.add(parseValue());
                    skipWhitespace();
                    char d = next();
                    if (d == ']') {
                        return items;
                    }
                    if (d != ',') {
                        throw new JsonException("expected ',' or ']' at offset " + (pos - 1));
                    }
                }
            }
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new JsonException("unexpected character at offset " + pos);
            }
            String number = src.substring(start, pos);
            try {
                return number.contains(".") || number.contains("e") || number.contains("E")
                        ? (Object) Double.valueOf(number)
                        : (Object) Long.valueOf(number);
            } catch (NumberFormatException e) {
                throw new JsonException("bad number " + number);
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new JsonException("truncated \\u escape");
                        }
                        out.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new JsonException("bad escape \\" + esc);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= src.length()) {
                throw new JsonException("unexpected end of body");
            }
            return src.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new JsonException("expected '" + expected + "' at offset " + (pos - 1));
            }
        }
    }
}
