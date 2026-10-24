package com.verifone.cat.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test scaffolding for the launcher: a results root, a rigs folder holding {@code bench.yaml}, and a
 * lease folder, all under a {@code @TempDir}. No test here reads {@code C:\CICD} or the lab's rigs.
 */
final class LaunchFixtures {

    static final String RIG = "bench";

    private static final ObjectMapper JSON = new ObjectMapper();

    private LaunchFixtures() {}

    /** Paths under {@code dir}, with {@code bench.yaml} copied into the rigs folder. */
    static LaunchPaths paths(Path dir) throws IOException {
        Path rigs = Files.createDirectories(dir.resolve("rigs"));
        try (InputStream in = LaunchFixtures.class.getResourceAsStream("/launch/rigs/bench.yaml")) {
            if (in == null) {
                throw new IOException("missing test resource launch/rigs/bench.yaml");
            }
            Files.write(rigs.resolve(RIG + ".yaml"), in.readAllBytes());
        }
        return new LaunchPaths(
                Files.createDirectories(dir.resolve("results")),
                rigs,
                Files.createDirectories(dir.resolve("leases")));
    }

    /** Every event line of a run's {@code events.ndjson}, parsed. */
    static List<JsonNode> events(Path runFolder) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(runFolder.resolve("events.ndjson"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(JSON.readTree(line));
            }
        }
        return out;
    }

    /** The {@code type} of each event, in order. */
    static List<String> types(List<JsonNode> events) {
        List<String> out = new ArrayList<>();
        events.forEach(event -> out.add(event.get("type").asText()));
        return out;
    }
}
