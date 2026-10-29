package com.verifone.cat.authoring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A stand-in for the {@code claude} CLI, run as a real process by {@link DraftTest}: the Python's {@code _STAND_IN}. It
 * refuses to answer (exit 9) if CAT broke a promise about how Claude is run, then answers as the mode says:
 * {@code good} (a clean case and one that needs a repair round), {@code error} (Claude Code reports an error),
 * {@code notjson} (exit 3, not JSON). Never a model.
 */
public final class FakeClaude {

    private FakeClaude() {}

    public static void main(String[] args) throws IOException {
        String mode = args[0];
        List<String> argv = Arrays.asList(args).subList(1, args.length);
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        ObjectMapper json = new ObjectMapper();
        if (mode.equals("notjson")) {
            System.out.println("not json");
            System.exit(3);
        }
        if (mode.equals("error")) {
            System.out.println("{\"is_error\": true, \"subtype\": \"error_during_execution\", \"result\": \"Not logged in\"}");
            return;
        }
        require(argv.subList(0, 3).equals(List.of("-p", "--output-format", "json")), "argv " + argv);
        require(argv.get(argv.indexOf("--tools") + 1).isEmpty(), "tools must be disabled");
        require(argv.contains("--no-session-persistence"), "no session persistence");
        JsonNode schema = json.readTree(argv.get(argv.indexOf("--json-schema") + 1));
        require(schema.get("required").toString().equals("[\"cases\",\"not_drafted\"]"), "schema " + schema);
        require(prompt.contains("<specification>") && prompt.contains("Round up for charity"), "the spec");
        require(prompt.contains("the cashier rings up PLU {plu}"), "the catalogue");
        boolean repair = prompt.contains("What CAT's checker said");
        Map<String, Object> good = new LinkedHashMap<>();
        good.put("title", "Cash sale accepts the round-up");
        good.put("covers", "AC 1");
        good.put("steps", List.of("Given the register is idle", "When the cashier rings up PLU 9999",
                "And the cashier totals the sale", "And the customer pays 11.00 in cash",
                "Then the receipt matches its baseline"));
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("title", "Card sale: declines");
        bad.put("steps", List.of("Given the register is idle", "When the customer pays by credit",
                repair ? "Then the receipt matches its baseline" : "And the customer waves the card"));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("cases", List.of(good, bad));
        answer.put("not_drafted", List.of(Map.of("title", "Loyalty discount",
                "missing_phrases", List.of("the customer identifies with loyalty ID {id}"), "why", "no phrase")));
        answer.put("notes", repair ? "repaired" : "first try");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "result");
        envelope.put("subtype", "success");
        envelope.put("is_error", false);
        envelope.put("result", json.writeValueAsString(answer));
        envelope.put("structured_output", answer);
        envelope.put("total_cost_usd", 0.25);
        envelope.put("modelUsage", Map.of("claude-test-model", Map.of("outputTokens", 9)));
        System.out.write((json.writeValueAsString(envelope) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            System.err.println("broken promise: " + what);
            System.exit(9);
        }
    }
}
