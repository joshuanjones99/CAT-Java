package com.verifone.cat.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code .feature} read with the line of every tag, step, scenario and table, which the checker reports by.
 *
 * <p>Hand-written, for the checker alone: the PATS reader in {@code pats.feature.Gherkin} keeps no line numbers and no
 * tables. What it reads: {@code @tag} lines (before a {@code Feature:}, a {@code Background:}, a scenario or an examples
 * block, they apply to the next one), {@code #} comments, the feature's description, {@code Background:}, {@code Scenario:},
 * {@code Scenario Outline:} (and {@code Scenario Template:}), {@code Example:}, {@code Rule:}, {@code Examples:} and
 * {@code Scenarios:} blocks with their header and rows, {@code Given/When/Then/And/But/*} steps, doc strings and data
 * tables attached to the step before them.
 *
 * <p>Errors: the first line the reader cannot place stops it, as the official parser does with {@code stop_at_first_error}.
 * Its message follows the official's {@code expected: ..., got '...'} shape, but its list of expected kinds is this
 * reader's, so the wording differs from the official parser's where the states differ.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: a reader of text only.
 */
final class FeatureDocument {

    /** A tag, without its {@code @}, and the line it is on (1-based). */
    record Tag(String name, int line) {}

    /**
     * A step: its keyword ({@code Given}, {@code *}, ...), its text, its line, and whether a doc string or a data table
     * follows it (the one thing the checker refuses: no phrase takes an argument).
     */
    record Step(String keyword, String text, int line, boolean argument) {}

    /** An examples block: its header cells and its body rows, each row as many cells as the table had. */
    record Examples(int line, List<String> header, List<List<String>> rows) {}

    /** A scenario: its keyword, name, line, tags, steps, examples. {@code outline} is set for a Scenario Outline. */
    record Scenario(String keyword, String name, int line, List<Tag> tags, List<Step> steps, List<Examples> examples,
            boolean outline) {}

    /**
     * A feature: its name, line and tags; its background's steps (null when it has none); its scenarios, in file order
     * (those under a {@code Rule:} are not here, as the official parser keeps them under the rule); and the lines of the
     * {@code Rule:} keywords, which the checker refuses.
     */
    record Feature(String name, int line, List<Tag> tags, List<Step> background, int backgroundLine,
            List<Scenario> scenarios, List<Integer> ruleLines) {}

    /** The reader's first error, on a line. */
    static final class ParseError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int line;

        ParseError(int line, String message) {
            super(message);
            this.line = line;
        }
    }

    private static final Pattern STEP = Pattern.compile("^(Given|When|Then|And|But|\\*)\\s+(.*)$");
    private static final Pattern SCENARIO = Pattern.compile("^(Scenario Outline|Scenario Template|Scenario|Example):(.*)$");
    private static final String EXPECTED_TOP = "#EOF, #Language, #TagLine, #FeatureLine, #Comment, #Empty";
    private static final String EXPECTED_STEP = "#EOF, #StepLine, #TableRow, #DocStringSeparator, #TagLine, "
            + "#ScenarioLine, #RuleLine, #ExamplesLine, #Comment, #Empty";

    private enum State { HEADER, BACKGROUND, SCENARIO, EXAMPLES, RULE }

    /** A step as it is being read: its argument flag set when a table or doc string follows. */
    private static final class StepBuilder {
        final String keyword;
        final String text;
        final int line;
        boolean argument;

        StepBuilder(String keyword, String text, int line) {
            this.keyword = keyword;
            this.text = text;
            this.line = line;
        }

        Step build() {
            return new Step(keyword, text, line, argument);
        }
    }

    /** A scenario as it is being read. */
    private static final class ScenarioBuilder {
        final String keyword;
        final String name;
        final int line;
        final List<Tag> tags;
        final List<StepBuilder> steps = new ArrayList<>();
        final List<ExamplesBuilder> examples = new ArrayList<>();
        final boolean outline;

        ScenarioBuilder(String keyword, String name, int line, List<Tag> tags, boolean outline) {
            this.keyword = keyword;
            this.name = name;
            this.line = line;
            this.tags = tags;
            this.outline = outline;
        }

        Scenario build() {
            List<Step> built = new ArrayList<>();
            for (StepBuilder step : steps) {
                built.add(step.build());
            }
            List<Examples> blocks = new ArrayList<>();
            for (ExamplesBuilder block : examples) {
                blocks.add(new Examples(block.line, block.header, block.rows));
            }
            return new Scenario(keyword, name, line, tags, built, blocks, outline);
        }
    }

    /** An examples block as it is being read. */
    private static final class ExamplesBuilder {
        final int line;
        List<String> header = List.of();
        final List<List<String>> rows = new ArrayList<>();
        boolean headerRead;

        ExamplesBuilder(int line) {
            this.line = line;
        }
    }

    private FeatureDocument() {}

    /**
     * The feature {@code text} holds.
     *
     * @throws ParseError at the first line the reader cannot place, or when there is no {@code Feature:} line
     */
    static Feature parse(String text) {
        String featureName = null;
        int featureLine = 0;
        List<Tag> featureTags = new ArrayList<>();
        List<Tag> pending = new ArrayList<>();
        List<StepBuilder> backgroundSteps = null;
        int backgroundLine = 0;
        List<ScenarioBuilder> scenarios = new ArrayList<>();
        List<Integer> ruleLines = new ArrayList<>();
        ScenarioBuilder current = null;
        ExamplesBuilder examples = null;
        State state = null;
        boolean descriptionAllowed = false;
        boolean inDocString = false;
        StepBuilder lastStep = null;
        int lineNo = 0;
        for (String raw : text.split("\\r?\\n", -1)) {
            lineNo++;
            String line = raw.strip();
            if (inDocString) {
                inDocString = !line.startsWith("\"\"\"");
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("\"\"\"")) {
                if (lastStep == null) {
                    throw new ParseError(lineNo, unexpected(line, state == null ? EXPECTED_TOP : EXPECTED_STEP));
                }
                lastStep.argument = true;
                inDocString = true;
                descriptionAllowed = false;
                continue;
            }
            if (line.startsWith("|")) {
                if (state == State.EXAMPLES && examples != null) {
                    readRow(examples, line, lineNo);
                    descriptionAllowed = false;
                    continue;
                }
                if (lastStep == null) {
                    throw new ParseError(lineNo, unexpected(line, EXPECTED_STEP));
                }
                lastStep.argument = true;
                descriptionAllowed = false;
                continue;
            }
            if (line.startsWith("@")) {
                for (String token : line.split("\\s+")) {
                    if (token.startsWith("@") && token.length() > 1) {
                        pending.add(new Tag(token.substring(1), lineNo));
                    }
                }
                continue;
            }
            if (line.startsWith("Feature:")) {
                if (featureName != null) {
                    throw new ParseError(lineNo, unexpected(line, EXPECTED_STEP));
                }
                featureName = line.substring("Feature:".length()).strip();
                featureLine = lineNo;
                featureTags = new ArrayList<>(pending);
                pending.clear();
                state = State.HEADER;
                descriptionAllowed = true;
                continue;
            }
            if (featureName == null) {
                throw new ParseError(lineNo, unexpected(line, EXPECTED_TOP));
            }
            if (line.startsWith("Background:")) {
                backgroundSteps = new ArrayList<>();
                backgroundLine = lineNo;
                pending.clear();
                state = State.BACKGROUND;
                current = null;
                examples = null;
                lastStep = null;
                descriptionAllowed = true;
                continue;
            }
            Matcher scenario = SCENARIO.matcher(line);
            if (scenario.matches()) {
                String keyword = scenario.group(1);
                current = new ScenarioBuilder(keyword, scenario.group(2).strip(), lineNo, new ArrayList<>(pending),
                        !keyword.equals("Scenario") && !keyword.equals("Example"));
                pending.clear();
                if (state != State.RULE) {
                    scenarios.add(current);
                }
                state = State.SCENARIO;
                examples = null;
                lastStep = null;
                descriptionAllowed = true;
                continue;
            }
            if (line.startsWith("Rule:")) {
                ruleLines.add(lineNo);
                pending.clear();
                state = State.RULE;
                current = null;
                examples = null;
                lastStep = null;
                descriptionAllowed = true;
                continue;
            }
            if (line.startsWith("Examples:") || line.startsWith("Scenarios:")) {
                if (current == null || state == State.BACKGROUND) {
                    throw new ParseError(lineNo, unexpected(line, EXPECTED_STEP));
                }
                pending.clear();
                examples = new ExamplesBuilder(lineNo);
                current.examples.add(examples);
                state = State.EXAMPLES;
                lastStep = null;
                descriptionAllowed = true;
                continue;
            }
            Matcher step = STEP.matcher(line);
            if (step.matches()) {
                if (state == State.EXAMPLES || state == null || state == State.RULE) {
                    throw new ParseError(lineNo, unexpected(line, EXPECTED_STEP));
                }
                StepBuilder built = new StepBuilder(step.group(1), step.group(2).strip(), lineNo);
                if (state == State.BACKGROUND) {
                    backgroundSteps.add(built);
                } else if (current != null) {
                    current.steps.add(built);
                } else {
                    throw new ParseError(lineNo, unexpected(line, EXPECTED_STEP));
                }
                lastStep = built;
                descriptionAllowed = false;
                continue;
            }
            if (descriptionAllowed) {
                continue;
            }
            throw new ParseError(lineNo, unexpected(line, EXPECTED_STEP));
        }
        if (featureName == null) {
            throw new ParseError(1, "no Feature: line");
        }
        List<Scenario> built = new ArrayList<>();
        for (ScenarioBuilder builder : scenarios) {
            built.add(builder.build());
        }
        List<Step> background = null;
        if (backgroundSteps != null) {
            background = new ArrayList<>();
            for (StepBuilder step : backgroundSteps) {
                background.add(step.build());
            }
        }
        return new Feature(featureName, featureLine, featureTags, background, backgroundLine, built, ruleLines);
    }

    private static void readRow(ExamplesBuilder block, String line, int lineNo) {
        List<String> cells = new ArrayList<>();
        String body = line.substring(1);
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String cell : body.split("(?<!\\\\)\\|", -1)) {
            cells.add(cell.strip().replace("\\|", "|"));
        }
        if (!block.headerRead) {
            block.header = cells;
            block.headerRead = true;
        } else {
            block.rows.add(cells);
        }
    }

    private static String unexpected(String line, String expected) {
        return "expected: " + expected + ", got " + pyQuote(line);
    }

    private static String pyQuote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** The rows of an examples block keyed by its header, as the checker reads them ({@code zip(header, row)}). */
    static List<Map<String, String>> rowsOf(Examples block) {
        List<Map<String, String>> out = new ArrayList<>();
        for (List<String> row : block.rows()) {
            Map<String, String> keyed = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(block.header().size(), row.size()); i++) {
                keyed.put(block.header().get(i), row.get(i));
            }
            out.add(keyed);
        }
        return out;
    }
}
