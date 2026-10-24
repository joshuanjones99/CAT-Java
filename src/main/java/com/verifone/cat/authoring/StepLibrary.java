package com.verifone.cat.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.verifone.cat.pats.feature.Phrase;
import com.verifone.cat.pats.feature.Phrases;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The step library: every phrase an authored case may use, in the Python's order, and the lookups the checker makes.
 *
 * <p>The domain phrases (POS screen, register keys, pinpad, forecourt, store and Commander, checks, screen controls) are
 * read from {@code step-library.json}, which is the Python's own {@code petrotest.authoring.steps} dumped by
 * {@code src/test/resources/authoring/dump_steps.py}; {@link StepLibraryParityTest} fails when the two disagree. The PATS
 * action phrases are not copied: they are {@link Phrases#ALL}, read here at the place the Python's {@code pats_actions}
 * module puts them. Port of {@code petrotest.authoring.registry.STEPS} and the lookups {@code check} makes on it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: the library says what a case may say; no phrase is run here.
 */
public final class StepLibrary {

    /** Where the domain phrases are read from, on the class path. */
    static final String RESOURCE = "/com/verifone/cat/authoring/step-library.json";

    /** The marker in the JSON that stands for the PATS action phrases, which are read from {@link Phrases#ALL}. */
    static final String PATS_MARKER = "patsActions";

    /** The fixture a PATS action phrase's step takes (the Python's {@code pats_scenario}). */
    static final List<String> PATS_FIXTURES = List.of("pats_scenario");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every phrase, in the Python's declaration order. */
    private static final List<StepDef> ALL = load();

    private StepLibrary() {}

    /** Every phrase, in the Python's declaration order. */
    public static List<StepDef> steps() {
        return ALL;
    }

    /** Every phrase {@code line} (keyword stripped) matches, in library order. One is the only good answer. */
    public static List<StepDef> matches(String line, List<StepDef> steps) {
        List<StepDef> out = new ArrayList<>();
        for (StepDef definition : steps) {
            if (definition.matches(line)) {
                out.add(definition);
            }
        }
        return out;
    }

    /**
     * The phrases closest to {@code text}, for an unbound line's message: {@code difflib.get_close_matches} with a cutoff
     * of 0.4, over each phrase's display text. Ties in score are broken by the text, largest first, as
     * {@code heapq.nlargest} breaks them.
     */
    public static List<String> closest(String text, List<StepDef> steps, int n) {
        Map<String, StepDef> phrases = new LinkedHashMap<>();
        for (StepDef definition : steps) {
            phrases.putIfAbsent(definition.display(), definition);
        }
        List<Scored> scored = new ArrayList<>();
        for (String candidate : phrases.keySet()) {
            double ratio = Similarity.ratio(candidate, text);
            if (ratio >= 0.4) {
                scored.add(new Scored(ratio, candidate));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).thenComparing(Scored::text).reversed());
        List<String> out = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(n, scored.size()))) {
            out.add(s.text());
        }
        return out;
    }

    private record Scored(double score, String text) {}

    private static List<StepDef> load() {
        List<StepDef> out = new ArrayList<>();
        try (InputStream in = StepLibrary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("No step library at " + RESOURCE);
            }
            for (JsonNode entry : JSON.readTree(in).path("steps")) {
                if (entry.has(PATS_MARKER)) {
                    for (Phrase phrase : Phrases.ALL) {
                        out.add(patsPhrase(phrase));
                    }
                    continue;
                }
                out.add(StepDef.of(
                        entry.path("text").asText(),
                        entry.path("group").asText(),
                        entry.path("kind").asText(),
                        entry.path("level").asText(),
                        entry.path("example").asText(),
                        entry.path("doc").asText(),
                        strings(entry.path("fixtures"))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Collections.unmodifiableList(out);
    }

    private static StepDef patsPhrase(Phrase phrase) {
        String action = phrase.action() == null ? "the named action" : phrase.action();
        return StepDef.of(
                phrase.text(),
                "PATS actions (migrated)",
                phrase.kind(),
                "pats",
                phrase.example(),
                "PATS's " + action + ", run by the PATS runner (`petrotest.pats.runner`).",
                PATS_FIXTURES);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(item.asText());
        }
        return out;
    }
}
