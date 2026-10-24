package com.verifone.cat.pats.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Gherkin {@code .feature} for the PATS phrases: the tags, and each scenario's step texts, as pytest-bdd's parser
 * gives them to {@code petrotest.pats.feature}.
 *
 * <p>Hand-written, with no Cucumber dependency. What it reads: {@code @tag} lines (before a {@code Feature:} or a
 * scenario, they apply to it), {@code #} comments, {@code Feature:} and its description, {@code Scenario:},
 * {@code Scenario Outline:} and {@code Example:} (an outline's steps are read as written, with no example substituted,
 * as the Python reads them), {@code Given/When/Then/And/But/*} steps (the keyword stripped), and the
 * {@code Background:} section, whose steps belong to no scenario and are left out, as pytest-bdd keeps them apart. Data
 * tables ({@code |} rows) and doc strings are attached to a step in pytest-bdd and do not change its name, so they are
 * skipped here. {@code Examples:} blocks are skipped, as the Python never reads them. UNVERIFIED AGAINST HARDWARE.
 */
public final class Gherkin {

    /**
     * One scenario: its name, its tags (without the {@code @}), its steps' texts in order, and whether it is an outline.
     */
    public record Scenario(String name, Set<String> tags, List<String> steps, boolean outline) {
        public Scenario {
            tags = Set.copyOf(tags);
            steps = List.copyOf(steps);
        }
    }

    /** A feature: its tags and its scenarios in file order. */
    public record Feature(String name, Set<String> tags, List<Scenario> scenarios) {
        public Feature {
            tags = Set.copyOf(tags);
            scenarios = List.copyOf(scenarios);
        }
    }

    private static final Pattern STEP = Pattern.compile("^(Given|When|Then|And|But|\\*)\\s+(.*)$");
    private static final Pattern SCENARIO = Pattern.compile("^(Scenario Outline|Scenario Template|Scenario|Example):(.*)$");

    private Gherkin() {}

    /** The feature in {@code path}, read as UTF-8. */
    public static Feature read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** The feature {@code text} holds. */
    public static Feature parse(String text) {
        String featureName = "";
        Set<String> featureTags = Collections.emptySet();
        Set<String> pending = new LinkedHashSet<>();
        List<ScenarioBuilder> scenarios = new ArrayList<>();
        ScenarioBuilder current = null;
        boolean inBackground = false;
        boolean inDocString = false;
        for (String raw : text.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (inDocString) {
                inDocString = !line.startsWith("\"\"\"");
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("|")) {
                continue;
            }
            if (line.startsWith("\"\"\"")) {
                inDocString = true;
                continue;
            }
            if (line.startsWith("@")) {
                for (String token : line.split("\\s+")) {
                    if (token.startsWith("@") && token.length() > 1) {
                        pending.add(token.substring(1));
                    }
                }
                continue;
            }
            if (line.startsWith("Feature:")) {
                featureName = line.substring("Feature:".length()).strip();
                featureTags = new LinkedHashSet<>(pending);
                pending.clear();
                continue;
            }
            if (line.startsWith("Background:")) {
                inBackground = true;
                current = null;
                pending.clear();
                continue;
            }
            Matcher scenario = SCENARIO.matcher(line);
            if (scenario.matches()) {
                inBackground = false;
                current = new ScenarioBuilder(scenario.group(2).strip(), pending,
                        !scenario.group(1).equals("Scenario"));
                scenarios.add(current);
                pending.clear();
                continue;
            }
            if (line.startsWith("Examples:") || line.startsWith("Scenarios:")) {
                continue;
            }
            Matcher step = STEP.matcher(line);
            if (step.matches() && current != null && !inBackground) {
                current.steps.add(step.group(2).strip());
            }
        }
        List<Scenario> built = new ArrayList<>();
        for (ScenarioBuilder builder : scenarios) {
            built.add(new Scenario(builder.name, builder.tags, builder.steps, builder.outline));
        }
        return new Feature(featureName, featureTags, built);
    }

    private static final class ScenarioBuilder {
        final String name;
        final Set<String> tags;
        final List<String> steps = new ArrayList<>();
        final boolean outline;

        ScenarioBuilder(String name, Set<String> tags, boolean outline) {
            this.name = name;
            this.tags = new LinkedHashSet<>(tags);
            this.outline = outline;
        }
    }
}
