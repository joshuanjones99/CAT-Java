package com.verifone.cat.pats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pats.feature.Gherkin;
import com.verifone.cat.pats.feature.LibrarySteps;
import com.verifone.cat.pats.feature.PatsFeature;
import com.verifone.cat.pats.runner.RunnableCase;

/**
 * Reads one PATS case from a file, as the runner executes it: a {@code -TestCase.xml}, a BDD {@code .scenario} or a
 * {@code .feature}. The dispatch is by file type, as the Python's {@code petrotest.pats.case} loads a case: XML through
 * {@link CaseParser}, a scenario through {@link ScenarioParser}, a {@code .feature} through {@code pats.feature}.
 *
 * <p>The case's key is the {@code PETROCORE-<n>} in its file name, else in the nearest folder that names one; a file
 * with none takes its base name, without a {@code -TestCase} suffix. The source is the file's absolute path, for the
 * runner's log line. Reads the filesystem only: the git-blob store that petroapps' cases come from is a later slice.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PatsCaseLoader {

    private static final Pattern CASE_KEY = Pattern.compile("PETROCORE-\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX = Pattern.compile("-TestCase.*$", Pattern.CASE_INSENSITIVE);
    private static final char BYTE_ORDER_MARK = '﻿';

    private PatsCaseLoader() {
    }

    /**
     * The case in {@code file}, by its type: {@code .xml} (a PATS {@code -TestCase.xml}), {@code .scenario} (a BDD
     * case) or {@code .feature} (one scenario of PATS or step-library phrases). For a Scenario Outline with Examples,
     * the first row's case; {@link #loadAll} gives every row's.
     *
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException when the file is none of those types, or does not parse as one
     */
    public static RunnableCase load(Path file) throws IOException {
        return loadAll(file).get(0).kase();
    }

    /**
     * One case a file holds: the parameter id pytest gives its test ({@code ""} for a file of one case; an Outline's
     * Examples row's id otherwise) and the case.
     */
    public record Variant(String id, RunnableCase kase) {
    }

    /**
     * Every case in {@code file}: one, except for a {@code .feature} Scenario Outline with Examples, which is one case
     * per Examples row with that row substituted into its steps, as pytest-bdd runs one test per row
     * ({@code petrotest.authoring.plugin._rendered}). {@link #load} is the first of them.
     *
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException when the file is none of the case types, or does not parse as one
     */
    public static List<Variant> loadAll(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        String name = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        String key = keyOf(absolute);
        if (name.endsWith(".feature")) {
            Gherkin.Feature feature = Gherkin.read(absolute);
            if (feature.scenarios().size() != 1) {
                throw new IllegalArgumentException(absolute.getFileName() + ": expected one scenario, found "
                        + feature.scenarios().size());
            }
            List<Variant> variants = new java.util.ArrayList<>();
            for (Gherkin.Rendered rendered : feature.scenarios().get(0).rendered()) {
                variants.add(new Variant(rendered.id(), featureCase(feature, rendered).withSource(key,
                        absolute.toString())));
            }
            return List.copyOf(variants);
        }
        ParsedCase parsed;
        if (name.endsWith(".xml")) {
            parsed = CaseParser.parseActions(Files.readAllBytes(absolute));
        } else if (name.endsWith(".scenario")) {
            parsed = ScenarioParser.parseScenario(text(absolute));
        } else {
            throw new IllegalArgumentException(absolute.getFileName() + " is not a PATS case: expected a"
                    + " -TestCase.xml, a .scenario or a .feature");
        }
        return List.of(new Variant("", parsed.withSource(key, absolute.toString())));
    }

    /** One rendered scenario of a {@code .feature}'s one scenario as a case, titled by its (rendered) name. */
    private static ParsedCase featureCase(Gherkin.Feature feature, Gherkin.Rendered rendered) {
        Gherkin.Scenario scenario = feature.scenarios().get(0);
        boolean bddCase = feature.tags().contains(PatsFeature.BDD_TAG) || scenario.tags().contains(PatsFeature.BDD_TAG);
        List<Action> actions;
        if (!scenario.examples().isEmpty()) {
            // An Outline with Examples is an authored case, never a migrated PATS one (the Python's _pats_actions).
            actions = LibrarySteps.actions(rendered.steps());
        } else {
            // A scenario of PATS action phrases is a PATS case; one of the step library's phrases is run step by step
            // (pats.feature.LibrarySteps), as the Python's authoring plugin binds every library phrase for pytest-bdd.
            actions = PatsFeature.scenarioActions(rendered.steps(), bddCase)
                    .orElseGet(() -> LibrarySteps.actions(rendered.steps()));
        }
        return new ParsedCase(rendered.name(), actions);
    }

    /** The file's text as a {@code .scenario} is read: UTF-8, with a leading byte-order mark dropped. */
    private static String text(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return text.isEmpty() || text.charAt(0) != BYTE_ORDER_MARK ? text : text.substring(1);
    }

    /** The {@code PETROCORE-<n>} in the file name, else in the nearest folder that names one, else the base name. */
    static String keyOf(Path file) {
        Optional<String> key = find(file.getFileName().toString());
        for (Path folder = file.getParent(); key.isEmpty() && folder != null; folder = folder.getParent()) {
            Path name = folder.getFileName();
            if (name != null) {
                key = find(name.toString());
            }
        }
        if (key.isPresent()) {
            return key.get().toUpperCase(Locale.ROOT);
        }
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        base = dot > 0 ? base.substring(0, dot) : base;
        return SUFFIX.matcher(base).replaceFirst("");
    }

    private static Optional<String> find(String text) {
        Matcher match = CASE_KEY.matcher(text);
        return match.find() ? Optional.of(match.group()) : Optional.empty();
    }
}
