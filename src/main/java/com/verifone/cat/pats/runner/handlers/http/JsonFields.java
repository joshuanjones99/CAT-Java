package com.verifone.cat.pats.runner.handlers.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON reads as PATS's HTTP steps make them: a body parsed or refused, a {@code $.a.b[0]} path resolved, and a node
 * read as text. Ported from the Python {@code _json_or_none}, {@code _resolve} and {@code _as_text}.
 */
final class JsonFields {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern INDEXED = Pattern.compile("(.*?)\\[(\\d+)\\]");

    private JsonFields() {
    }

    /**
     * The body as a JSON document, or {@code null} when it is not UTF-8, not JSON, or is one of the constants
     * ({@code NaN}, {@code Infinity}) that JSON does not allow (PATS's Jackson refuses them too).
     */
    static JsonNode parse(byte[] body) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * {@code JsonPathUtil.resolvePath}: {@code $.a.b[0]} (the {@code $.} is optional). Null for a missing node or a
     * JSON null, at any step.
     */
    static JsonNode resolve(JsonNode root, String path) {
        JsonNode node = present(root);
        String rest = path.startsWith("$.") ? path.substring(2) : path;
        for (String segment : rest.split("\\.", -1)) {
            Matcher indexed = INDEXED.matcher(segment);
            String name = segment;
            Integer index = null;
            if (indexed.matches()) {
                name = indexed.group(1);
                // A subscript too large for an int names no element of any array, as Python's index does.
                index = new java.math.BigInteger(indexed.group(2)).min(java.math.BigInteger.valueOf(Integer.MAX_VALUE))
                        .intValue();
            }
            if (!name.isEmpty()) {
                node = node != null && node.isObject() ? present(node.get(name)) : null;
            }
            if (index != null) {
                node = node != null && node.isArray() && index < node.size() ? present(node.get(index)) : null;
            }
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** Jackson's {@code asText()} as PATS reads it: a container reads as empty. */
    static String asText(JsonNode node) {
        if (node.isContainerNode()) {
            return "";
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "true" : "false";
        }
        if (node.isNull()) {
            return "";
        }
        return node.asText();
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }
}
