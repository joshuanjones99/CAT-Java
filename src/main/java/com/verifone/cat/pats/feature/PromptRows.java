package com.verifone.cat.pats.feature;

import com.verifone.cat.pats.Elements;
import com.verifone.cat.pats.ScenarioParser;

import com.fasterxml.jackson.databind.JsonNode;
import com.verifone.cat.core.Json;
import com.verifone.cat.validate.PyRepr;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * VALIDATE_DYNAMIC_PROMPTS' table: the {@code PROMPTS} field (a JSON array) and the step text that writes it
 * (the {@code PROMPT_ROWS} phrase argument, {@code <prompt text> @ <element> = <key> + <key> ; ...}).
 *
 * <p>Port of {@code petrotest.pats.scenario.dynamic_prompts_field} (the JSON is written as Python's
 * {@code json.dumps(..., separators=(",", ":"))} writes it, so the two compare equal) and of the feature's
 * {@code _prompts_text} and {@code _prompts_field}. UNVERIFIED AGAINST HARDWARE.
 */
final class PromptRows {

    private static final Pattern PROMPT_SEPARATOR = Pattern.compile(Pattern.quote(" + "));

    private PromptRows() {}

    /** The {@code PROMPTS} field as the step's words: each row as {@code text @ prompt = key + key}, rows joined by {@code ;}. */
    static String toText(String field) {
        JsonNode rows;
        try {
            rows = Json.MAPPER.readTree(field);
        } catch (IOException exc) {
            throw new IllegalArgumentException("PROMPTS is not JSON: " + exc.getMessage(), exc);
        }
        if (rows == null || !rows.isArray()) {
            throw new IllegalArgumentException("PROMPTS is not a JSON array of rows");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode row : rows) {
            String text = required(row, "promptText");
            if (Phrase.containsAny(text, "\";@=+") || !text.equals(text.strip())) {
                throw new UnreadableActionException("prompt text " + PyRepr.reprString(text)
                        + " cannot be written in a step");
            }
            String prompt = Elements.elementName(
                    required(row, "promptWebElement"), required(row, "promptElementLocatorType"));
            String kind = required(row, "touchActionLocatorType");
            List<String> keys = new ArrayList<>();
            for (String element : required(row, "touchActionWebElements").split(",", -1)) {
                keys.add(Elements.elementName(element, kind));
            }
            out.add(text + " @ " + prompt + " = " + String.join(" + ", keys));
        }
        return String.join(" ; ", out);
    }

    /** The step's words (see {@link #toText}) read back into the {@code PROMPTS} field the runner reads. */
    static String fromText(String text) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String raw : text.split(";")) {
            String part = raw.strip();
            if (part.isEmpty()) {
                continue;
            }
            int at = part.indexOf(" @ ");
            String rest = at < 0 ? "" : part.substring(at + 3);
            int equals = rest.indexOf(" = ");
            if (at < 0 || equals < 0) {
                throw new IllegalArgumentException(PyRepr.reprString(part)
                        + " is not '<prompt text> @ <element> = <key> + <key>'");
            }
            String promptText = part.substring(0, at);
            Elements.Ref prompt = Elements.elementValue(rest.substring(0, equals));
            List<Elements.Ref> touches = new ArrayList<>();
            for (String key : PROMPT_SEPARATOR.split(rest.substring(equals + 3), -1)) {
                touches.add(Elements.elementValue(key));
            }
            LinkedHashSet<String> kinds = new LinkedHashSet<>();
            List<String> literals = new ArrayList<>();
            for (Elements.Ref touch : touches) {
                kinds.add(touch.kind());
                literals.add(touch.literal());
            }
            if (kinds.size() != 1) {
                throw new IllegalArgumentException(PyRepr.reprString(part)
                        + ": one row's keys are all IDs or all XPaths, as in PATS");
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("promptWebElement", prompt.literal());
            row.put("promptText", promptText.strip());
            row.put("promptElementLocatorType", prompt.kind());
            row.put("touchActionLocatorType", kinds.iterator().next());
            row.put("touchActionWebElements", String.join(",", literals));
            rows.add(row);
        }
        return json(rows);
    }

    /**
     * The rows as the {@code PROMPTS} JSON array: each row's columns in {@link ScenarioParser#PROMPT_COLUMNS} order
     * and trimmed, the touch list with no spaces after its commas and no empty entries. The one writer a
     * {@code .scenario} and a {@code .feature} share.
     */
    static String json(List<Map<String, String>> rows) {
        return ScenarioParser.dynamicPromptsField(rows);
    }

    private static String required(JsonNode row, String key) {
        JsonNode node = row.get(key);
        if (node == null) {
            throw new IllegalArgumentException("a prompt row with no " + key);
        }
        return node.asText();
    }
}
