package com.verifone.cat.validate;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * One declared substitution over receipt text: a field the test does not control, replaced
 * by a token so an unchanged receipt compares equal across runs.
 *
 * <p>It carries its own evidence. The compact constructor checks every recorded evidence
 * string against the pattern, so a normaliser that matches nothing fails at class load
 * instead of silently masking nothing. That is how three of DESIGN.md §5.4's five sketches
 * would otherwise have shipped.
 *
 * <p>Patterns are compiled with {@link Pattern#UNIX_LINES} so that {@code .}, {@code $} and
 * {@code ^} treat only {@code \n} as a line break, as Python's {@code re} does. The Python
 * module is the specification; the regexes here are copied from it.
 */
public record Normaliser(
        String name,
        Pattern pattern,
        Function<MatchResult, String> replacement,
        Volatility volatility,
        List<String> evidence,
        Integer corpusFiles,
        boolean listedInDesign54,
        boolean killedByReceiptRuleTxt,
        String note) {

    public Normaliser {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(volatility, "volatility");
        evidence = List.copyOf(evidence);
        note = note == null ? "" : note;
        for (String sample : evidence) {
            if (!pattern.matcher(sample).find()) {
                throw new IllegalArgumentException("normaliser " + name + ": pattern "
                        + pattern.pattern() + " does not match its own recorded evidence "
                        + sample + ". Fix the pattern or the evidence: a normaliser that "
                        + "matches nothing is worse than no normaliser (DESIGN.md §5.4).");
            }
        }
    }

    /**
     * Declares a normaliser whose replacement is a fixed token, e.g. {@code "<DATE>"}.
     *
     * <p>Defaults to {@link Volatility#RUN}, with no evidence and no provenance. Chain the
     * {@code with...} methods to add them.
     */
    public static Normaliser mask(String name, String regex, String token) {
        return mask(name, regex, token, 0);
    }

    /** As {@link #mask(String, String, String)}, with Java regex {@code flags}. */
    public static Normaliser mask(String name, String regex, String token, int flags) {
        return masking(name, regex, m -> token, flags);
    }

    /**
     * Declares a normaliser whose replacement depends on the match: the car-wash code block
     * and the spaced code, where the masking is per line within one match.
     */
    public static Normaliser masking(String name, String regex, Function<MatchResult, String> replacement, int flags) {
        return new Normaliser(name, Pattern.compile(regex, flags | Pattern.UNIX_LINES), replacement,
                Volatility.RUN, List.of(), null, false, false, "");
    }

    public Normaliser withVolatility(Volatility value) {
        return new Normaliser(name, pattern, replacement, value, evidence, corpusFiles,
                listedInDesign54, killedByReceiptRuleTxt, note);
    }

    public Normaliser withEvidence(String... samples) {
        return new Normaliser(name, pattern, replacement, volatility, List.of(samples), corpusFiles,
                listedInDesign54, killedByReceiptRuleTxt, note);
    }

    /** How many of the 7,077 {@code -Receipt.txt} files contain a match (petroapps HEAD 934cf4d). */
    public Normaliser withCorpusFiles(int files) {
        return new Normaliser(name, pattern, replacement, volatility, evidence, files,
                listedInDesign54, killedByReceiptRuleTxt, note);
    }

    /** Marks the field as one DESIGN.md §5.4's sketch listed. */
    public Normaliser inDesign54() {
        return new Normaliser(name, pattern, replacement, volatility, evidence, corpusFiles,
                true, killedByReceiptRuleTxt, note);
    }

    /** Marks the field as one the production {@code RECEIPT-RULE.txt} kills. */
    public Normaliser inReceiptRuleTxt() {
        return new Normaliser(name, pattern, replacement, volatility, evidence, corpusFiles,
                listedInDesign54, true, note);
    }

    public Normaliser withNote(String text) {
        return new Normaliser(name, pattern, replacement, volatility, evidence, corpusFiles,
                listedInDesign54, killedByReceiptRuleTxt, text);
    }

    /** Returns {@code text} with every match replaced. Replacement text is taken literally. */
    public String apply(String text) {
        return pattern.matcher(text).replaceAll(m -> Matcher.quoteReplacement(replacement.apply(m)));
    }

    /** Whether this normaliser would change {@code text}. */
    public boolean matches(String text) {
        return pattern.matcher(text).find();
    }
}
