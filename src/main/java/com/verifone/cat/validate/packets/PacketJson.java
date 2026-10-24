package com.verifone.cat.validate.packets;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.validate.PyRepr;

/**
 * The packet snapshot's JSON, in the Python CAT's {@code json.dumps(value, indent=2)} layout, so a
 * Java-written baseline reads the same as a Python-written one in a pull request.
 *
 * <p>Writing mirrors Python: two-space indent, {@code ": "} between key and value, {@code []} and
 * {@code {}} when empty, non-ASCII escaped as {@code \\uXXXX}. Reading goes through Jackson and then
 * into plain Java collections, keeping key order. Numbers are {@link BigInteger} or {@link Double};
 * equality is numeric, so {@code 1} and {@code 1.0} are equal as they are in Python.
 */
final class PacketJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PacketJson() {}

    /** Parses JSON to plain Java values. @throws IllegalArgumentException with "invalid packet JSON" if malformed. */
    static Object parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("invalid packet JSON: empty input");
            }
            return toJava(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid packet JSON: " + e.getMessage(), e);
        }
    }

    /** Serialises plain Java values the way Python's {@code json.dumps(value, indent=2)} does. */
    static String dumps(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, 0, sb);
        return sb.toString();
    }

    /** Python's {@code ==} over parsed JSON: dicts by key set and values, lists in order, numbers numerically. */
    static boolean deepEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            return decimal(x).compareTo(decimal(y)) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : x.entrySet()) {
                if (!deepEquals(entry.getValue(), y.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            Iterator<?> right = y.iterator();
            for (Object left : x) {
                if (!deepEquals(left, right.next())) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static Object toJava(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(toJava(item));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        throw new IllegalArgumentException("invalid packet JSON: unsupported value " + node);
    }

    private static BigDecimal decimal(Number n) {
        if (n instanceof BigInteger big) {
            return new BigDecimal(big);
        }
        if (n instanceof Double d) {
            return new BigDecimal(Double.toString(d));
        }
        return new BigDecimal(n.toString());
    }

    private static void write(Object value, int level, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean flag) {
            sb.append(flag ? "true" : "false");
        } else if (value instanceof Double d) {
            sb.append(PyRepr.floatRepr(d));
        } else if (value instanceof Number n) {
            sb.append(n);
        } else if (value instanceof String text) {
            writeString(text, sb);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            String inner = "  ".repeat(level + 1);
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(inner);
                writeString(String.valueOf(entry.getKey()), sb);
                sb.append(": ");
                write(entry.getValue(), level + 1, sb);
            }
            sb.append('\n').append("  ".repeat(level)).append('}');
        } else if (value instanceof List<?> items) {
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            String inner = "  ".repeat(level + 1);
            sb.append("[\n");
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append(inner);
                write(item, level + 1, sb);
            }
            sb.append('\n').append("  ".repeat(level)).append(']');
        } else {
            throw new IllegalArgumentException("cannot write " + value.getClass().getSimpleName() + " as JSON");
        }
    }

    /** Python's {@code json.dumps} string: ASCII only, with the usual escapes. */
    private static void writeString(String text, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
