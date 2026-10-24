package com.verifone.cat.devices.csite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceProtocolError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The two Commander Connect datasets CAT clears before a run: the smart suggestions and the tips. Ported from the Python
 * {@code clear_smart_suggestions}, {@code clear_tips} and their listing parsers in {@code petrotest.devices.csite}.
 *
 * <p>Smart suggestions: GET the dataset, then DELETE each sysid it lists (a 404 is already gone). Tips: DELETE returns
 * HTTP 500 on QAT (2026-10-20), so each enabled tip is PUT back as it reads with {@code enabled: false}, which is how
 * the Tipping plan itself ends.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: fake-backed tests only.
 */
public final class CSiteDatasets {

    /** The smart suggestions dataset of one Commander Connect service. */
    public static final String SMART_SUGGESTIONS = "pcms/v1/config/{service_id}/smartsuggestions";
    /** The tips dataset: the golden (onboarded, fresh) service has none enabled. */
    public static final String TIPS = "pcms/v1/config/{service_id}/tips";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<Integer> DELETED_OK = Set.of(200, 204, 404);
    private static final Set<Integer> PUT_OK = Set.of(200, 204);

    private CSiteDatasets() {
    }

    /**
     * Deletes every smart suggestion on {@code serviceId}.
     *
     * <p>Commander Connect is the golden dataset (rig owner, 2026-10-15): a suggestion a stopped Smart Suggestion run left
     * there is pushed to the Commander, and every later Total opens its upsell menu (HANDOFF.md §1.150).
     *
     * @return the sysids deleted, in listing order
     */
    public static List<Long> clearSmartSuggestions(CSiteClient client, String serviceId) {
        Objects.requireNonNull(client, "client");
        String endpoint = SMART_SUGGESTIONS.replace("{service_id}", serviceId);
        CSiteResponse listed = listing(client, endpoint);
        List<Long> deleted = new ArrayList<>();
        for (long sysid : smartSuggestionIds(listed.body())) {
            CSiteResponse gone = client.send("DELETE", endpoint + "/" + sysid, CSiteClient.ACCEPT_JSON, null, true);
            if (!DELETED_OK.contains(gone.status())) {
                throw new DeviceError("DELETE " + gone.url() + " answered HTTP " + gone.status());
            }
            deleted.add(sysid);
        }
        return deleted;
    }

    /**
     * Turns off every enabled tip configuration on {@code serviceId}, by PUTting each back disabled.
     *
     * @return the sysids turned off, in listing order
     */
    public static List<Long> clearTips(CSiteClient client, String serviceId) {
        Objects.requireNonNull(client, "client");
        String endpoint = TIPS.replace("{service_id}", serviceId);
        CSiteResponse listed = listing(client, endpoint);
        List<Long> off = new ArrayList<>();
        for (ObjectNode tip : tips(listed.body())) {
            if (!truthy(tip.get("enabled")) || !tip.has("sysid")) {
                continue;
            }
            long sysid = sysid(tip);
            ObjectNode disabled = tip.deepCopy();
            disabled.put("enabled", false);
            CSiteResponse put = client.send("PUT", endpoint + "/" + sysid,
                    jsonHeaders(), serialise(disabled), true);
            if (!PUT_OK.contains(put.status())) {
                throw new DeviceError("PUT " + put.url() + " answered HTTP " + put.status());
            }
            off.add(sysid);
        }
        return off;
    }

    /** The sysids in a smart suggestions GET: {@code config.smartSuggestionsConfig.smartSuggestions.smartSuggestion[]}. */
    public static List<Long> smartSuggestionIds(byte[] body) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : listedItems(body, "smart suggestions",
                "config", "smartSuggestionsConfig", "smartSuggestions", "smartSuggestion")) {
            if (item.has("sysid")) {
                ids.add(sysid(item));
            }
        }
        return ids;
    }

    /** The tip configurations in a tips GET: {@code config.tipConfig.tip[]}. */
    static List<ObjectNode> tips(byte[] body) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode item : listedItems(body, "tips", "config", "tipConfig", "tip")) {
            if (!item.isObject()) {
                throw new DeviceProtocolError("C-Site", "a tips listing CAT cannot read: a tip is not an object");
            }
            out.add((ObjectNode) item);
        }
        return out;
    }

    // -- helpers -----------------------------------------------------------------------------

    private static CSiteResponse listing(CSiteClient client, String endpoint) {
        CSiteResponse listed = client.send("GET", endpoint, CSiteClient.ACCEPT_JSON, null, true);
        if (listed.status() != 200) {
            throw new DeviceError("GET " + listed.url() + " answered HTTP " + listed.status());
        }
        return listed;
    }

    /**
     * The items of the list at {@code path} in a dataset GET. An absent or null list is empty (a clean service); a single
     * object is one item, as PATS reads it.
     */
    private static List<JsonNode> listedItems(byte[] body, String what, String... path) {
        JsonNode node;
        try {
            node = JSON.readTree(body == null || body.length == 0 ? "{}".getBytes() : body);
        } catch (IOException e) {
            throw new DeviceProtocolError("C-Site", "a " + what + " listing CAT cannot read: " + e.getMessage(), e);
        }
        for (String key : path) {
            node = node == null ? null : node.get(key);
            if (node == null) {
                throw new DeviceProtocolError("C-Site", "a " + what + " listing CAT cannot read: no " + key);
            }
        }
        List<JsonNode> items = new ArrayList<>();
        if (node.isNull()) {
            return items;
        }
        if (node.isObject()) {
            items.add(node);
        } else if (node.isArray()) {
            node.forEach(items::add);
        } else {
            throw new DeviceProtocolError("C-Site", "a " + what + " listing CAT cannot read: not a list");
        }
        return items;
    }

    private static long sysid(JsonNode item) {
        JsonNode value = item.get("sysid");
        if (value == null || value.isNull()) {
            throw new DeviceProtocolError("C-Site", "a listing CAT cannot read: a sysid is null");
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText().strip());
        } catch (NumberFormatException e) {
            throw new DeviceProtocolError("C-Site", "a listing CAT cannot read: sysid " + value.asText(), e);
        }
    }

    /** Python's truthiness for the JSON a tip carries. */
    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asDouble() != 0;
        }
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        return value.size() > 0;
    }

    private static Map<String, String> jsonHeaders() {
        return Map.of("Accept", "application/json", "Content-Type", "application/json");
    }

    private static String serialise(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (IOException e) {
            throw new DeviceProtocolError("C-Site", "a tip CAT cannot write: " + e.getMessage(), e);
        }
    }
}
