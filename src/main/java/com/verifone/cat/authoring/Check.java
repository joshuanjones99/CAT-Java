package com.verifone.cat.authoring;

import com.verifone.cat.authoring.FeatureDocument.Examples;
import com.verifone.cat.authoring.FeatureDocument.Feature;
import com.verifone.cat.authoring.FeatureDocument.Scenario;
import com.verifone.cat.authoring.FeatureDocument.Step;
import com.verifone.cat.authoring.FeatureDocument.Tag;
import com.verifone.cat.validate.PyRepr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is this {@code .feature} an authored case CAT will run? Every reason it is not, by line. Port of
 * {@code petrotest.authoring.check}.
 *
 * <p><b>Errors</b> (the case is refused): {@code parse} (not Gherkin, or no {@code Feature:} line; see
 * {@link FeatureDocument} for the wording), {@code name} (the file is not {@code PETROCORE-<n>-TestCase.feature}),
 * {@code one-case}, {@code tag}, {@code unbound}, {@code ambiguous}, {@code placeholder}, {@code argument} (a data table or
 * doc string) and {@code empty}.
 *
 * <p><b>Warnings</b> (the case runs, but is not ready to publish): {@code ui-level}, {@code pats-level} and {@code long}
 * (more than {@link #MAX_STEPS} steps).
 *
 * <p>UNVERIFIED AGAINST HARDWARE: this reads a case's text against the library's phrases; it runs nothing.
 */
public final class Check {

    /** A scenario longer than this draws a warning: probably two cases, or keystrokes rather than intent. */
    public static final int MAX_STEPS = 15;

    /** Tags a case may carry besides its {@code @PETROCORE-<n>}. {@code pats_bdd} marks a PATS case written from BDD. */
    public static final Set<String> ALLOWED_TAGS = Set.of("plan_end", "unmigrated", "pats_bdd");

    /** Tags that name a group of cases for {@code --tags}, and mean nothing to pytest (the Python's {@code GROUP_TAGS}). */
    public static final Set<String> GROUP_TAGS = Set.of(
            "fepsim", "fep_setup", "wex_otr", "comdata", "fleet_code", "mudflap", "propfleet", "incomm",
            "payware_fleet", "quikq", "multiservice", "cullinan", "full_service_attendant");

    private static final Pattern JIRA_TAG = Pattern.compile("^PETROCORE-(\\d+)$");
    private static final Pattern FILE = Pattern.compile("^PETROCORE-(\\d+)-TestCase\\.feature$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([^<>]+)>");

    /** One finding: the line, its severity ({@code error} or {@code warning}), its code and its message. */
    public record Diagnostic(int line, String severity, String code, String message) {

        /** {@code path:line: severity [code] message}, or {@code line N: ...} when there is no path. */
        public String render(String path) {
            String where = path == null || path.isEmpty() ? "line " + line : path + ":" + line;
            return where + ": " + severity + " [" + code + "] " + message;
        }
    }

    /** A step line and the phrase it binds to (null when unbound, ambiguous or a placeholder with no column). */
    public record StepLine(int line, String keyword, String text, StepDef step) {}

    /** The result of checking one {@code .feature}. */
    public static final class Analysis {
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<StepLine> steps = new ArrayList<>();
        final List<String> scenarios = new ArrayList<>();
        String feature = "";
        String key;

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        public List<Diagnostic> errors() {
            return bySeverity("error");
        }

        public List<Diagnostic> warnings() {
            return bySeverity("warning");
        }

        private List<Diagnostic> bySeverity(String severity) {
            List<Diagnostic> out = new ArrayList<>();
            for (Diagnostic d : diagnostics) {
                if (d.severity().equals(severity)) {
                    out.add(d);
                }
            }
            return out;
        }

        public boolean ok() {
            return errors().isEmpty();
        }

        public List<StepLine> steps() {
            return steps;
        }

        /** The scenarios' names, in file order. */
        public List<String> scenarios() {
            return scenarios;
        }

        /** The feature's name. */
        public String feature() {
            return feature;
        }

        /** {@code PETROCORE-<n>} from the case's tag, or null when there is none. */
        public String key() {
            return key;
        }

        /** The fixtures the bound steps use, each once, in first-use order: what a reviewer reads as "what it touches". */
        public List<String> fixtures() {
            Set<String> seen = new LinkedHashSet<>();
            for (StepLine line : steps) {
                if (line.step() != null) {
                    seen.addAll(line.step().fixtures());
                }
            }
            return new ArrayList<>(seen);
        }

        private void add(int line, String severity, String code, String message) {
            diagnostics.add(new Diagnostic(line, severity, code, message));
        }
    }

    private Check() {}

    /** {@link #analyse(String, String, List)} against the whole library. */
    public static Analysis analyse(String text, String filename) {
        return analyse(text, filename, StepLibrary.steps());
    }

    /**
     * Check one {@code .feature}'s text. {@code filename} (a name, not a path) enables the name rules; null skips them.
     */
    public static Analysis analyse(String text, String filename, List<StepDef> steps) {
        Analysis analysis = new Analysis();
        String number = null;
        if (filename != null) {
            Matcher found = FILE.matcher(filename);
            if (!found.matches()) {
                analysis.add(1, "error", "name", PyRepr.reprString(filename)
                        + " is not PETROCORE-<n>-TestCase.feature (rig owner, 2026-10-07)");
            } else {
                number = found.group(1);
            }
        }
        Feature feature;
        try {
            feature = FeatureDocument.parse(text);
        } catch (FeatureDocument.ParseError error) {
            analysis.add(error.line, "error", "parse", error.getMessage());
            return analysis;
        }
        analysis.feature = feature.name();
        List<Tag> tags = new ArrayList<>(feature.tags());
        if (feature.background() != null) {
            checkScenario(feature.background(), List.of(), feature.backgroundLine(), steps, analysis, true);
        }
        for (int ruleLine : feature.ruleLines()) {
            analysis.add(ruleLine, "error", "one-case", "Rule: is not used here");
        }
        List<Scenario> scenarios = feature.scenarios();
        if (scenarios.size() != 1) {
            analysis.add(1, "error", "one-case",
                    scenarios.size() + " scenarios: an authored case is exactly one Scenario per file");
        }
        for (Scenario node : scenarios) {
            analysis.scenarios.add(node.name());
            if (node.name().isBlank()) {
                analysis.add(node.line(), "error", "name", "the scenario has no name");
            }
            tags.addAll(node.tags());
            checkScenario(node.steps(), node.examples(), node.line(), steps, analysis, false);
        }
        checkTags(tags, number, analysis);
        analysis.diagnostics.sort((x, y) -> {
            int byLine = Integer.compare(x.line(), y.line());
            return byLine != 0 ? byLine : Boolean.compare(!x.severity().equals("error"), !y.severity().equals("error"));
        });
        return analysis;
    }

    /** Each {@code .feature} under {@code paths} (files or directories), analysed, in path order. */
    public static Map<Path, Analysis> checkFiles(List<Path> paths) throws IOException {
        Map<Path, Analysis> out = new LinkedHashMap<>();
        for (Path path : paths) {
            List<Path> files = new ArrayList<>();
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    walk.filter(p -> p.getFileName().toString().endsWith(".feature") && Files.isRegularFile(p))
                            .sorted().forEach(files::add);
                }
            } else {
                files.add(path);
            }
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                out.put(file, analyse(text, file.getFileName().toString()));
            }
        }
        return out;
    }

    /**
     * The library phrases closest to {@code text}, for an unbound line's message: its placeholders shown as {@code …}.
     */
    public static List<String> suggest(String text, List<StepDef> steps, int n) {
        return StepLibrary.closest(text, steps, n);
    }

    private static void checkTags(List<Tag> tags, String number, Analysis analysis) {
        List<Tag> keys = new ArrayList<>();
        List<String> keyNumbers = new ArrayList<>();
        for (Tag tag : tags) {
            Matcher found = JIRA_TAG.matcher(tag.name());
            if (found.matches()) {
                keys.add(tag);
                keyNumbers.add(found.group(1));
            } else if (!ALLOWED_TAGS.contains(tag.name()) && !GROUP_TAGS.contains(tag.name())) {
                List<String> allowed = new ArrayList<>(new TreeSet<>(ALLOWED_TAGS));
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < allowed.size(); i++) {
                    names.append(i == 0 ? "" : ", ").append('@').append(allowed.get(i));
                }
                analysis.add(tag.line(), "error", "tag", "@" + tag.name() + " is not a case's key, one of " + names
                        + " or a group (GROUP_TAGS)");
            }
        }
        if (keys.isEmpty()) {
            analysis.add(1, "error", "tag", "no @PETROCORE-<n> tag: the case's Jira key");
            return;
        }
        if (new TreeSet<>(keyNumbers).size() > 1) {
            analysis.add(keys.get(1).line(), "error", "tag", "more than one @PETROCORE-<n> tag");
        }
        Tag first = keys.get(0);
        String key = keyNumbers.get(0);
        analysis.key = "PETROCORE-" + key;
        if (number != null && !key.equals(number)) {
            analysis.add(first.line(), "error", "tag",
                    "@PETROCORE-" + key + " disagrees with the file name's PETROCORE-" + number);
        }
    }

    private static void checkStep(int line, String keyword, String text, List<StepDef> steps, Analysis analysis) {
        List<StepDef> found = StepLibrary.matches(text, steps);
        if (found.isEmpty()) {
            List<String> close = suggest(PLACEHOLDER.matcher(text).replaceAll("…"), steps, 3);
            String hint = close.isEmpty() ? "" : "; closest: " + String.join(" | ", close);
            analysis.add(line, "error", "unbound", "no phrase in the library says " + PyRepr.reprString(text) + hint);
        } else if (found.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (StepDef definition : found) {
                names.append(names.length() == 0 ? "" : ", ").append(definition.display());
            }
            analysis.add(line, "error", "ambiguous",
                    PyRepr.reprString(text) + " matches " + found.size() + " phrases: " + names);
        } else if (found.get(0).level().equals("ui")) {
            analysis.add(line, "warning", "ui-level", PyRepr.reprString(text)
                    + " names a screen control; ask a developer for a domain phrase");
        } else if (found.get(0).level().equals("pats")) {
            analysis.add(line, "warning", "pats-level", PyRepr.reprString(text)
                    + " is one PATS action, as a migrated case says it; a new case should say what the cashier means");
        }
        analysis.steps.add(new StepLine(line, keyword, text, found.size() == 1 ? found.get(0) : null));
    }

    private static void checkScenario(List<Step> lines, List<Examples> examples, int scenarioLine,
            List<StepDef> steps, Analysis analysis, boolean background) {
        if (lines.isEmpty() && !background) {
            analysis.add(scenarioLine, "error", "empty", "a scenario with no steps");
        }
        if (!background && lines.size() > MAX_STEPS) {
            analysis.add(scenarioLine, "warning", "long",
                    lines.size() + " steps (more than " + MAX_STEPS + "): two cases, or keystrokes, not intent?");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (Examples block : examples) {
            rows.addAll(FeatureDocument.rowsOf(block));
        }
        if (rows.isEmpty()) {
            rows.add(Map.of());
        }
        Set<String> columns = rows.get(0).keySet();
        for (Step raw : lines) {
            int line = raw.line();
            String keyword = raw.keyword().strip();
            String text = raw.text();
            if (raw.argument()) {
                analysis.add(line, "error", "argument", "no phrase takes a data table or doc string");
            }
            List<String> missing = new ArrayList<>();
            Matcher placeholder = PLACEHOLDER.matcher(text);
            while (placeholder.find()) {
                if (!columns.contains(placeholder.group(1))) {
                    missing.add(placeholder.group(1));
                }
            }
            if (!missing.isEmpty()) {
                analysis.add(line, "error", "placeholder",
                        "<" + String.join(">, <", missing) + "> has no Examples column");
                analysis.steps.add(new StepLine(line, keyword, text, null));
                continue;
            }
            int before = analysis.diagnostics.size();
            Set<String> bound = new LinkedHashSet<>();
            for (Map<String, String> row : rows) {
                String rendered = substitute(text, row);
                checkStep(line, keyword, rendered, steps, analysis);
                StepLine last = analysis.steps.remove(analysis.steps.size() - 1);
                if (last.step() != null) {
                    bound.add(last.step().text());
                }
            }
            dedupe(analysis, before);
            StepDef phrase = null;
            if (bound.size() == 1) {
                for (StepDef definition : steps) {
                    if (bound.contains(definition.text())) {
                        phrase = definition;
                        break;
                    }
                }
            }
            analysis.steps.add(new StepLine(line, keyword, text, phrase));
        }
    }

    /** {@code text} with each {@code <name>} replaced by the row's value for it, or left as written. */
    private static String substitute(String text, Map<String, String> row) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String value = row.get(m.group(1));
            out.append(value == null ? m.group(0) : value);
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** Keeps one copy of each diagnostic an outline's rows repeated (from {@code start} on). */
    private static void dedupe(Analysis analysis, int start) {
        List<Diagnostic> kept = new ArrayList<>(analysis.diagnostics.subList(0, start));
        Set<Diagnostic> seen = new LinkedHashSet<>();
        for (Diagnostic diagnostic : analysis.diagnostics.subList(start, analysis.diagnostics.size())) {
            if (seen.add(diagnostic)) {
                kept.add(diagnostic);
            }
        }
        analysis.diagnostics.clear();
        analysis.diagnostics.addAll(kept);
    }
}
