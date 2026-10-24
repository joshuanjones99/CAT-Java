package com.verifone.cat.pats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.verifone.cat.pats.feature.Gherkin;
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
     * case) or {@code .feature} (one scenario of PATS phrases).
     *
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException when the file is none of those types, or does not parse as one
     */
    public static RunnableCase load(Path file) throws IOException {
        Path absolute = file.toAbsolutePath();
        String name = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        ParsedCase parsed;
        if (name.endsWith(".xml")) {
            parsed = CaseParser.parseActions(Files.readAllBytes(absolute));
        } else if (name.endsWith(".scenario")) {
            parsed = ScenarioParser.parseScenario(text(absolute));
        } else if (name.endsWith(".feature")) {
            parsed = featureCase(absolute);
        } else {
            throw new IllegalArgumentException(absolute.getFileName() + " is not a PATS case: expected a"
                    + " -TestCase.xml, a .scenario or a .feature");
        }
        return parsed.withSource(keyOf(absolute), absolute.toString());
    }

    /** A {@code .feature}'s one scenario as a case, titled by the scenario's name. */
    private static ParsedCase featureCase(Path file) throws IOException {
        Gherkin.Feature feature = Gherkin.read(file);
        if (feature.scenarios().size() != 1) {
            throw new IllegalArgumentException(file.getFileName() + ": expected one scenario, found "
                    + feature.scenarios().size());
        }
        return new ParsedCase(feature.scenarios().get(0).name(), PatsFeature.actionsOf(file));
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
