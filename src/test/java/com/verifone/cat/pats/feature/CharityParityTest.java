package com.verifone.cat.pats.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Parity with the Python on real Charity cases: each {@code .feature} under {@code src/test/resources/pats/feature/}
 * (copied from the Python CAT's {@code tests/suites/charity_combined} and {@code charity_outdoor}, the PATS-phrase
 * Charity plans) reads to the steps, tags and actions the Python reads. {@code charity_pats_actions.json} holds the
 * Python's own output, from running {@code petrotest.pats.feature.scenario_actions} on the same files, so a
 * difference here is a difference from the Python. UNVERIFIED AGAINST HARDWARE.
 */
class CharityParityTest {

    private static final String ROOT = "/pats/feature/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode golden() throws IOException {
        try (InputStream in = CharityParityTest.class.getResourceAsStream(ROOT + "charity_pats_actions.json")) {
            return JSON.readTree(in);
        }
    }

    private static Path feature(String key) throws IOException {
        try {
            return Path.of(CharityParityTest.class.getResource(ROOT + key).toURI());
        } catch (URISyntaxException exc) {
            throw new IOException("bad resource name " + key, exc);
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static Action actionOf(JsonNode node) {
        List<Action.Field> fields = new ArrayList<>();
        for (JsonNode pair : node.get("fields")) {
            fields.add(new Action.Field(pair.get(0).asText(), pair.get(1).asText()));
        }
        return new Action(node.get("name").asText(), fields, node.get("index").asInt());
    }

    @Test
    void theGoldenHoldsEveryCharityFeatureAndEachIsOnDisk() throws IOException {
        JsonNode golden = golden();
        assertEquals(38, golden.size());
        Iterator<String> keys = golden.fieldNames();
        while (keys.hasNext()) {
            assertTrue(Files.exists(feature(keys.next())));
        }
    }

    @Test
    void eachFeatureReadsToThePythonsStepsTagsAndActions() throws IOException {
        JsonNode golden = golden();
        Iterator<String> keys = golden.fieldNames();
        int actions = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode expected = golden.get(key);
            Gherkin.Feature feature = Gherkin.read(feature(key));
            Gherkin.Scenario scenario = feature.scenarios().get(0);
            assertEquals(names(expected.get("feature_tags")), new TreeSet<>(feature.tags()), key);
            assertEquals(names(expected.get("scenario_tags")), new TreeSet<>(scenario.tags()), key);
            assertEquals(strings(expected.get("steps")), scenario.steps(), key);

            boolean bdd = feature.tags().contains(PatsFeature.BDD_TAG)
                    || scenario.tags().contains(PatsFeature.BDD_TAG);
            if (expected.has("none")) {
                assertEquals(Optional.empty(), PatsFeature.scenarioActions(scenario.steps(), bdd), key);
                continue;
            }
            List<Action> read = PatsFeature.scenarioActions(scenario.steps(), bdd)
                    .orElseThrow(() -> new AssertionError(key + ": Python reads actions, Java reads none"));
            assertEquals(expected.get("actions").size(), read.size(), key);
            for (int i = 0; i < read.size(); i++) {
                JsonNode want = expected.get("actions").get(i);
                assertEquals(want.get("name").asText(), read.get(i).name(), key);
                assertEquals(want.get("index").asInt(), read.get(i).index(), key);
                assertEquals(actionOf(want).fields(), read.get(i).fields(), key + " action " + i);
                actions++;
            }
        }
        assertEquals(337, actions);
    }

    @Test
    void everyCharityActionRendersToAPhraseThatReadsBackTheSame() throws IOException {
        JsonNode golden = golden();
        Iterator<String> keys = golden.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            JsonNode expected = golden.get(key);
            if (!expected.has("actions")) {
                continue;
            }
            for (JsonNode node : expected.get("actions")) {
                Action action = actionOf(node);
                String line = PatsFeature.render(action);
                assertEquals(Fields.canonical(action), Fields.canonical(PatsFeature.buildLine(line, action.index())),
                        key + ": " + line);
            }
        }
    }
}
