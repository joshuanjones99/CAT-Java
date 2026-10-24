package com.verifone.cat.pats.runner.handlers.http;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.validate.PyRepr;

/**
 * PATS's JSON payload comparison, {@code JsonPayloadDiffUtil.compareJsonPayloads}. Ported from the Python
 * {@code json_payloads_differ}, which is the specification.
 *
 * <p>Both documents are flattened to {@code /a/b/0} paths. Each leaf keeps the Java type PATS's Jackson reads it as:
 * a JSON string is read as {@code parseStringValue} reads it (true or false, else a long, else a double, else the
 * string), so {@code "5"} and {@code 5} compare equal only if both read as the same type. The rule file's
 * {@code KEY_NAMES} drop every path that <em>contains</em> one of its names. Doubles compare by their bits.
 */
public final class JsonPayloads {

    private static final Pattern LONG = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern DOUBLE = Pattern.compile(
            "\\s*[+-]?(NaN|Infinity|(([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?)[fFdD]?)\\s*");
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX_EXCLUSIVE = BigInteger.ONE.shiftLeft(63);

    /** A leaf's value with the Java type it reads as; equal only when both match, as PATS's typed compare does. */
    private record Typed(String kind, Object value) {
    }

    private JsonPayloads() {
    }

    /**
     * The differences between the reference and the reply, under the rule file's keys: empty when they are equal,
     * otherwise one line per difference.
     *
     * @param reference the expected document, as the reference file holds it
     * @param current   the reply, as the step kept it
     * @param ruleText  the rule file's text, or empty
     */
    public static String differences(byte[] reference, byte[] current, String ruleText) {
        List<String> keys = ruleKeys(ruleText);
        Map<String, Typed> left;
        Map<String, Typed> right;
        try {
            left = flattenDocument(reference);
            right = flattenDocument(current);
        } catch (IllegalArgumentException e) {
            return "cannot compare: " + e.getMessage();
        }
        left = withoutKeys(left, keys);
        right = withoutKeys(right, keys);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Typed> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())) {
                lines.add("  only in the reference: " + entry.getKey() + " = " + repr(entry.getValue()));
            }
        }
        for (Map.Entry<String, Typed> entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                lines.add("  only in the reply: " + entry.getKey() + " = " + repr(entry.getValue()));
            }
        }
        for (Map.Entry<String, Typed> entry : left.entrySet()) {
            Typed other = right.get(entry.getKey());
            if (other != null && !other.equals(entry.getValue())) {
                lines.add("  " + entry.getKey() + ": expected " + repr(entry.getValue()) + " ("
                        + entry.getValue().kind() + "), got " + repr(other) + " (" + other.kind() + ")");
            }
        }
        return String.join("\n", lines);
    }

    /** {@code getRuleKeys}: {@code KEY_NAMES=} split on {@code ;}, each given a leading {@code /}. */
    static List<String> ruleKeys(String text) {
        List<String> keys = new ArrayList<>();
        if (text == null) {
            return keys;
        }
        for (String line : text.split("\r\n|\n|\r", -1)) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2 || !parts[0].equals("KEY_NAMES")) {
                continue;
            }
            List<String> items = new ArrayList<>(Arrays.asList(parts[1].split(";", -1)));
            while (!items.isEmpty() && items.get(items.size() - 1).isEmpty()) {
                items.remove(items.size() - 1);
            }
            for (String item : items) {
                keys.add(item.startsWith("/") ? item : "/" + item);
            }
        }
        return keys;
    }

    private static Map<String, Typed> withoutKeys(Map<String, Typed> flat, List<String> keys) {
        Map<String, Typed> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Typed> entry : flat.entrySet()) {
            boolean dropped = keys.stream().anyMatch(entry.getKey()::contains);
            if (!dropped) {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return kept;
    }

    private static Map<String, Typed> flattenDocument(byte[] data) {
        JsonNode root = JsonFields.parse(data);
        if (root == null) {
            throw new IllegalArgumentException("not JSON");
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("the document is not a JSON object");
        }
        Map<String, Typed> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            walk(field.getKey(), field.getValue(), out);
        }
        return out;
    }

    /** {@code flatten}: an empty object or array leaves nothing; a leaf is typed as Jackson types it. */
    private static void walk(String key, JsonNode node, Map<String, Typed> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                walk(key + "/" + field.getKey(), field.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(key + "/" + i, node.get(i), out);
            }
        } else if (node.isTextual()) {
            out.put("/" + key, parseStringValue(node.textValue()));
        } else {
            out.put("/" + key, nativeValue(node));
        }
    }

    /** The type Jackson's default mapper reads a JSON scalar as. */
    private static Typed nativeValue(JsonNode node) {
        if (node.isNull()) {
            return new Typed("null", null);
        }
        if (node.isBoolean()) {
            return new Typed("Boolean", node.booleanValue());
        }
        if (node.isIntegralNumber()) {
            if (node.isInt()) {
                return new Typed("Integer", node.intValue());
            }
            if (node.isLong()) {
                return new Typed("Long", node.longValue());
            }
            return new Typed("BigInteger", node.bigIntegerValue());
        }
        if (node.isFloatingPointNumber()) {
            return new Typed("Double", node.doubleValue());
        }
        return new Typed("String", node.asText());
    }

    /** {@code parseStringValue}: true or false, else {@code Long.parseLong}, else {@code Double.parseDouble}. */
    private static Typed parseStringValue(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("false")) {
            return new Typed("Boolean", lower.equals("true"));
        }
        if (LONG.matcher(text).matches()) {
            BigInteger value = new BigInteger(text);
            if (value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX_EXCLUSIVE) < 0) {
                return new Typed("Long", value.longValue());
            }
        }
        if (DOUBLE.matcher(text).matches()) {
            String numeric = text.strip();
            int end = numeric.length();
            while (end > 0 && "fFdD".indexOf(numeric.charAt(end - 1)) >= 0) {
                end--;
            }
            return new Typed("Double", Double.parseDouble(numeric.substring(0, end)));
        }
        return new Typed("String", text);
    }

    private static String repr(Typed typed) {
        return PyRepr.repr(typed.value());
    }
}
