package com.verifone.cat.pats.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Gherkin {@code .feature} for the PATS phrases: the tags, and each scenario's step texts, as pytest-bdd's parser
 * gives them to {@code petrotest.pats.feature}.
 *
 * <p>Hand-written, with no Cucumber dependency. What it reads: {@code @tag} lines (before a {@code Feature:} or a
 * scenario, they apply to it), {@code #} comments, {@code Feature:} and its description, {@code Scenario:},
 * {@code Scenario Outline:} and {@code Example:} (an outline's {@link Scenario#steps} are read as written, with no
 * example substituted, as {@code petrotest.pats.feature} reads them), {@code Given/When/Then/And/But/*} steps (the
 * keyword stripped), and the {@code Background:} section, whose steps belong to no scenario and are left out, as
 * pytest-bdd keeps them apart. Data tables ({@code |} rows under a step) and doc strings are attached to a step in
 * pytest-bdd and do not change its name, so they are skipped here.
 *
 * <p>An outline's {@code Examples:} tables are read into {@link Scenario#examples}, one context per row, and
 * {@link Scenario#rendered} gives the scenario once per row with each {@code <name>} the row names substituted (one
 * left alone when the row does not name it), as pytest-bdd 9's {@code ScenarioTemplate.render} does; the run then
 * executes one case per row ({@code petrotest.authoring.plugin._rendered}). Each row's id is pytest-bdd's
 * {@code "-".join(row values)}, made unique as pytest makes duplicate ids unique. UNVERIFIED AGAINST HARDWARE.
 */
public final class Gherkin {

    /**
     * One scenario: its name, its tags (without the {@code @}), its steps' texts in order, whether it is an outline,
     * and an outline's Examples rows, each a map of column name to value in column order (empty for a scenario with
     * none).
     */
    public record Scenario(String name, Set<String> tags, List<String> steps, boolean outline,
            List<Map<String, String>> examples) {
        public Scenario {
            tags = Set.copyOf(tags);
            steps = List.copyOf(steps);
            List<Map<String, String>> rows = new ArrayList<>();
            for (Map<String, String> row : examples == null ? List.<Map<String, String>>of() : examples) {
                rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
            }
            examples = List.copyOf(rows);
        }

        /** A scenario with no Examples rows. */
        public Scenario(String name, Set<String> tags, List<String> steps, boolean outline) {
            this(name, tags, steps, outline, List.of());
        }

        /**
         * The scenario once per Examples row, or once as written when it has none: each step's {@code <name>}
         * replaced by the row's value when the row names it.
         */
        public List<Rendered> rendered() {
            if (examples.isEmpty()) {
                return List.of(new Rendered("", name, steps));
            }
            List<String> ids = exampleIds(examples);
            List<Rendered> out = new ArrayList<>();
            for (int i = 0; i < examples.size(); i++) {
                Map<String, String> row = examples.get(i);
                List<String> lines = new ArrayList<>();
                for (String step : steps) {
                    lines.add(render(step, row));
                }
                out.add(new Rendered(ids.get(i), render(name, row), lines));
            }
            return List.copyOf(out);
        }
    }

    /**
     * One rendered scenario: the parameter id pytest gives its test ({@code ""} for a scenario with no Examples), its
     * name and its steps with the row substituted.
     */
    public record Rendered(String id, String name, List<String> steps) {
        public Rendered {
            steps = List.copyOf(steps);
        }
    }

    /** pytest-bdd's {@code PARAM_RE}. */
    private static final Pattern PARAM = Pattern.compile("<(.+?)>");

    /** {@code text} with each {@code <name>} the row names replaced: pytest-bdd's {@code render_string}. */
    static String render(String text, Map<String, String> row) {
        Matcher m = PARAM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = row.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * pytest's ids for the rows: {@code "-".join(values)}, and where two rows give the same id each gets a counter
     * appended (after a {@code _} when the id ends in a digit), as pytest's id maker does.
     */
    static List<String> exampleIds(List<Map<String, String>> rows) {
        List<String> ids = new ArrayList<>();
        for (Map<String, String> row : rows) {
            ids.add(String.join("-", row.values()));
        }
        Map<String, Integer> counts = new HashMap<>();
        ids.forEach(id -> counts.merge(id, 1, Integer::sum));
        Map<String, Integer> next = new HashMap<>();
        List<String> unique = new ArrayList<>();
        for (String id : ids) {
            if (counts.get(id) > 1) {
                int n = next.merge(id, 1, Integer::sum) - 1;
                String suffix = !id.isEmpty() && Character.isDigit(id.charAt(id.length() - 1)) ? "_" : "";
                unique.add(id + suffix + n);
            } else {
                unique.add(id);
            }
        }
        return unique;
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
        boolean inExamples = false;
        List<String> header = null;
        for (String raw : text.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (inDocString) {
                inDocString = !line.startsWith("\"\"\"");
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("|")) {
                // An Examples table's rows; a step's data table is attached to the step and skipped.
                if (inExamples && current != null) {
                    List<String> cells = cells(line);
                    if (header == null) {
                        header = cells;
                    } else {
                        if (cells.size() != header.size()) {
                            throw new IllegalArgumentException("an Examples row of '" + current.name + "' has "
                                    + cells.size() + " cells where its header has " + header.size() + ": " + line);
                        }
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int i = 0; i < header.size(); i++) {
                            row.put(header.get(i), cells.get(i));
                        }
                        current.examples.add(row);
                    }
                }
                continue;
            }
            inExamples = false;
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
                // Its own table, its header first; tags above it are the examples' own, not the next scenario's.
                inExamples = true;
                header = null;
                pending.clear();
                continue;
            }
            Matcher step = STEP.matcher(line);
            if (step.matches() && current != null && !inBackground) {
                current.steps.add(step.group(2).strip());
            }
        }
        List<Scenario> built = new ArrayList<>();
        for (ScenarioBuilder builder : scenarios) {
            built.add(new Scenario(builder.name, builder.tags, builder.steps, builder.outline, builder.examples));
        }
        return new Feature(featureName, featureTags, built);
    }

    /** A table row's cells, stripped: {@code \|} is a pipe inside a cell, {@code \\} a backslash, {@code \n} a newline. */
    static List<String> cells(String line) {
        String body = line.strip();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean closed = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            closed = false;
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                cell.append(next == 'n' ? '\n' : next);
            } else if (c == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
                closed = true;
            } else {
                cell.append(c);
            }
        }
        if (!closed && !cell.toString().isBlank()) {
            cells.add(cell.toString().strip());
        }
        return cells;
    }

    private static final class ScenarioBuilder {
        final String name;
        final Set<String> tags;
        final List<String> steps = new ArrayList<>();
        final List<Map<String, String>> examples = new ArrayList<>();
        final boolean outline;

        ScenarioBuilder(String name, Set<String> tags, boolean outline) {
            this.name = name;
            this.tags = new LinkedHashSet<>(tags);
            this.outline = outline;
        }
    }
}
