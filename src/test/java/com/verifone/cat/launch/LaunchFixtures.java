package com.verifone.cat.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.plans.PlanCatalogue;
import com.verifone.cat.plans.PlanSource;
import com.verifone.cat.plans.SeedPlanSource;
import com.verifone.cat.setup.SetupEnv;

/**
 * Test scaffolding for the launcher: every path a run reads or writes, under one {@code @TempDir}. No test here reads
 * {@code C:\CICD}, {@code C:\gitrepos\petroapps}, {@code ~/.cat} or the lab's rig files.
 *
 * <p>The setup environment is one in which the four required workstation items PASS: a Java 17 set with a
 * {@code javaw.exe} and a {@code java -version} that answers 17, a tools folder, a results folder, and a built
 * {@code target\cat-java.jar} with a {@code target\lib}. Everything else the audit looks at is absent, which is the
 * advisory state a fresh workstation is in.
 */
final class LaunchFixtures {

    static final String RIG = "bench";

    private static final ObjectMapper JSON = new ObjectMapper();

    private LaunchFixtures() {}

    /** Paths under {@code dir}, with {@code bench.yaml} copied into the rigs folder and no petroapps checkout. */
    static LaunchPaths paths(Path dir) throws IOException {
        return paths(dir, dir.resolve("petroapps"));
    }

    /** Paths under {@code dir}, with petroapps at {@code petroapps} (a fixture checkout, or a folder with none). */
    static LaunchPaths paths(Path dir, Path petroapps) throws IOException {
        Path rigs = Files.createDirectories(dir.resolve("rigs"));
        try (InputStream in = LaunchFixtures.class.getResourceAsStream("/launch/rigs/bench.yaml")) {
            if (in == null) {
                throw new IOException("missing test resource launch/rigs/bench.yaml");
            }
            Files.write(rigs.resolve(RIG + ".yaml"), in.readAllBytes());
        }
        Path results = Files.createDirectories(dir.resolve("results"));
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path tools = Files.createDirectories(dir.resolve("tools"));
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(petroapps);
        // The lab's layout: Java17\jdk-17.0.5+8\bin (the staging check finds javaw.exe only under a jdk* folder).
        Path javaBin = Files.createDirectories(tools.resolve("Java17").resolve("jdk-17.0.5+8").resolve("bin"));
        Files.write(javaBin.resolve("javaw.exe"), new byte[0]);
        Files.write(javaBin.resolve("java.exe"), new byte[0]);
        Path target = Files.createDirectories(repo.resolve("target"));
        Files.createDirectories(target.resolve("lib"));
        Files.write(target.resolve("cat-java.jar"), new byte[0]);
        Path leases = Files.createDirectories(dir.resolve("leases"));

        Map<String, String> vars = Map.of(
                "CAT_TOOLS_ROOT", tools.toString(),
                "CAT_RESULTS_DIR", results.toString(),
                "PETROAPPS_ROOT", petroapps.toString(),
                "PETROTEST_RIGS_DIR", rigs.toString(),
                "PLAYWRIGHT_BROWSERS_PATH", dir.resolve("browsers").toString());
        SetupEnv env = new SetupEnv(vars, home, repo,
                (argv, timeout) -> new SetupEnv.CommandResult(0, "", "openjdk version \"17.0.5\" 2022-10-18"));
        PlanCatalogue catalogue = new PlanCatalogue(dir.resolve("cache"), dir.resolve("python-cache"),
                PlanSource.unavailable("no Atlassian source in a test"), new SeedPlanSource(), repo);
        return new LaunchPaths(results, rigs, leases, repo, petroapps, catalogue, env);
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
